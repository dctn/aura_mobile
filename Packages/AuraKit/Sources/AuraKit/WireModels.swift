import Foundation

// The backend's JSON, as packages/contracts/src describes it. Everything but ids is optional here, so a missing field
// never fails a whole screen, and fields the app doesn't read are ignored.

enum WireDate {
    private static let plain: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    /// With or without fractional seconds, at any precision, with Z or an offset.
    static func parse(_ text: String?) -> Date? {
        guard let text = text else { return nil }
        guard let dot = text.firstIndex(of: ".") else { return plain.date(from: text) }
        let afterDot = text[text.index(after: dot)...]
        let digits = afterDot.prefix(while: { $0.isNumber })
        let whole = String(text[..<dot]) + String(afterDot[digits.endIndex...])
        guard let seconds = plain.date(from: whole) else { return nil }
        let fraction = Double("0." + String(digits)) ?? 0
        return seconds.addingTimeInterval(fraction)
    }

    /// An instant with the phone's own offset, which is what Health Data files a check-in's local day by.
    static func string(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = TimeZone.current
        return formatter.string(from: date)
    }
}

/// A metric's value: a number, a word or a yes/no. Lists and documents come through as `.other`.
enum WireValue: Decodable, Equatable {
    case number(Double)
    case text(String)
    case flag(Bool)
    case other

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let flag = try? container.decode(Bool.self) {
            self = .flag(flag)
        } else if let number = try? container.decode(Double.self) {
            self = .number(number)
        } else if let text = try? container.decode(String.self) {
            self = .text(text)
        } else {
            self = .other
        }
    }

    var numberValue: Double? {
        if case .number(let value) = self { return value }
        return nil
    }

    var textValue: String? {
        if case .text(let value) = self { return value }
        return nil
    }
}

/// A value that comes through as nil instead of failing everything around it when it can't be read.
struct WireLossy<Item: Decodable>: Decodable {
    let item: Item?

    init(from decoder: Decoder) throws {
        item = try? Item(from: decoder)
    }
}

/// A whole number sent as any JSON number, rounded. Nil when it's missing or too big to be one.
enum WireWhole {
    static func from(_ value: Double?) -> Int? {
        guard let value = value, value.isFinite, abs(value) < 1_000_000_000 else { return nil }
        return Int(value.rounded())
    }
}

/// A list route that hands back `cursor` until it's null.
protocol CursorPage: Decodable {
    associatedtype Item
    var pageItems: [Item] { get }
    var cursor: String? { get }
}

// MARK: Scores: GET /v1/scores/today?date

struct WireScore: Decodable {
    let value: Double?
    let checksCounted: Int?
    let checksNeeded: Int?

    static func model(_ wire: WireScore?, name: String) -> Score {
        return Score(name: name, value: wire?.value, checksCounted: wire?.checksCounted ?? 0, checksNeeded: wire?.checksNeeded ?? 0)
    }
}

struct WireScores: Decodable {
    let attention: WireScore?
    let memory: WireScore?
    let thinking: WireScore?
    let focus: WireScore?

    var model: ScoresToday {
        return ScoresToday(attention: WireScore.model(attention, name: "Attention"),
                           memory: WireScore.model(memory, name: "Memory"),
                           thinking: WireScore.model(thinking, name: "Thinking"),
                           focus: WireScore.model(focus, name: "Focus"))
    }
}

// MARK: Calendar: GET /v1/calendar/events

struct WireEvent: Decodable {
    let id: String
    let title: String?
    let start: String?
    let end: String?
    let kind: String?
    let notes: String?
    let location: String?
    let source: String?
    let seriesId: String?
    let version: Int?

    var model: CalendarEvent? {
        guard let startDate = WireDate.parse(start), let endDate = WireDate.parse(end) else { return nil }
        return CalendarEvent(id: id, title: title ?? "", start: startDate, end: endDate, kind: kind ?? "focus",
                             notes: notes, location: location, source: source ?? "app", seriesId: seriesId,
                             version: version)
    }
}

/// GET /v1/calendar/events/{id}: `{"event": ...}`.
struct WireEventAnswer: Decodable {
    let event: WireEvent
}

struct WireEventPage: CursorPage {
    let events: [WireEvent]?
    let cursor: String?

    var pageItems: [WireEvent] {
        return events ?? []
    }
}

// MARK: Tasks: GET /v1/tasks

struct WireTask: Decodable {
    let id: String
    let title: String?
    let status: String?
    let dueAt: String?
    let hasTime: Bool?
    let scheduledAt: String?
    let completedAt: String?
    /// Find a time places it. A Task service from before the flag sends none, which reads as no.
    let autoAllocate: Bool?

    var model: TaskItem {
        // A due date without a time only names the day, so it isn't the task's time.
        let due: Date? = hasTime == true ? WireDate.parse(dueAt) : nil
        let done = status == "completed" || completedAt != nil
        return TaskItem(id: id, title: title ?? "", at: WireDate.parse(scheduledAt) ?? due, done: done,
                        autoAllocate: autoAllocate == true)
    }
}

struct WireTaskPage: CursorPage {
    let tasks: [WireTask]?
    let cursor: String?

    var pageItems: [WireTask] {
        return tasks ?? []
    }
}

// MARK: Alerts: GET /v1/alerts

struct WireAlert: Decodable {
    struct Action: Decodable {
        let ref: String?
    }

    let id: String
    let featureId: String?
    let date: String?
    let title: String?
    let body: String?
    let action: Action?
    let done: Bool?

    var model: AlertItem {
        return AlertItem(id: id, featureId: featureId ?? "", title: title ?? "", detail: body, date: date ?? "",
                         done: done ?? false, actionRef: action?.ref)
    }
}

struct WireAlertList: Decodable {
    let alerts: [WireAlert]?
}

// MARK: Metrics: GET /v1/metrics/daily, catalogue, availability, series

struct WireDay: Decodable {
    struct Metrics: Decodable {
        let burnoutIndexScore: WireValue?
        let burnoutIndexRisk: WireValue?
    }

    let date: String
    let metrics: Metrics?

    var model: DailyMetrics {
        return DailyMetrics(date: date, burnoutIndexScore: metrics?.burnoutIndexScore?.numberValue,
                            burnoutIndexRisk: metrics?.burnoutIndexRisk?.textValue)
    }
}

struct WireDayPage: CursorPage {
    let days: [WireDay]?
    let cursor: String?

    var pageItems: [WireDay] {
        return days ?? []
    }
}

struct WireParameter: Decodable {
    let name: String?
    let field: String?
    let read: String?
    let unit: String?
    let format: String?

    var model: CatalogueParameter {
        return CatalogueParameter(name: name ?? "", field: field, read: read ?? "none", unit: unit ?? "", format: format ?? "")
    }
}

struct WireSection: Decodable {
    let id: String
    let title: String?
    let parameters: [WireParameter]?

    var model: CatalogueSection {
        let rows = (parameters ?? []).map { $0.model }
        return CatalogueSection(id: id, title: title ?? "", parameters: rows)
    }
}

struct WireCatalogue: Decodable {
    let sections: [WireSection]?
}

struct WireAvailableField: Decodable {
    let field: String
    let latestDate: String?
    let latestValue: WireValue?

    var model: FieldAvailability {
        var number: Double?
        var text: String?
        if let value = latestValue {
            switch value {
            case .number(let found):
                number = found
            case .text(let found):
                text = found
            case .flag(let found):
                text = found ? "Yes" : "No"
            case .other:
                break
            }
        }
        return FieldAvailability(field: field, latestDate: latestDate ?? "", latestNumber: number, latestText: text)
    }
}

struct WireAvailability: Decodable {
    let fields: [WireAvailableField]?
}

struct WireSeriesPoint: Decodable {
    let date: String
    let value: WireValue?
}

struct WireSeries: Decodable {
    let unit: String?
    let points: [WireSeriesPoint]?
}

// MARK: Sessions

struct WireStartedSession: Decodable {
    struct Session: Decodable {
        let id: String
        let plannedMinutes: Int?
        let title: String?
        let kind: String?
        let device: String?
        /// An ISO instant, parsed with WireDate.parse; nil when open-ended.
        let endsAt: String?
    }

    let session: Session
    let joined: Bool?
}

struct WireTrace: Decodable {
    let status: String?
    let stressDrop: Double?
}

// MARK: Beta day score: Session's sessionView, reviews; Capacity's ledger and recovery options; sleep nights

/// The sessionView keys the app reads.
struct WireSessionView: Decodable {
    struct Capacity: Decodable {
        let cost: Double?
        let charged: Bool?
    }

    let id: String
    let kind: String?
    let title: String?
    let startedAt: String?
    let endedAt: String?
    let minutes: Double?
    let activeMinutes: Double?
    let outcome: String?
    let difficulty: String?
    let recoveryType: String?
    let capacity: WireLossy<Capacity>?

    /// A difficulty or recovery type this app doesn't know reads as none. The cost is only kept once charged.
    var model: SessionRecord {
        let charge = capacity?.item
        let cost: Double? = charge?.charged == true ? charge?.cost : nil
        let length = WireWhole.from(activeMinutes) ?? WireWhole.from(minutes) ?? 0
        return SessionRecord(id: id, kind: kind ?? "", title: title, startedAt: WireDate.parse(startedAt),
                             endedAt: WireDate.parse(endedAt), minutes: length, outcome: outcome,
                             difficulty: difficulty.flatMap { Difficulty(rawValue: $0) },
                             recoveryType: recoveryType.flatMap { RecoveryType(rawValue: $0) }, capacityCost: cost)
    }
}

/// `{"session": sessionView}`, the answer of PUT /v1/sessions/{id}/difficulty.
struct WireSessionResponse: Decodable {
    let session: WireSessionView
}

/// `{"session": sessionView, "created": bool}`, the answer of POST /v1/sessions/log.
struct WireLoggedSession: Decodable {
    let session: WireSessionView
    let created: Bool?
}

/// GET /v1/sessions?from&to. A session the app can't read is left out.
struct WireSessionPage: Decodable {
    let sessions: [WireLossy<WireSessionView>]?
    let cursor: String?
}

/// The log body. `minutes` and the times are the caller's; the route checks them.
struct WireLogRequest: Encodable {
    let id: String
    let kind: String
    let recoveryType: String
    let startedAt: String
    let minutes: Int
    let device: String
}

/// Only the review's presence is read: an item with one is already answered.
struct WireReviewMarker: Decodable {
    let blockId: String?
}

struct WireInboxItem: Decodable {
    let blockId: String
    let date: String?
    let title: String?
    let kind: String?
    let start: String?
    let end: String?
    let minutes: Double?
    let review: WireLossy<WireReviewMarker>?

    /// Nil when a time can't be read: that item is left out.
    var model: ReviewItem? {
        guard let startDate = WireDate.parse(start), let endDate = WireDate.parse(end) else { return nil }
        let length = WireWhole.from(minutes) ?? Int((endDate.timeIntervalSince(startDate) / 60).rounded())
        return ReviewItem(blockId: blockId, date: date ?? DayKey.string(startDate), title: title ?? "",
                          kind: kind ?? "focus", start: startDate, end: endDate, minutes: length)
    }
}

/// GET /v1/reviews/inbox?date&days.
struct WireInbox: Decodable {
    let items: [WireLossy<WireInboxItem>]?
}

/// One line of Capacity's ledger. Only the fields that find a session's line are read.
struct WireLedgerEntry: Decodable {
    let source: String?
    let sourceId: String?
    let detail: String?
}

/// GET /v1/capacity/today?date: only the ledger is read.
struct WireCapacityToday: Decodable {
    let ledger: [WireLossy<WireLedgerEntry>]?
}

struct WireRecoveryOption: Decodable {
    let id: String
    let title: String?
    let minutes: Double?
    let maxPoints: Double?
    let fadesAfterMinutes: Double?
    let note: String?

    var model: RecoveryOption {
        return RecoveryOption(id: id, title: title ?? "", minutes: WireWhole.from(minutes) ?? 0,
                              maxPoints: maxPoints ?? 0, fadesAfterMinutes: WireWhole.from(fadesAfterMinutes) ?? 0,
                              note: note ?? "")
    }
}

/// GET /v1/capacity/recovery-options?minutes.
struct WireRecoveryOptions: Decodable {
    let options: [WireLossy<WireRecoveryOption>]?
}

/// The night body. The route is a strict object whose optional keys take a number or nothing, never null: they are
/// left out when nil, which synthesized Encodable does (it writes optionals with encodeIfPresent).
struct WireSleepNightRequest: Encodable {
    let timeZone: String
    let bedtimeAt: String
    let wakeAt: String
    let totalSleepMin: Int
    let awakeMin: Int?
    let inBedMin: Int?

    init(_ night: SleepNight) {
        timeZone = night.timeZone
        bedtimeAt = WireDate.string(night.bedtimeAt)
        wakeAt = WireDate.string(night.wakeAt)
        totalSleepMin = WireSleepNightRequest.dayMinutes(night.totalSleepMin)
        awakeMin = night.awakeMin.map { WireSleepNightRequest.dayMinutes($0) }
        inBedMin = night.inBedMin.map { WireSleepNightRequest.dayMinutes($0) }
    }

    /// Minutes inside the route's 0-1440.
    static func dayMinutes(_ value: Int) -> Int {
        return min(max(value, 0), 1440)
    }
}

/// `{"night": sleepNightView, "created": bool}`.
struct WireSleepNightResponse: Decodable {
    struct Night: Decodable {
        let source: String?
    }

    let night: WireLossy<Night>?
    let created: Bool?
}

// MARK: Day zones: GET /v1/planner/zones?from&to

struct WireZone: Decodable {
    let kind: String?
    let start: String?
    let end: String?

    /// Nil for a kind this app doesn't know or a time it can't read: that zone is left out.
    var model: Zone? {
        guard let name = kind, let zoneKind = ZoneKind(rawValue: name),
              let startDate = WireDate.parse(start), let endDate = WireDate.parse(end) else { return nil }
        return Zone(kind: zoneKind, start: startDate, end: endDate)
    }
}

struct WireZoneDay: Decodable {
    let date: String
    let status: String?
    let score: Double?
    let level: String?
    let heavyMinutes: Double?
    let reason: String?
    let zones: [WireLossy<WireZone>]?

    var model: ZoneDay {
        let known = status.flatMap { ZoneStatus(rawValue: $0) } ?? .unknown
        let placed = (zones ?? []).compactMap { $0.item?.model }
        return ZoneDay(date: date, status: known, score: WireWhole.from(score), level: level,
                       heavyMinutes: WireWhole.from(heavyMinutes) ?? 0, reason: reason, zones: placed)
    }
}

struct WireZoneDays: Decodable {
    let days: [WireLossy<WireZoneDay>]?
}

// MARK: Find a time: POST /v1/planner/allocate, GET /v1/planner/plan/{date}

/// The allocate body. The route is a strict object that takes a value or nothing, never null: the optional fields
/// are left out when nil, which synthesized Encodable does (it writes optionals with encodeIfPresent).
struct WireAllocateRequest: Encodable {
    let date: String
    let dryRun: Bool
    let skipCheck: Bool
    let taskIds: [String]?
    let workdayStart: String?
    let workdayEnd: String?
}

/// `{"plan": planView}`, the answer of both routes.
struct WirePlanResponse: Decodable {
    let plan: WirePlan
}

struct WirePlan: Decodable {
    struct Hours: Decodable {
        let startHour: Double?
        let endHour: Double?

        var model: SharpestHours? {
            guard let start = WireWhole.from(startHour), let end = WireWhole.from(endHour) else { return nil }
            return SharpestHours(startHour: start, endHour: end)
        }
    }

    struct Basis: Decodable {
        let quickCheckAt: String?
        let attention: Double?
        let sleepMinutes: Double?
        let sharpestHours: WireLossy<Hours>?
        let capacityOpening: Double?
        let workLimitMinutes: Double?

        var model: PlanBasis {
            return PlanBasis(quickCheckAt: WireDate.parse(quickCheckAt), attention: attention, sleepMinutes: sleepMinutes,
                             sharpestHours: sharpestHours?.item?.model, capacityOpening: capacityOpening,
                             workLimitMinutes: WireWhole.from(workLimitMinutes))
        }
    }

    struct Part: Decodable {
        let index: Double?
        let of: Double?

        var model: PlanPart? {
            guard let number = WireWhole.from(index), let total = WireWhole.from(of) else { return nil }
            return PlanPart(index: number, of: total)
        }
    }

    struct Placement: Decodable {
        let taskId: String?
        let blockId: String?
        let start: String?
        let end: String?
        let demand: Double?
        let workType: String?
        let reason: String?
        /// Missing from a Planner deployed before the recovery rhythm: that reads as focus.
        let kind: String?
        let part: WireLossy<Part>?

        /// Nil when a time can't be read: that block is left out.
        var model: PlanBlock? {
            guard let startDate = WireDate.parse(start), let endDate = WireDate.parse(end) else { return nil }
            return PlanBlock(taskId: taskId, blockId: blockId, start: startDate, end: endDate, demand: demand ?? 0,
                             workType: workType, reason: reason ?? "", kind: kind ?? "focus", part: part?.item?.model)
        }
    }

    /// The reason is kept as sent, so a code this app doesn't know yet still reads.
    struct Unplaced: Decodable {
        let taskId: String
        let reason: String?
        let detail: String?

        var model: UnplacedTask {
            return UnplacedTask(taskId: taskId, reason: reason ?? "", detail: detail ?? "")
        }
    }

    struct Warnings: Decodable {
        let committedOnRecovery: Double?
        let recoveryMoved: Double?

        var model: PlanWarnings {
            return PlanWarnings(committedOnRecovery: WireWhole.from(committedOnRecovery) ?? 0,
                                recoveryMoved: WireWhole.from(recoveryMoved) ?? 0)
        }
    }

    let date: String
    let generatedAt: String?
    let basis: WireLossy<Basis>?
    let placed: [WireLossy<Placement>]?
    let unplaced: [WireLossy<Unplaced>]?
    /// Missing from a Planner deployed before the recovery rhythm: that reads as no warnings.
    let warnings: WireLossy<Warnings>?
    let version: Double?

    /// Nil when `generatedAt` can't be read, which the caller turns into a bad answer. Every Planner build sends it,
    /// so an unreadable one means the shape changed, and any time put in its place would show a wrong "planned at".
    var model: DayPlan? {
        guard let generated = WireDate.parse(generatedAt) else { return nil }
        let blocks = (placed ?? []).compactMap { $0.item?.model }.sorted { $0.start < $1.start }
        return DayPlan(date: date, generatedAt: generated, basis: basis?.item?.model ?? PlanBasis(), placed: blocks,
                       unplaced: (unplaced ?? []).compactMap { $0.item?.model },
                       warnings: warnings?.item?.model ?? PlanWarnings(), version: WireWhole.from(version) ?? 0)
    }
}

// MARK: Quick check: POST /v1/checks

/// The check body. The route is a strict object whose nullable keys must all be there, so this writes JSON null for
/// them; synthesized Encodable would leave them out (it writes optionals with encodeIfPresent).
struct WireCheckRequest: Encodable {
    struct Pvt: Encodable {
        let durationSeconds: Int
        let reactionTimesMs: [Int]
        let falseStarts: Int
    }

    struct Span: Encodable {
        let spanCorrect: Int
        let spanAttempts: Int
    }

    enum CodingKeys: String, CodingKey {
        case id, takenAt, device, kind, minutesSinceWake, pvt, memory, switching
    }

    let id: String
    let takenAt: String
    let kind: String
    let pvt: Pvt
    let memory: Span?

    init(_ run: QuickCheckRun) {
        id = run.id
        takenAt = WireDate.string(run.takenAt)
        kind = run.kind
        pvt = Pvt(durationSeconds: run.durationSeconds, reactionTimesMs: run.reactionTimesMs.map { WireCheckRequest.wholeMs($0) },
                  falseStarts: run.falseStarts)
        memory = run.memory.map { Span(spanCorrect: $0.spanCorrect, spanAttempts: $0.spanAttempts) }
    }

    /// Whole milliseconds inside the route's 0-30000.
    static func wholeMs(_ value: Double) -> Int {
        guard value.isFinite else { return 0 }
        return Int(min(max(value, 0), 30_000).rounded())
    }

    /// `minutesSinceWake` is always null: the server works it out from the wake time it knows. `switching` is always
    /// null: the rule-switch part isn't built.
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(takenAt, forKey: .takenAt)
        try container.encode("phone", forKey: .device)
        try container.encode(kind, forKey: .kind)
        try container.encodeNil(forKey: .minutesSinceWake)
        try container.encode(pvt, forKey: .pvt)
        if let span = memory {
            try container.encode(span, forKey: .memory)
        } else {
            try container.encodeNil(forKey: .memory)
        }
        try container.encodeNil(forKey: .switching)
    }
}

/// `{"check": checkView, "scores": scoresToday, "created": bool}`.
struct WireCheckResponse: Decodable {
    struct Attention: Decodable {
        let meanRtMs: Double?
        let medianRtMs: Double?
        let lapses: Double?
        let falseStarts: Double?
        let performance: Double?
    }

    struct Check: Decodable {
        let id: String
        let valid: Bool?
        let invalidReason: String?
        let attention: WireLossy<Attention>?
        let qualifiesForNormal: Bool?
    }

    let check: Check
    let scores: WireLossy<WireScores>?
    let created: Bool?

    /// Nil without a readable `valid`, which the caller turns into a bad answer: guessing either way would tell the
    /// person something the server didn't say.
    var model: QuickCheckResult? {
        guard let valid = check.valid else { return nil }
        let attention = check.attention?.item
        let today = scores?.item ?? WireScores(attention: nil, memory: nil, thinking: nil, focus: nil)
        return QuickCheckResult(id: check.id, valid: valid, invalidReason: check.invalidReason,
                                meanRtMs: attention?.meanRtMs ?? 0, medianRtMs: attention?.medianRtMs ?? 0,
                                lapses: WireWhole.from(attention?.lapses) ?? 0,
                                falseStarts: WireWhole.from(attention?.falseStarts) ?? 0,
                                performance: attention?.performance ?? 0,
                                qualifiesForNormal: check.qualifiesForNormal ?? false, scores: today.model)
    }
}

// MARK: Profile: GET /v1/me, PUT /v1/me/profile

struct WireMe: Decodable {
    let timezone: String?
    /// Null until the person sets it. One the app can't read counts as none, so the rest of the profile still reads.
    let workSchedule: WireLossy<WireWorkSchedule>?
    /// Read for the usual sleep times only. One the app can't read counts as none.
    let healthGoals: WireLossy<WireHealthGoals>?
    /// Not optional: a profile write has to name the version it read.
    let version: Int
}

/// The two health goals the app reads: "HH:mm" or null each.
struct WireHealthGoals: Decodable {
    let bedtime: String?
    let wakeTime: String?

    /// Nil when either time is missing or empty.
    var schedule: SleepSchedule? {
        guard let bed = bedtime, !bed.isEmpty, let wake = wakeTime, !wake.isEmpty else { return nil }
        return SleepSchedule(bedtime: bed, wakeTime: wake)
    }
}

struct WireWorkSchedule: Codable {
    let workdayStart: String?
    let workdayEnd: String?
    let lunchStart: String?
    let lunchMinutes: Int?
    let workDays: [Int]?

    init(_ schedule: WorkSchedule) {
        workdayStart = schedule.workdayStart
        workdayEnd = schedule.workdayEnd
        lunchStart = schedule.lunchStart
        lunchMinutes = schedule.lunchMinutes
        workDays = schedule.workDays
    }

    var model: WorkSchedule? {
        guard let start = workdayStart, let end = workdayEnd, let lunch = lunchStart, let minutes = lunchMinutes,
              let days = workDays else { return nil }
        return WorkSchedule(workdayStart: start, workdayEnd: end, lunchStart: lunch, lunchMinutes: minutes, workDays: days)
    }
}

/// The profile write for the work hours alone: fields not sent are kept.
struct WireScheduleUpdate: Encodable {
    let workSchedule: WireWorkSchedule
}

// MARK: Consent: GET /v1/me/consent

struct WireConsent: Decodable {
    let aiAssistant: Bool?
    let productAnalytics: Bool?
}
