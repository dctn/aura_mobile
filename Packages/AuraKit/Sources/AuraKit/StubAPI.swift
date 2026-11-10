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

    /// The stub's tasks, all marked for Find a time. "Book the dentist" comes back unplaced today, as due later, and
    /// placed at 15:00 on a later day.
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
    /// True when any Quick check with at least one reaction counts as valid (UI tests run a few-second check).
    private let shortChecks: Bool
    /// Quick checks by id, as first answered, so a resend gets the same answer.
    private var savedChecks: [String: QuickCheckResult] = [:]
    /// The first valid Quick check of each day (a `DayKey`): it clears the gate and is the plan's `quickCheckAt`.
    private var firstValidCheck: [String: Date] = [:]
    /// Valid checks taken here, added to Attention's `checksCounted`.
    private var countedChecks = 0
    /// Sessions started here (not joined), by id; ended ones are listed by `sessions(from:to:)`.
    private var startedSessions: [String: SessionRecord] = [:]
    /// Ratings by session id, the seeded session's included.
    private var ratings: [String: Difficulty] = [:]
    /// Breaks logged afterwards, by id.
    private var loggedBreaks: [String: SessionRecord] = [:]
    /// Review answers by block id.
    private var reviews: [String: ReviewAnswer] = [:]
    /// Sleep nights by date.
    private var nights: [String: SleepNight] = [:]
    /// The last morning check-in, for tests.
    private var morning: StubMorningCheckin?

    /// Nil starts with no work hours set, as a new account does. `joinsRunningSession` starts with a session already
    /// running on the desktop, which the first start joins until it is stopped. `needsQuickCheck` makes Find a time
    /// for today answer 409 `quick_check_required` unless `skipCheck` is sent or a valid Quick check was taken today.
    /// `shortChecksCount` makes any Quick check with at least one reaction valid, whatever its length.
    public init(workSchedule: WorkSchedule? = StubAPI.defaultSchedule, joinsRunningSession: Bool = false,
                needsQuickCheck: Bool = false, shortChecksCount: Bool = false) {
        held = workSchedule
        otherRunning = joinsRunningSession
        quickCheckNeeded = needsQuickCheck
        shortChecks = shortChecksCount
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

    /// The Figma's numbers. Each valid Quick check taken here adds one to Attention's `checksCounted`.
    public func scoresToday() async throws -> ScoresToday {
        StubAPI.scores(counted: checksCountedSoFar)
    }

    static func scores(counted: Int) -> ScoresToday {
        ScoresToday(attention: Score(name: "Attention", value: 75, checksCounted: counted),
                    memory: Score(name: "Memory", value: 50),
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
    /// answers with that one instead, joined, as the backend's one-running-session rule does. A session of its own is
    /// kept, so `sessions(from:to:)` lists it once it ends.
    public func startSession(id: String, kind: String, mode: String, plannedMinutes: Int?, title: String,
                             recoveryType: RecoveryType?) async throws -> StartedSession {
        if otherSessionRunning {
            return StartedSession(id: StubAPI.runningElsewhereId, joined: true, plannedMinutes: 50, title: "Deep work",
                                  kind: "focus", device: "desktop", endsAt: Date().addingTimeInterval(25 * 60))
        }
        let granted: Int? = mode == "flowtime" ? nil : plannedMinutes
        let type: RecoveryType? = kind == "recovery" ? recoveryType : nil
        noteStarted(SessionRecord(id: id, kind: kind, title: title, startedAt: Date(), minutes: granted ?? 0,
                                  recoveryType: type))
        return StartedSession(id: id, plannedMinutes: granted)
    }

    /// Stopping or completing the other device's session lets the next start go through. Ending one of its own
    /// records its outcome and end.
    public func sessionAction(id: String, action: SessionAction) async throws {
        if id == StubAPI.runningElsewhereId && (action == .stop || action == .complete) {
            otherSessionRunning = false
        }
        if action == .stop || action == .complete {
            noteEnded(id: id, outcome: action == .complete ? "completed" : "stopped", at: Date())
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
    /// `skipCheck` are refused as the Planner refuses them, until a valid Quick check is taken today; the plan then names
    /// that check's time. A later day is planned as the Planner plans it: with no Quick check gate whatever `skipCheck`
    /// says, `quickCheckAt` nil, and "Book the dentist" placed (see `StubPlan.build`). A real run keeps the plan for its
    /// date, today or later, and `events(from:to:)` shows its blocks in that day's range; a dry run doesn't.
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
        let laterDay = key > today
        // Only today has the Quick check gate and a check time; a later day records none.
        let checkAt: Date? = laterDay ? nil : quickCheckTime(day: key)
        if quickCheckNeeded && !skipCheck && key == today && checkAt == nil {
            throw AuraError.server(code: "quick_check_required",
                                   message: "Take today's Quick check first, or send skipCheck to plan without it.", status: 409)
        }
        let version = (stored[key]?.version ?? 0) + 1
        let plan = StubPlan.build(key: key, day: day, schedule: current, skipCheck: skipCheck, taskIds: taskIds,
                                  version: version, calendar: calendar, quickCheckAt: checkAt, laterDay: laterDay)
        if !dryRun {
            stored[key] = plan
        }
        return plan
    }

    public func plan(date: String) async throws -> DayPlan? {
        guard let day = DayKey.date(date) else { return nil }
        return stored[DayKey.string(day)]
    }

    // MARK: Quick check

    /// Scored as Health Data scores it (see `StubCheck`). A resent id answers with the stored result. A valid check
    /// counts toward the normal here (the stub knows no wake time or sleep) and clears its day's Quick check gate.
    public func submitCheck(_ run: QuickCheckRun) async throws -> QuickCheckResult {
        return record(run)
    }

    /// The lock is taken once for the whole check, outside any async code, so two sends of one id store one result.
    private func record(_ run: QuickCheckRun) -> QuickCheckResult {
        lock.lock()
        defer { lock.unlock() }
        if let saved = savedChecks[run.id] { return saved }
        let measured = StubCheck.measure(run, shortChecksCount: shortChecks)
        let valid = measured.invalidReason == nil
        if valid {
            countedChecks += 1
            // The day's first valid check stays the one the plan names.
            let key = DayKey.string(run.takenAt)
            let isFirst: Bool = firstValidCheck[key].map { run.takenAt < $0 } ?? true
            if isFirst {
                firstValidCheck[key] = run.takenAt
            }
        }
        let result = QuickCheckResult(id: run.id, valid: valid, invalidReason: measured.invalidReason,
                                      meanRtMs: measured.meanRtMs, medianRtMs: measured.medianRtMs,
                                      lapses: measured.lapses, falseStarts: measured.falseStarts,
                                      performance: measured.performance, qualifiesForNormal: valid,
                                      scores: StubAPI.scores(counted: countedChecks))
        savedChecks[run.id] = result
        return result
    }

    /// Same locking as `current`: only here, outside any async code.
    private func quickCheckTime(day key: String) -> Date? {
        lock.lock()
        defer { lock.unlock() }
        return firstValidCheck[key]
    }

    /// Same locking as `current`: only here, outside any async code.
    private var checksCountedSoFar: Int {
        lock.lock()
        defer { lock.unlock() }
        return countedChecks
    }

    // MARK: Beta day score

    /// The seeded focus session: today 09:00-09:50, "Deep work", not rated yet.
    static let seededId = "s-deep"

    /// Lists the seeded session, the sessions started here that ended, and the logged breaks, newest first.
    public func sessions(from: String, to: String) async throws -> [SessionRecord] {
        let inRange = sessionsSoFar(now: Date()).filter { record in
            guard let start = record.startedAt else { return false }
            let key = DayKey.string(start)
            return key >= from && key <= to
        }
        return LiveAPI.newestFirst(inRange)
    }

    /// Stores the rating and the cost it sets. An unknown id is taken as a focus session the stub just made. Another
    /// kind is refused as Session refuses it (409 `not_focus_session`).
    public func setDifficulty(sessionId: String, _ difficulty: Difficulty) async throws -> SessionRecord {
        return try rate(sessionId, difficulty, now: Date())
    }

    /// Capacity's wording for a rated or unrated focus session; nil for any other.
    public func capacityLine(sessionId: String) async throws -> String? {
        return line(for: sessionId)
    }

    /// 409 `log_overlaps` when the break overlaps a logged one or the seeded session. A resent id is created false.
    public func logBreak(id: String, type: RecoveryType, startedAt: Date, minutes: Int) async throws -> LoggedBreak {
        return try storeBreak(id: id, type: type, start: startedAt, minutes: minutes, now: Date())
    }

    /// Capacity's five recovery breaks with their real lifts, in Capacity's order.
    public func recoveryOptions(minutes: Int) async throws -> [RecoveryOption] {
        return StubAPI.recoveryChoices(minutes: minutes)
    }

    /// A meeting and a recovery block today; an answered one drops out.
    public func reviewInbox(date: String, days: Int) async throws -> [ReviewItem] {
        let answered = answeredBlocks()
        return StubAPI.inboxItems(now: Date()).filter { !answered.contains($0.blockId) }
    }

    /// Stored as sent. A recovery block without a type is still taken, as the server takes it.
    public func submitReview(_ answer: ReviewAnswer) async throws {
        saveReview(answer)
    }

    public func sleepSchedule() async throws -> SleepSchedule? {
        return SleepSchedule(bedtime: "23:00", wakeTime: "07:00")
    }

    /// Stored by date: created the first time, false for the same date again.
    public func putSleepNight(_ night: SleepNight) async throws -> SleepNightResult {
        return SleepNightResult(created: saveNight(night), source: "appleHealth")
    }

    public func morningCheckin(alertness: Int?, fellAsleepAt: String?, wokeAt: String?, at: Date) async throws {
        saveMorning(StubMorningCheckin(alertness: alertness, fellAsleepAt: fellAsleepAt, wokeAt: wokeAt, at: at))
    }

    // MARK: Beta day score: what tests read

    /// The last morning check-in sent.
    var lastMorningCheckin: StubMorningCheckin? {
        lock.lock()
        defer { lock.unlock() }
        return morning
    }

    /// Review answers by block id.
    var savedReviews: [String: ReviewAnswer] {
        lock.lock()
        defer { lock.unlock() }
        return reviews
    }

    /// Sleep nights by date.
    var savedNights: [String: SleepNight] {
        lock.lock()
        defer { lock.unlock() }
        return nights
    }

    // MARK: Beta day score: the stub's rules

    /// Capacity's demand for a rating and what 50 minutes at it cost. No rating counts as normal.
    static func charge(_ difficulty: Difficulty?) -> (demand: Int, cost: Double) {
        switch difficulty {
        case .easy?: return (demand: 20, cost: 0.1)
        case .light?: return (demand: 40, cost: 0.2)
        case .hard?: return (demand: 80, cost: 0.8)
        case .veryHard?: return (demand: 100, cost: 1.3)
        case .normal?, nil: return (demand: 60, cost: 0.5)
        }
    }

    /// "50 min of desk work at demand 80: -0.8. You rated it Hard.", without the last sentence when not rated.
    static func lineText(_ rating: Difficulty?) -> String {
        let charged = charge(rating)
        let base = "50 min of desk work at demand \(charged.demand): -\(String(format: "%.1f", charged.cost))."
        guard let rating = rating else { return base }
        return "\(base) You rated it \(rating.label)."
    }

    /// Today 09:00-09:50 on the phone's calendar.
    static func seededWindow(now: Date) -> (start: Date, end: Date) {
        let start = DayKey.calendar.date(bySettingHour: 9, minute: 0, second: 0, of: now) ?? now
        return (start: start, end: start.addingTimeInterval(50 * 60))
    }

    static func seeded(now: Date, rating: Difficulty?) -> SessionRecord {
        let window = seededWindow(now: now)
        return SessionRecord(id: seededId, kind: "focus", title: "Deep work", startedAt: window.start,
                             endedAt: window.end, minutes: 50, outcome: "completed", difficulty: rating,
                             capacityCost: charge(rating).cost)
    }

    /// Capacity's real values (services/capacity/src/service/views.ts), the full lift at 20 minutes.
    static func recoveryChoices(minutes: Int) -> [RecoveryOption] {
        let length = min(max(minutes, 0), 20)
        return [
            RecoveryOption(id: "nap", title: "Nap", minutes: length, maxPoints: 10, fadesAfterMinutes: 120,
                           note: "A 10-20 minute nap gives a small lift for about two hours; longer naps can bring grogginess first."),
            RecoveryOption(id: "walk", title: "Walk", minutes: length, maxPoints: 9, fadesAfterMinutes: 50,
                           note: "A walk of about 20 minutes just before hard work gives a short lift. It does not pay back sleep debt."),
            RecoveryOption(id: "rest", title: "Rest", minutes: length, maxPoints: 5, fadesAfterMinutes: 40,
                           note: "About 20 minutes of rest away from the screen gives a small lift that fades within about 40 minutes."),
            RecoveryOption(id: "outside", title: "Time outside", minutes: length, maxPoints: 9, fadesAfterMinutes: 50,
                           note: "About 20 minutes outside gives a short lift, like a walk. It does not pay back sleep debt."),
            RecoveryOption(id: "social", title: "Talk with someone", minutes: length, maxPoints: 5, fadesAfterMinutes: 40,
                           note: "About 20 minutes talking with someone gives a small lift that fades within about 40 minutes."),
        ]
    }

    static func inboxItems(now: Date) -> [ReviewItem] {
        let calendar = DayKey.calendar
        let today = DayKey.string(now, calendar: calendar)
        func at(_ hour: Int, _ minute: Int) -> Date {
            return calendar.date(bySettingHour: hour, minute: minute, second: 0, of: now) ?? now
        }
        return [
            ReviewItem(blockId: "b-sync", date: today, title: "Team sync", kind: "meeting", start: at(10, 0),
                       end: at(10, 30), minutes: 30),
            ReviewItem(blockId: "e1-review", date: today, title: "10 Min nap", kind: "recovery", start: at(15, 54),
                       end: at(16, 4), minutes: 10),
        ]
    }

    // The helpers below take the lock, as `current` does: only here, outside any async code.

    /// A resent start keeps the record already there.
    private func noteStarted(_ record: SessionRecord) {
        lock.lock()
        defer { lock.unlock() }
        if startedSessions[record.id] == nil {
            startedSessions[record.id] = record
        }
    }

    /// The first end counts. An open-ended run gets the minutes it ran; a focus session gets its cost.
    private func noteEnded(id: String, outcome: String, at end: Date) {
        lock.lock()
        defer { lock.unlock() }
        guard var record = startedSessions[id], record.outcome == nil else { return }
        record.outcome = outcome
        record.endedAt = end
        if record.minutes <= 0, let start = record.startedAt {
            record.minutes = max(1, Int((end.timeIntervalSince(start) / 60).rounded()))
        }
        if record.isFocus {
            record.capacityCost = StubAPI.charge(ratings[id]).cost
        }
        startedSessions[id] = record
    }

    private func sessionsSoFar(now: Date) -> [SessionRecord] {
        lock.lock()
        defer { lock.unlock() }
        let seeded = StubAPI.seeded(now: now, rating: ratings[StubAPI.seededId])
        let ended: [SessionRecord] = startedSessions.values.filter { $0.outcome != nil }
        let logged: [SessionRecord] = Array(loggedBreaks.values)
        return [seeded] + ended + logged
    }

    private func rate(_ id: String, _ difficulty: Difficulty, now: Date) throws -> SessionRecord {
        lock.lock()
        defer { lock.unlock() }
        if id == StubAPI.seededId {
            ratings[id] = difficulty
            return StubAPI.seeded(now: now, rating: difficulty)
        }
        let made = SessionRecord(id: id, kind: "focus", startedAt: now.addingTimeInterval(-50 * 60), endedAt: now,
                                 minutes: 50, outcome: "completed")
        var record = startedSessions[id] ?? made
        guard record.isFocus else {
            throw AuraError.server(code: "not_focus_session", message: "Only a focus session can be rated.", status: 409)
        }
        ratings[id] = difficulty
        record.difficulty = difficulty
        record.capacityCost = StubAPI.charge(difficulty).cost
        startedSessions[id] = record
        return record
    }

    private func line(for id: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        if id != StubAPI.seededId {
            guard let record = startedSessions[id], record.isFocus, record.outcome != nil else { return nil }
        }
        return StubAPI.lineText(ratings[id])
    }

    private func storeBreak(id: String, type: RecoveryType, start: Date, minutes: Int, now: Date) throws -> LoggedBreak {
        lock.lock()
        defer { lock.unlock() }
        if let saved = loggedBreaks[id] {
            return LoggedBreak(session: saved, created: false)
        }
        let end = start.addingTimeInterval(TimeInterval(minutes) * 60)
        var taken: [(start: Date, end: Date)] = [StubAPI.seededWindow(now: now)]
        for record in loggedBreaks.values {
            if let from = record.startedAt, let to = record.endedAt {
                taken.append((start: from, end: to))
            }
        }
        if taken.contains(where: { start < $0.end && end > $0.start }) {
            throw AuraError.server(code: "log_overlaps", message: "This break overlaps another session.", status: 409)
        }
        let record = SessionRecord(id: id, kind: "recovery", title: type.label, startedAt: start, endedAt: end,
                                   minutes: minutes, outcome: "logged", recoveryType: type)
        loggedBreaks[id] = record
        return LoggedBreak(session: record, created: true)
    }

    private func answeredBlocks() -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return Set(reviews.keys)
    }

    private func saveReview(_ answer: ReviewAnswer) {
        lock.lock()
        defer { lock.unlock() }
        reviews[answer.item.blockId] = answer
    }

    /// True when no night was stored for the date before.
    private func saveNight(_ night: SleepNight) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let created = nights[night.date] == nil
        nights[night.date] = night
        return created
    }

    private func saveMorning(_ checkin: StubMorningCheckin) {
        lock.lock()
        defer { lock.unlock() }
        morning = checkin
    }
}

/// One morning check-in as the stub got it.
struct StubMorningCheckin: Sendable, Equatable {
    let alertness: Int?
    let fellAsleepAt: String?
    let wokeAt: String?
    let at: Date
}

/// Health Data's PVT-B scoring (services/health-data/src/service/attention.ts), for the stub only. The real app shows
/// what the backend sends.
enum StubCheck {
    static let minSeconds = 170
    static let maxSeconds = 200
    static let lapseMs = 355.0
    static let falseStartMs = 100.0
    static let minResponses = 30
    static let maxFalseStartShare = 0.2

    struct Measures: Equatable {
        let meanRtMs: Double
        let medianRtMs: Double
        let lapses: Int
        let falseStarts: Int
        let performance: Double
        let invalidReason: String?
    }

    /// Reactions under 100 ms count as false starts, not reactions. With `shortChecksCount` any run with a reaction is
    /// valid; otherwise it needs 170-200 s, 30+ answers and false starts at most 20% of answers plus taps.
    static func measure(_ run: QuickCheckRun, shortChecksCount: Bool) -> Measures {
        let reactions = run.reactionTimesMs.filter { $0 >= falseStartMs }
        let falseStarts = run.falseStarts + (run.reactionTimesMs.count - reactions.count)
        var mean = 0.0
        var middle = 0.0
        var lapses = 0
        var performance = 0.0
        if !reactions.isEmpty {
            let count = Double(reactions.count)
            lapses = reactions.filter { $0 >= lapseMs }.count
            mean = rounded(reactions.reduce(0, +) / count, 1)
            middle = rounded(median(reactions), 1)
            performance = rounded(1 - Double(lapses + falseStarts) / Double(reactions.count + falseStarts), 4)
        }
        let reason: String?
        if shortChecksCount && !run.reactionTimesMs.isEmpty {
            reason = nil
        } else {
            reason = invalidReason(run, answers: reactions.count, falseStarts: falseStarts)
        }
        return Measures(meanRtMs: mean, medianRtMs: middle, lapses: lapses, falseStarts: falseStarts,
                        performance: performance, invalidReason: reason)
    }

    /// The server's code, checked in the server's order; the screen turns it into words.
    private static func invalidReason(_ run: QuickCheckRun, answers: Int, falseStarts: Int) -> String? {
        if run.durationSeconds < minSeconds || run.durationSeconds > maxSeconds { return "not_3_minutes" }
        if answers < minResponses { return "too_few_responses" }
        if Double(falseStarts) > maxFalseStartShare * Double(answers + run.falseStarts) { return "too_many_false_starts" }
        return nil
    }

    private static func median(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        let mid = sorted.count / 2
        return sorted.count % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
    }

    private static func rounded(_ value: Double, _ digits: Int) -> Double {
        let scale = pow(10.0, Double(digits))
        return (value * scale).rounded() / scale
    }
}

/// Find a time's answer for the stub: fixed blocks for the stub tasks. With work hours, a band and a work day they
/// follow the recovery rhythm inside the stub zones; otherwise the plain plan. The real app shows what the Planner sends.
enum StubPlan {
    private static let early = "09:00: early in your working day. Your sharpest hours aren't known yet, so hard work goes first."

    /// `quickCheckAt` is the day's first valid Quick check taken on the stub. Without one the basis names 08:40, or
    /// nothing when `skipCheck` was sent. `laterDay` is a date after today, which the Planner plans without the Quick
    /// check: the basis names no check time whatever `skipCheck` says, and "Book the dentist" (due tomorrow, so unplaced
    /// today) is placed 15:00-15:30 in either variant, leaving nothing unplaced.
    static func build(key: String, day: Date, schedule: WorkSchedule?, skipCheck: Bool, taskIds: [String]?,
                      version: Int, calendar: Calendar, quickCheckAt: Date? = nil, laterDay: Bool = false) -> DayPlan {
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
        // A later day has room for the task that is only due later when planned today.
        let dentist: (owner: String, block: PlanBlock) =
            (owner: "t2", block: PlanBlock(taskId: "t2", blockId: "plan-\(key)-t2", start: time(15, 0), end: time(15, 30),
                                           demand: 20, workType: "routine",
                                           reason: "Due soon: 15:00 is a light hour for a short call."))
        let all: [(owner: String, block: PlanBlock)] = laterDay ? blocks + [dentist] : blocks
        let wanted: Set<String>? = taskIds.map { Set($0) }
        let placed = all.filter { wanted?.contains($0.owner) ?? true }.map { $0.block }
        let tomorrow = DayKey.string(calendar.date(byAdding: .day, value: 1, to: day) ?? day, calendar: calendar)
        let dueLater: [UnplacedTask] = laterDay ? [] : [UnplacedTask(taskId: "t2", reason: "dueLater",
                                                                     detail: "Planned for \(tomorrow).")]
        let unplaced = dueLater.filter { wanted?.contains($0.taskId) ?? true }
        // A later day is planned without the Quick check, so it records no check time.
        let checkAt: Date? = laterDay ? nil : (quickCheckAt ?? (skipCheck ? nil : time(8, 40)))
        let basis = PlanBasis(quickCheckAt: checkAt, attention: 71, sleepMinutes: 432,
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
