import Foundation

/// Answers without a network, with the Figma's numbers, for UI tests and CI. Holds no real data.
public final class StubAPI: AuraAPI, @unchecked Sendable {
    /// Every day of the week, so CI screenshots show zones on a weekend too.
    public static let defaultSchedule = WorkSchedule(workdayStart: "09:00", workdayEnd: "18:00", lunchStart: "13:00",
                                                     lunchMinutes: 60, workDays: [1, 2, 3, 4, 5, 6, 7])
    /// The score every stub work day has: a steady day (68-78), so 2 h of heavy work.
    static let zoneScore = 72

    /// The id of the session the stub pretends is running on the person's computer.
    public static let runningElsewhereId = "running-elsewhere"

    /// The stub's tasks, all marked for Find a time. "Book the dentist" always comes back unplaced, as due later.
    static let stubTasks = [TaskItem(id: "t1", title: "Reply to Ana", autoAllocate: true),
                            TaskItem(id: "t2", title: "Book the dentist", autoAllocate: true),
                            TaskItem(id: "t3", title: "Write the review", autoAllocate: true)]

    private let lock = NSLock()
    private var held: WorkSchedule?
    /// True while the other device's session is still running and a start joins it.
    private var otherRunning: Bool
    /// Find a time's real runs, one per date (a `DayKey`).
    private var savedPlans: [String: DayPlan] = [:]
    /// True when today's plan asks for the Quick check first, as the Planner does without `skipCheck`.
    private let quickCheckNeeded: Bool

    /// Nil starts with no work hours set, as a new account does. `joinsRunningSession` starts with a session already
    /// running on the desktop, which the first start joins until it is stopped. `needsQuickCheck` makes Find a time
    /// for today answer 409 `quick_check_required` unless `skipCheck` is sent.
    public init(workSchedule: WorkSchedule? = StubAPI.defaultSchedule, joinsRunningSession: Bool = false,
                needsQuickCheck: Bool = false) {
        held = workSchedule
        otherRunning = joinsRunningSession
        quickCheckNeeded = needsQuickCheck
    }

    /// Capacity's band for a score, as the stub stands in for it.
    static func level(_ score: Int) -> String { StubZones.level(score) }
    /// The Planner's heavy minutes for a score's band.
    static func heavyMinutes(_ score: Int) -> Int { StubZones.heavyMinutes(score) }

    /// The title of one of the stub's tasks, or "" for none.
    static func taskTitle(_ id: String?) -> String {
        stubTasks.first(where: { $0.id == id })?.title ?? ""
    }

    private func day(_ offset: Int) -> String {
        DayKey.string(Calendar.current.date(byAdding: .day, value: offset, to: Date()) ?? Date())
    }

    public func scoresToday() async throws -> ScoresToday {
        ScoresToday(attention: Score(name: "Attention", value: 75), memory: Score(name: "Memory", value: 50),
                    thinking: Score(name: "Thinking", value: 50), focus: Score(name: "Focus", value: 71))
    }

    /// Always ahead of the clock, so Upcoming shows them whenever CI runs. Then the blocks of Find a time's real runs
    /// that start in [from, to), as the Planner writes them to the calendar. A dry run writes nothing.
    public func events(from: Date, to: Date) async throws -> [CalendarEvent] {
        let now = Date()
        let nap = now.addingTimeInterval(15 * 60)
        let building = nap.addingTimeInterval(15 * 60)
        let fixed = [CalendarEvent(id: "e1", title: "10 Min nap", start: nap, end: building, kind: "recovery"),
                     CalendarEvent(id: "e2", title: "Xcode Building", start: building, end: building.addingTimeInterval(60 * 60), kind: "focus")]
        let blocks = stored.values.flatMap { $0.placed }.filter { $0.start >= from && $0.start < to }.sorted { $0.start < $1.start }
        let planned = blocks.map { block -> CalendarEvent in
            CalendarEvent(id: block.blockId ?? UUID().uuidString, title: block.title(taskTitle: StubAPI.taskTitle(block.taskId)),
                          start: block.start, end: block.end, kind: block.kind)
        }
        return fixed + planned
    }

    public func tasks() async throws -> [TaskItem] {
        StubAPI.stubTasks
    }

    public func alerts(from: String, to: String) async throws -> [AlertItem] {
        [AlertItem(id: "a1", featureId: "DX-04", title: "Take a 10 minutes nap", date: day(0), actionRef: "nap-10")]
    }

    public func markAlertDone(id: String) async throws {}
    public func logMood(_ mood: Int, at: Date) async throws {}

    public func dailyMetrics(from: String, to: String) async throws -> [DailyMetrics] {
        [DailyMetrics(date: day(0), burnoutIndexScore: 72, burnoutIndexRisk: "high")]
    }

    private func row(_ name: String, _ field: String?, _ unit: String) -> CatalogueParameter {
        CatalogueParameter(name: name, field: field, read: field == nil ? "none" : "series", unit: unit)
    }

    public func catalogue() async throws -> [CatalogueSection] {
        let titles = ["Running", "Cycling", "Gym and Strength", "Swimming", "Winter Sports", "Body Metrics", "Heart", "Stress",
                      "Sleep", "Recovery", "Focus", "Screen Wellness", "Meetings", "Mental Wellness", "Respiration", "Mobility",
                      "Nutrition", "Cycle Tracking", "Hearing", "Symptoms", "Burnout", "Habits", "Progress", "Other"]
        let movement = CatalogueSection(id: "movement", title: "Movement", parameters: [
            row("Step Count", "stepCount", "steps"), row("Walk Distance", "walkDistanceKm", "km"),
            row("Active Calories", "activeCalories", "kcal"), row("Resting Calories", "restingCalories", "kcal"),
            row("Total Calories", "totalCalories", "kcal"), row("Exercise Duration", "exerciseMinutes", "min"),
            row("Run Distance", nil, "km"), row("Active Minutes", nil, "min"), row("Standing Hours", nil, "h"),
            row("Stairs Climbed", nil, "floors"), row("Physical Effort Score", nil, ""), row("Workout Effort Rating", nil, ""),
            row("Movement Time", nil, "min"), row("Push Count", nil, ""), row("Wheelchair Distance", nil, "km"),
            row("Daily Step Goal Progress", nil, "%"), row("Active Calorie Goal Progress", nil, "%"),
            row("Stand Hours Goal Progress", nil, "%"), row("Inactivity Minutes", nil, "min"), row("Move Reminder Count", nil, ""),
        ])
        return [movement] + titles.map { CatalogueSection(id: $0.lowercased(), title: $0, parameters: []) }
    }

    public func availability(from: String, to: String) async throws -> [FieldAvailability] {
        let latest: [(String, Double)] = [("stepCount", 8200), ("walkDistanceKm", 3.4), ("activeCalories", 420),
                                          ("restingCalories", 1500), ("totalCalories", 1920), ("exerciseMinutes", 32)]
        return latest.map { FieldAvailability(field: $0.0, latestDate: day(0), latestNumber: $0.1) }
    }

    /// The Walk Distance bars from the Figma, repeated across the range.
    public func series(field: String, from: String, to: String) async throws -> MetricSeries {
        let bars = [2.3, 3.8, 3.1, 4.5, 3.6, 2.2, 3.0, 4.2, 5.0, 3.5, 3.1, 4.0, 4.5, 3.4]
        let start = DayKey.date(from) ?? Date()
        let end = DayKey.date(to) ?? Date()
        let count = max(1, (Calendar.current.dateComponents([.day], from: start, to: end).day ?? 0) + 1)
        let points = (0..<count).map { i -> MetricPoint in
            let date = Calendar.current.date(byAdding: .day, value: i, to: start) ?? start
            return MetricPoint(date: DayKey.string(date), value: bars[(bars.count - count % bars.count + i) % bars.count])
        }
        return MetricSeries(field: field, unit: "km", points: points)
    }

    /// Grants what was asked, open-ended for flowtime as the backend does. While the other device's session runs,
    /// answers with that one instead, joined, as the backend's one-running-session rule does.
    public func startSession(id: String, kind: String, mode: String, plannedMinutes: Int?, title: String) async throws -> StartedSession {
        if otherSessionRunning {
            return StartedSession(id: StubAPI.runningElsewhereId, joined: true, plannedMinutes: 50, title: "Deep work",
                                  kind: "focus", device: "desktop", endsAt: Date().addingTimeInterval(25 * 60))
        }
        let granted: Int? = mode == "flowtime" ? nil : plannedMinutes
        return StartedSession(id: id, plannedMinutes: granted)
    }

    /// Stopping or completing the other device's session lets the next start go through.
    public func sessionAction(id: String, action: SessionAction) async throws {
        if id == StubAPI.runningElsewhereId && (action == .stop || action == .complete) {
            otherSessionRunning = false
        }
    }
    public func sessionTrace(id: String) async throws -> SessionTrace { SessionTrace(status: "noData") }
    public func addEvent(title: String, start: Date, end: Date, kind: String) async throws {}
    public func addTask(title: String, plannedFor: String) async throws {}
    public func grantHealthConsent() async throws {}
    public func setTimeZoneIfMissing(_ identifier: String) async throws {}

    // MARK: Work hours and day zones

    /// The lock is only taken here, outside any async code.
    private var current: WorkSchedule? {
        get {
            lock.lock()
            defer { lock.unlock() }
            return held
        }
        set {
            lock.lock()
            defer { lock.unlock() }
            held = newValue
        }
    }

    /// Same locking as `current`: only here, outside any async code.
    private var otherSessionRunning: Bool {
        get {
            lock.lock()
            defer { lock.unlock() }
            return otherRunning
        }
        set {
            lock.lock()
            defer { lock.unlock() }
            otherRunning = newValue
        }
    }

    public func workSchedule() async throws -> WorkSchedule? {
        return current
    }

    public func setWorkSchedule(_ schedule: WorkSchedule) async throws {
        current = schedule
    }

    /// One day per date, both ends included, at most 62. Work days are all ready with the same score.
    public func dayZones(from: String, to: String) async throws -> [ZoneDay] {
        let schedule = current
        let calendar = DayKey.calendar
        guard let first = DayKey.date(from, calendar: calendar), let last = DayKey.date(to, calendar: calendar) else { return [] }
        var days: [ZoneDay] = []
        var date = first
        while date <= last && days.count < 62 {
            days.append(StubZones.day(DayKey.string(date, calendar: calendar), schedule: schedule, score: StubAPI.zoneScore,
                                      calendar: calendar))
            guard let next = calendar.date(byAdding: .day, value: 1, to: date) else { break }
            date = next
        }
        return days
    }

    // MARK: Find a time

    /// Same locking as `current`: only here, outside any async code.
    private var stored: [String: DayPlan] {
        get {
            lock.lock()
            defer { lock.unlock() }
            return savedPlans
        }
        set {
            lock.lock()
            defer { lock.unlock() }
            savedPlans = newValue
        }
    }

    /// The same plan for every day, inside the stub zones. A past date and, with `needsQuickCheck`, today without
    /// `skipCheck` are refused as the Planner refuses them. A real run keeps the plan for its date; a dry run doesn't.
    public func allocate(date: String, dryRun: Bool, skipCheck: Bool, taskIds: [String]?) async throws -> DayPlan {
        let calendar = DayKey.calendar
        guard let day = DayKey.date(date, calendar: calendar) else {
            throw AuraError.server(code: "validation_failed", message: "Send the date as yyyy-MM-dd.", status: 400)
        }
        let key = DayKey.string(day, calendar: calendar)
        let today = DayKey.string(Date(), calendar: calendar)
        if key < today {
            throw AuraError.server(code: "date_in_past", message: "Plan today or a later day.", status: 400)
        }
        if quickCheckNeeded && !skipCheck && key == today {
            throw AuraError.server(code: "quick_check_required",
                                   message: "Take today's Quick check first, or send skipCheck to plan without it.", status: 409)
        }
        let version = (stored[key]?.version ?? 0) + 1
        let plan = StubPlan.build(key: key, day: day, schedule: current, skipCheck: skipCheck, taskIds: taskIds,
                                  version: version, calendar: calendar)
        if !dryRun {
            stored[key] = plan
        }
        return plan
    }

    public func plan(date: String) async throws -> DayPlan? {
        guard let day = DayKey.date(date) else { return nil }
        return stored[DayKey.string(day)]
    }
}

/// Find a time's answer for the stub: fixed blocks for the stub tasks. With work hours, a band and a work day they
/// follow the recovery rhythm inside the stub zones; otherwise the plain plan. The real app shows what the Planner sends.
enum StubPlan {
    private static let early = "09:00: early in your working day. Your sharpest hours aren't known yet, so hard work goes first."

    static func build(key: String, day: Date, schedule: WorkSchedule?, skipCheck: Bool, taskIds: [String]?,
                      version: Int, calendar: Calendar) -> DayPlan {
        let parts = calendar.dateComponents([.year, .month, .day], from: day)
        func time(_ hour: Int, _ minute: Int) -> Date {
            return StubZones.at(hour * 60 + minute, parts, calendar) ?? day
        }
        let zoneDay = StubZones.day(key, schedule: schedule, score: StubAPI.zoneScore, calendar: calendar)
        let rhythm = zoneDay.status == .ready && zoneDay.heavyMinutes > 0
        // Each block with the task it belongs to, so a rest goes when its task is left out.
        let blocks: [(owner: String, block: PlanBlock)]
        let warnings: PlanWarnings
        if rhythm {
            blocks = [
                (owner: "t3", block: PlanBlock(taskId: "t3", blockId: "plan-\(key)-t3-p1", start: time(9, 0), end: time(10, 30),
                                               demand: 78, workType: "deepFocus", reason: early,
                                               part: PlanPart(index: 1, of: 2))),
                (owner: "t3", block: PlanBlock(taskId: "t3", blockId: "plan-\(key)-t3-p2", start: time(10, 45), end: time(11, 15),
                                               demand: 78, workType: "deepFocus",
                                               reason: "10:45: the rest of it, after the short recovery.",
                                               part: PlanPart(index: 2, of: 2))),
                (owner: "t3", block: PlanBlock(taskId: nil, blockId: "plan-\(key)-t3-rest", start: time(11, 15), end: time(11, 25),
                                               demand: 0, reason: "A short rest after deep work.", kind: "recovery")),
                (owner: "t1", block: PlanBlock(taskId: "t1", blockId: "plan-\(key)-t1", start: time(14, 0), end: time(14, 30),
                                               demand: 25, workType: "routine",
                                               reason: "Light enough for any hour: 14:00 keeps the better hours for harder work.")),
            ]
            warnings = PlanWarnings(committedOnRecovery: 1, recoveryMoved: 1)
        } else {
            blocks = [
                (owner: "t3", block: PlanBlock(taskId: "t3", blockId: "plan-\(key)-t3", start: time(9, 0), end: time(11, 0),
                                               demand: 78, workType: "deepFocus", reason: early)),
                (owner: "t1", block: PlanBlock(taskId: "t1", blockId: "plan-\(key)-t1", start: time(11, 15), end: time(11, 45),
                                               demand: 25, workType: "routine",
                                               reason: "Light enough for any hour: 11:15 keeps the better hours for harder work.")),
            ]
            warnings = PlanWarnings()
        }
        let wanted: Set<String>? = taskIds.map { Set($0) }
        let placed = blocks.filter { wanted?.contains($0.owner) ?? true }.map { $0.block }
        let tomorrow = DayKey.string(calendar.date(byAdding: .day, value: 1, to: day) ?? day, calendar: calendar)
        let unplaced = [UnplacedTask(taskId: "t2", reason: "dueLater", detail: "Planned for \(tomorrow).")]
            .filter { wanted?.contains($0.taskId) ?? true }
        let basis = PlanBasis(quickCheckAt: skipCheck ? nil : time(8, 40), attention: 71, sleepMinutes: 432,
                              sharpestHours: nil, capacityOpening: Double(StubAPI.zoneScore), workLimitMinutes: nil)
        return DayPlan(date: key, generatedAt: Date(), basis: basis, placed: placed, unplaced: unplaced, warnings: warnings,
                       version: version)
    }
}

/// The Planner's zone placement (services/planner/src/service/zones/classify.ts), for the stub only. The real app
/// draws what the backend sends.
enum StubZones {
    private static let maxHeavyBlock = 90
    private static let shortRecovery = 15
    private static let longStretch = 180
    private static let minHeavy = 15
    private static let snap = 15

    private struct Placed {
        let kind: ZoneKind
        let start: Int
        let end: Int
    }

    static func day(_ key: String, schedule: WorkSchedule?, score: Int, calendar: Calendar) -> ZoneDay {
        guard let schedule = schedule else { return ZoneDay(date: key, status: .needsSchedule) }
        guard let date = DayKey.date(key, calendar: calendar) else { return ZoneDay(date: key, status: .unknown) }
        // Calendar weekdays run 1 = Sunday ... 7 = Saturday; the schedule's run 1 = Monday ... 7 = Sunday.
        let weekday = (calendar.component(.weekday, from: date) + 5) % 7 + 1
        if !schedule.workDays.contains(weekday) { return ZoneDay(date: key, status: .dayOff) }
        let placed = classify(schedule, heavyMinutes: heavyMinutes(score))
        let heavy = placed.filter { $0.kind == .heavyWork }.reduce(0) { $0 + $1.end - $1.start }
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        let zones = placed.compactMap { zone -> Zone? in
            guard let start = at(zone.start, parts, calendar), let end = at(zone.end, parts, calendar) else { return nil }
            return Zone(kind: zone.kind, start: start, end: end)
        }
        return ZoneDay(date: key, status: .ready, score: score, level: level(score), heavyMinutes: heavy,
                       reason: reason(score, heavyMinutes: heavy), zones: zones)
    }

    /// Capacity's `openingBand` cut points since 9 Nov 2026. The Planner no longer cuts the score itself: it reads
    /// the band Capacity sends (evidence.ts ZONE_LEVELS is gone), so this only stands in for Capacity here.
    static func level(_ score: Int) -> String {
        if score >= 89 { return "peak" }
        if score >= 79 { return "focused" }
        if score >= 68 { return "steady" }
        if score >= 57 { return "shallow" }
        return "depleted"
    }

    /// The Planner's heavy minutes by band (ZONES.heavyMinutes).
    static func heavyMinutes(_ score: Int) -> Int {
        switch level(score) {
        case "peak": return 240
        case "focused": return 180
        case "steady": return 120
        default: return 0
        }
    }

    /// The Planner's `zoneReason` wording.
    static func reason(_ score: Int, heavyMinutes: Int) -> String {
        let base = "Your capacity score is \(score)"
        if heavyMinutes > 0 { return "\(base), so about \(hoursText(heavyMinutes)) of heavy work today, first thing." }
        if level(score) == "depleted" { return "\(base), so today is for recovery and light work only." }
        return "\(base), so keep to lighter work today."
    }

    private static func hoursText(_ minutes: Int) -> String {
        let h = minutes / 60
        let m = minutes % 60
        if h == 0 { return "\(m) min" }
        return m == 0 ? "\(h) h" : "\(h) h \(m) min"
    }

    /// The wall-clock time `minute` after midnight on that day.
    static func at(_ minute: Int, _ day: DateComponents, _ calendar: Calendar) -> Date? {
        var parts = day
        parts.hour = minute / 60
        parts.minute = minute % 60
        return calendar.date(from: parts)
    }

    /// Lunch, heavy work from the start of the day in blocks with a short recovery between, then light work, split
    /// when a stretch is long. A low day's extra recovery is left out: stub days are never low.
    private static func classify(_ schedule: WorkSchedule, heavyMinutes: Int) -> [Placed] {
        let start = WorkSchedule.minutes(schedule.workdayStart) ?? 0
        let end = WorkSchedule.minutes(schedule.workdayEnd) ?? 0
        let lunch = WorkSchedule.minutes(schedule.lunchStart) ?? start
        let lunchEnd = lunch + schedule.lunchMinutes
        var zones: [Placed] = []
        var free: [(Int, Int)] = []
        var wanted = heavyMinutes
        for (from, to) in [(start, lunch), (lunchEnd, end)] {
            var cursor = from
            while wanted >= minHeavy && to - cursor >= minHeavy {
                let block = min(maxHeavyBlock, wanted, to - cursor)
                zones.append(Placed(kind: .heavyWork, start: cursor, end: cursor + block))
                cursor += block
                wanted -= block
                if wanted < minHeavy || to - cursor < shortRecovery + minHeavy { break }
                zones.append(Placed(kind: .shortRecovery, start: cursor, end: cursor + shortRecovery))
                cursor += shortRecovery
            }
            if to > cursor { free.append((cursor, to)) }
        }
        for (from, to) in free {
            zones.append(contentsOf: lightWork(from, to))
        }
        if schedule.lunchMinutes > 0 {
            zones.append(Placed(kind: .heavyRecovery, start: lunch, end: lunchEnd))
        }
        return zones.sorted { $0.start < $1.start }
    }

    private static func lightWork(_ from: Int, _ to: Int) -> [Placed] {
        if to <= from { return [] }
        if to - from <= longStretch { return [Placed(kind: .lightWork, start: from, end: to)] }
        let middle = from + (to - from - shortRecovery) / 2 / snap * snap
        return lightWork(from, middle) + [Placed(kind: .shortRecovery, start: middle, end: middle + shortRecovery)]
            + lightWork(middle + shortRecovery, to)
    }
}

/// Always signed in, for UI tests and CI.
public struct StubAuth: AuthService {
    public init() {}
    public func restore() async -> AuthState { .signedIn(email: nil) }
    public func requestCode(email: String) async throws -> AuthStep {
        AuthStep(kind: .signIn, email: email, session: "stub", codeLength: 8)
    }
    public func finish(step: AuthStep, code: String) async throws -> AuthState { .signedIn(email: step.email) }
    public func resend(step: AuthStep) async throws -> AuthStep { step }
    public func signOut() async {}
}
