import Foundation

/// yyyy-MM-dd ranges, counted on a fixed calendar so daylight saving can't shift a day.
enum DayRanges {
    private static let calendar: Calendar = {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0) ?? TimeZone.current
        return utc
    }()

    /// Consecutive pieces of at most `maxDays` days, both ends included. A range it can't read goes through whole, for the server to answer.
    static func split(from: String, to: String, maxDays: Int) -> [(from: String, to: String)] {
        guard let first = DayKey.date(from, calendar: calendar),
              let last = DayKey.date(to, calendar: calendar),
              first <= last else {
            return [(from: from, to: to)]
        }
        var pieces: [(from: String, to: String)] = []
        var start = first
        while start <= last {
            guard let pieceEnd = calendar.date(byAdding: .day, value: maxDays - 1, to: start),
                  let next = calendar.date(byAdding: .day, value: maxDays, to: start) else { break }
            let end = min(pieceEnd, last)
            pieces.append((from: DayKey.string(start, calendar: calendar), to: DayKey.string(end, calendar: calendar)))
            start = next
        }
        return pieces
    }

    /// The day before `key`, or `key` itself if it can't be read.
    static func dayBefore(_ key: String) -> String {
        guard let day = DayKey.date(key, calendar: calendar),
              let earlier = calendar.date(byAdding: .day, value: -1, to: day) else {
            return key
        }
        return DayKey.string(earlier, calendar: calendar)
    }

    /// `from`, moved up when the range is longer than `maxDays`.
    static func clampedStart(from: String, to: String, maxDays: Int) -> String {
        guard let first = DayKey.date(from, calendar: calendar),
              let last = DayKey.date(to, calendar: calendar),
              let earliest = calendar.date(byAdding: .day, value: 1 - maxDays, to: last),
              first < earliest else {
            return from
        }
        return DayKey.string(earliest, calendar: calendar)
    }
}

/// The backend, reached through `ServiceClient`. Routes and shapes follow packages/contracts/src in aws_docs.
public final class LiveAPI: AuraAPI, Sendable {
    private let client: ServiceClient
    private let policyVersion: String
    private let now: @Sendable () -> Date

    /// Longest range the metrics routes take.
    private static let maxMetricDays = 400
    /// Longest range `GET /v1/calendar/events` takes.
    private static let maxEventDays = 62
    private static let maxPlannedMinutes = 240
    /// Longest range `GET /v1/planner/zones` takes.
    private static let maxZoneDays = 31

    init(config: AppConfig, tokens: any AccessTokenSource, session: URLSession, sleep: @escaping AuraSleep,
         now: @escaping @Sendable () -> Date) {
        self.client = ServiceClient(config: config, tokens: tokens, session: session, sleep: sleep, now: now)
        self.policyVersion = config.consentPolicyVersion
        self.now = now
    }

    // MARK: Home

    public func scoresToday() async throws -> ScoresToday {
        let query = ["date": DayKey.string(now())]
        let scores = try await get(WireScores.self, .healthData, "/v1/scores/today", query: query)
        return scores.model
    }

    /// Events overlapping `from` to `to`. The route files an event under the local day it starts, so one running past
    /// midnight is only found by asking from the day before. The extra ones are dropped here. An event starting more
    /// than a day before `from` isn't found.
    public func events(from: Date, to: Date) async throws -> [CalendarEvent] {
        if from > to { return [] }
        var byId: [String: CalendarEvent] = [:]
        let firstDay = DayRanges.dayBefore(DayKey.string(from))
        let ranges = DayRanges.split(from: firstDay, to: DayKey.string(to), maxDays: LiveAPI.maxEventDays)
        for range in ranges {
            let query = ["from": range.from, "to": range.to]
            let wire = try await allPages(WireEventPage.self, .calendar, "/v1/calendar/events", query: query)
            for item in wire {
                guard let event = item.model else { continue }
                if LiveAPI.overlaps(event, from: from, to: to) {
                    byId[event.id] = event
                }
            }
        }
        return byId.values.sorted { $0.start < $1.start }
    }

    public func tasks() async throws -> [TaskItem] {
        let wire = try await allPages(WireTaskPage.self, .task, "/v1/tasks", query: [:])
        return wire.map { $0.model }
    }

    public func alerts(from: String, to: String) async throws -> [AlertItem] {
        let list = try await get(WireAlertList.self, .healthData, "/v1/alerts", query: ["from": from, "to": to])
        return (list.alerts ?? []).map { $0.model }
    }

    public func markAlertDone(id: String) async throws {
        try await post(.healthData, "/v1/alerts/\(URLEscape.component(id))/done", [:])
    }

    public func logMood(_ mood: Int, at: Date) async throws {
        let body: [String: Any] = ["id": LiveAPI.newId(), "kind": "mood", "takenAt": WireDate.string(at), "mood": mood]
        try await post(.healthData, "/v1/checkins", body)
    }

    public func dailyMetrics(from: String, to: String) async throws -> [DailyMetrics] {
        var days: [DailyMetrics] = []
        for range in DayRanges.split(from: from, to: to, maxDays: LiveAPI.maxMetricDays) {
            let query = ["from": range.from, "to": range.to, "fields": "burnoutIndexScore,burnoutIndexRisk"]
            let wire = try await allPages(WireDayPage.self, .healthData, "/v1/metrics/daily", query: query)
            days.append(contentsOf: wire.map { $0.model })
        }
        return days
    }

    // MARK: Search and metrics

    public func catalogue() async throws -> [CatalogueSection] {
        let catalogue = try await get(WireCatalogue.self, .healthData, "/v1/metrics/catalogue", query: [:])
        return (catalogue.sections ?? []).map { $0.model }
    }

    /// A longer range is clamped to its newest 400 days: this route only answers each field's latest value.
    public func availability(from: String, to: String) async throws -> [FieldAvailability] {
        let start = DayRanges.clampedStart(from: from, to: to, maxDays: LiveAPI.maxMetricDays)
        let query = ["from": start, "to": to]
        let answer = try await get(WireAvailability.self, .healthData, "/v1/metrics/availability", query: query)
        return (answer.fields ?? []).map { $0.model }
    }

    public func series(field: String, from: String, to: String) async throws -> MetricSeries {
        var unit: String?
        var points: [MetricPoint] = []
        let path = "/v1/metrics/series/\(URLEscape.component(field))"
        for range in DayRanges.split(from: from, to: to, maxDays: LiveAPI.maxMetricDays) {
            let query = ["from": range.from, "to": range.to]
            let part = try await get(WireSeries.self, .healthData, path, query: query)
            if unit == nil {
                unit = part.unit
            }
            for point in part.points ?? [] {
                points.append(MetricPoint(date: point.date, value: point.value?.numberValue))
            }
        }
        return MetricSeries(field: field, unit: unit, points: points)
    }

    // MARK: Sessions

    public func startSession(id: String, kind: String, mode: String, plannedMinutes: Int?, title: String) async throws -> StartedSession {
        // The route wants the key present: null for an open-ended run. A timer without minutes is refused there.
        var planned: Any = NSNull()
        if mode != "flowtime", let minutes = plannedMinutes, minutes > 0 {
            planned = min(minutes, LiveAPI.maxPlannedMinutes)
        }
        let body: [String: Any] = ["id": id, "kind": kind, "mode": mode, "plannedMinutes": planned,
                                   "title": title, "device": "phone"]
        let encoded = try LiveAPI.encode(body)
        let data = try await client.send(.session, "POST", "/v1/sessions", body: encoded)
        let started = try LiveAPI.decode(WireStartedSession.self, data)
        let session = started.session
        return StartedSession(id: session.id, joined: started.joined ?? false, plannedMinutes: session.plannedMinutes,
                              title: session.title, kind: session.kind, device: session.device,
                              endsAt: WireDate.parse(session.endsAt))
    }

    public func sessionAction(id: String, action: SessionAction) async throws {
        try await post(.session, "/v1/sessions/\(URLEscape.component(id))/actions", ["action": action.rawValue])
    }

    public func sessionTrace(id: String) async throws -> SessionTrace {
        let path = "/v1/trends/sessions/\(URLEscape.component(id))"
        let trace = try await get(WireTrace.self, .healthData, path, query: [:])
        return SessionTrace(status: trace.status ?? "noData", stressDrop: trace.stressDrop)
    }

    // MARK: Things a mode adds

    public func addEvent(title: String, start: Date, end: Date, kind: String) async throws {
        var body: [String: Any] = ["id": LiveAPI.newId(), "title": title,
                                   "start": WireDate.string(start), "end": WireDate.string(end)]
        if let block = LiveAPI.blockKind(kind) {
            body["kind"] = block
        }
        try await post(.calendar, "/v1/calendar/events", body)
    }

    public func addTask(title: String, plannedFor: String) async throws {
        let body: [String: Any] = ["id": LiveAPI.newId(), "title": title, "plannedFor": plannedFor]
        try await post(.task, "/v1/tasks", body)
    }

    // MARK: Consent

    public func grantHealthConsent() async throws {
        // Only health data changes here: the person's other choices are read first and sent back as they are.
        let current = try await get(WireConsent.self, .identity, "/v1/me/consent", query: [:])
        let body: [String: Any] = ["policyVersion": policyVersion, "healthData": true,
                                   "aiAssistant": current.aiAssistant ?? false,
                                   "productAnalytics": current.productAnalytics ?? false]
        let encoded = try LiveAPI.encode(body)
        _ = try await client.send(.identity, "PUT", "/v1/me/consent", body: encoded)
        await client.noteConsentGranted()
    }

    // MARK: Profile

    /// The route refuses a write without the version just read, so a zone set in between is never overwritten.
    public func setTimeZoneIfMissing(_ identifier: String) async throws {
        let body = try LiveAPI.encode(["timezone": identifier])
        var retried = false
        while true {
            let me = try await get(WireMe.self, .identity, "/v1/me", query: [:])
            if let zone = me.timezone, !zone.isEmpty { return }
            do {
                _ = try await client.send(.identity, "PUT", "/v1/me/profile", body: body,
                                          headers: ["If-Match": "\"v\(me.version)\""])
                return
            } catch AuraError.server(_, _, let status) where status == 412 && !retried {
                // Something else wrote to the profile since the read: read again, once.
                retried = true
            }
        }
    }

    public func workSchedule() async throws -> WorkSchedule? {
        let me = try await get(WireMe.self, .identity, "/v1/me", query: [:])
        return me.workSchedule?.item?.model
    }

    /// Sends only the work hours, with the version just read; a write in between makes it read again, once.
    public func setWorkSchedule(_ schedule: WorkSchedule) async throws {
        let body = try LiveAPI.encodeValue(WireScheduleUpdate(workSchedule: WireWorkSchedule(schedule)))
        var retried = false
        while true {
            let me = try await get(WireMe.self, .identity, "/v1/me", query: [:])
            do {
                _ = try await client.send(.identity, "PUT", "/v1/me/profile", body: body,
                                          headers: ["If-Match": "\"v\(me.version)\""])
                return
            } catch AuraError.server(_, _, let status) where status == 412 && !retried {
                retried = true
            }
        }
    }

    // MARK: Day zones

    /// One entry per day. A range over 31 days is asked in pieces, as the route takes at most 31.
    public func dayZones(from: String, to: String) async throws -> [ZoneDay] {
        var days: [ZoneDay] = []
        for range in DayRanges.split(from: from, to: to, maxDays: LiveAPI.maxZoneDays) {
            let query = ["from": range.from, "to": range.to]
            let answer = try await get(WireZoneDays.self, .planner, "/v1/planner/zones", query: query)
            days.append(contentsOf: (answer.days ?? []).compactMap { $0.item?.model })
        }
        return days
    }

    // MARK: Find a time

    /// Reads the profile's work hours first and sends them as the day window; without any the Planner uses
    /// 09:00-18:00. A failed read stops here instead of planning in the wrong hours. Errors come through as the
    /// Planner sends them: 409 `quick_check_required` (ask, then call again with `skipCheck`), 400 `date_in_past`.
    public func allocate(date: String, dryRun: Bool, skipCheck: Bool, taskIds: [String]?) async throws -> DayPlan {
        let schedule = try await workSchedule()
        let request = WireAllocateRequest(date: date, dryRun: dryRun, skipCheck: skipCheck, taskIds: taskIds,
                                          workdayStart: schedule?.workdayStart, workdayEnd: schedule?.workdayEnd)
        let body = try LiveAPI.encodeValue(request)
        let data = try await client.send(.planner, "POST", "/v1/planner/allocate", body: body)
        return try LiveAPI.dayPlan(data)
    }

    /// Nil on a 404, which is how the Planner says no plan has been made for that day yet.
    public func plan(date: String) async throws -> DayPlan? {
        let path = "/v1/planner/plan/\(URLEscape.component(date))"
        let data: Data
        do {
            data = try await client.send(.planner, "GET", path)
        } catch AuraError.server(_, _, let status) where status == 404 {
            return nil
        }
        return try LiveAPI.dayPlan(data)
    }

    // MARK: Quick check

    /// The id is the run's own, so a resend answers with the stored check. Errors come through as Health Data sends
    /// them: 403 `consent_required` is `AuraError.consentRequired`, as on the other Health Data routes.
    public func submitCheck(_ run: QuickCheckRun) async throws -> QuickCheckResult {
        let body = try LiveAPI.encodeValue(WireCheckRequest(run))
        let data = try await client.send(.healthData, "POST", "/v1/checks", body: body)
        let answer = try LiveAPI.decode(WireCheckResponse.self, data)
        guard let result = answer.model else { throw LiveAPI.badResponse }
        return result
    }

    // MARK: Helpers

    private static func overlaps(_ event: CalendarEvent, from: Date, to: Date) -> Bool {
        if from == to {
            return event.start <= from && event.end >= from
        }
        return event.start < to && event.end > from
    }

    /// Calendar blocks are focus, meeting or recovery. Anything else is left for the server to classify.
    private static func blockKind(_ kind: String) -> String? {
        switch kind {
        case "focus", "meeting", "recovery":
            return kind
        case "walk":
            return "recovery"
        default:
            return nil
        }
    }

    /// Ids are chosen here, so resending a create is safe.
    private static func newId() -> String {
        return UUID().uuidString.lowercased()
    }

    private func get<Answer: Decodable>(_ type: Answer.Type, _ service: ServiceName, _ path: String,
                                        query: [String: String]) async throws -> Answer {
        let data = try await client.send(service, "GET", path, query: query)
        return try LiveAPI.decode(type, data)
    }

    private func post(_ service: ServiceName, _ path: String, _ body: [String: Any]) async throws {
        let encoded = try LiveAPI.encode(body)
        _ = try await client.send(service, "POST", path, body: encoded)
    }

    private func allPages<Page: CursorPage>(_ type: Page.Type, _ service: ServiceName, _ path: String,
                                            query: [String: String]) async throws -> [Page.Item] {
        var items: [Page.Item] = []
        var cursor: String?
        repeat {
            var pageQuery = query
            if let current = cursor {
                pageQuery["cursor"] = current
            }
            let page = try await get(type, service, path, query: pageQuery)
            items.append(contentsOf: page.pageItems)
            if let next = page.cursor, !next.isEmpty {
                cursor = next
            } else {
                cursor = nil
            }
        } while cursor != nil
        return items
    }

    private static func encode(_ body: [String: Any]) throws -> Data {
        return try JSONSerialization.data(withJSONObject: body)
    }

    private static func encodeValue<Body: Encodable>(_ body: Body) throws -> Data {
        return try JSONEncoder().encode(body)
    }

    private static func decode<Answer: Decodable>(_ type: Answer.Type, _ data: Data) throws -> Answer {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw LiveAPI.badResponse
        }
    }

    /// The plan in `{"plan": ...}`. One without a readable `generatedAt` is a bad answer, like one that isn't JSON.
    private static func dayPlan(_ data: Data) throws -> DayPlan {
        let answer = try decode(WirePlanResponse.self, data)
        guard let plan = answer.plan.model else { throw LiveAPI.badResponse }
        return plan
    }

    private static var badResponse: AuraError {
        return AuraError.server(code: "bad_response", message: "Aura sent an answer this app can't read.", status: 200)
    }
}
