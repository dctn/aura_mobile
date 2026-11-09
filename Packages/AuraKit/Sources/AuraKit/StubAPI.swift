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

    private let lock = NSLock()
    private var held: WorkSchedule?
    /// True while the other device's session is still running and a start joins it.
    private var otherRunning: Bool

    /// Nil starts with no work hours set, as a new account does. `joinsRunningSession` starts with a session already
    /// running on the desktop, which the first start joins until it is stopped.
    public init(workSchedule: WorkSchedule? = StubAPI.defaultSchedule, joinsRunningSession: Bool = false) {
        held = workSchedule
        otherRunning = joinsRunningSession
    }

    /// Capacity's band for a score, as the stub stands in for it.
    static func level(_ score: Int) -> String { StubZones.level(score) }
    /// The Planner's heavy minutes for a score's band.
    static func heavyMinutes(_ score: Int) -> Int { StubZones.heavyMinutes(score) }

    private func day(_ offset: Int) -> String {
        DayKey.string(Calendar.current.date(byAdding: .day, value: offset, to: Date()) ?? Date())
    }

    public func scoresToday() async throws -> ScoresToday {
        ScoresToday(attention: Score(name: "Attention", value: 75), memory: Score(name: "Memory", value: 50),
                    thinking: Score(name: "Thinking", value: 50), focus: Score(name: "Focus", value: 71))
    }

    /// Always ahead of the clock, so Upcoming shows them whenever CI runs.
    public func events(from: Date, to: Date) async throws -> [CalendarEvent] {
        let now = Date()
        let nap = now.addingTimeInterval(15 * 60)
        let building = nap.addingTimeInterval(15 * 60)
        return [CalendarEvent(id: "e1", title: "10 Min nap", start: nap, end: building, kind: "recovery"),
                CalendarEvent(id: "e2", title: "Xcode Building", start: building, end: building.addingTimeInterval(60 * 60), kind: "focus")]
    }

    public func tasks() async throws -> [TaskItem] {
        [TaskItem(id: "t1", title: "Reply to Ana"), TaskItem(id: "t2", title: "Book the dentist")]
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
    private static func at(_ minute: Int, _ day: DateComponents, _ calendar: Calendar) -> Date? {
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
