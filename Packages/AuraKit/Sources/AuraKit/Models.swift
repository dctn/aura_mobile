import Foundation

// What the screens work with. Field names follow the backend contracts in aws_docs (packages/contracts/src).

// MARK: Config

/// Public settings in App/Resources/aura-dev.json. Client ids and API URLs aren't secrets.
public struct AppConfig: Codable, Sendable, Equatable {
    public var stage: String
    public var region: String
    public var userPoolId: String
    public var clientId: String
    public var platform: String
    public var appVersion: String
    public var consentPolicyVersion: String
    public var services: [String: String]

    public init(stage: String, region: String, userPoolId: String, clientId: String, platform: String, appVersion: String,
                consentPolicyVersion: String, services: [String: String]) {
        self.stage = stage; self.region = region; self.userPoolId = userPoolId; self.clientId = clientId
        self.platform = platform; self.appVersion = appVersion; self.consentPolicyVersion = consentPolicyVersion
        self.services = services
    }

    public func baseURL(_ service: ServiceName) -> URL? {
        services[service.rawValue].flatMap(URL.init(string:))
    }
}

public enum ServiceName: String, Sendable, CaseIterable {
    case identity, healthData = "health-data", notification, calendar, capacity, content, habit, planner, session, task
}

// MARK: Home

public struct Score: Sendable, Equatable {
    public var name: String
    /// 100 is the person's normal; nil while the app is still learning it.
    public var value: Double?
    public var checksCounted: Int
    public var checksNeeded: Int
    public init(name: String, value: Double?, checksCounted: Int = 0, checksNeeded: Int = 0) {
        self.name = name; self.value = value; self.checksCounted = checksCounted; self.checksNeeded = checksNeeded
    }
}

public struct ScoresToday: Sendable, Equatable {
    public var attention: Score
    public var memory: Score
    public var thinking: Score
    public var focus: Score
    public init(attention: Score, memory: Score, thinking: Score, focus: Score) {
        self.attention = attention; self.memory = memory; self.thinking = thinking; self.focus = focus
    }
}

public struct CalendarEvent: Identifiable, Sendable, Equatable {
    public var id: String
    public var title: String
    public var start: Date
    public var end: Date
    public var kind: String
    public var notes: String?
    public var location: String?
    /// "app", "apple" (read from Apple Calendar on the Mac) or "planner" (placed by Find a time).
    public var source: String
    /// The repeating event this occurrence belongs to, or nil for a one-off.
    public var seriesId: String?
    /// The version to send as If-Match when changing it. Nil when the answer had none.
    public var version: Int?

    public init(id: String, title: String, start: Date, end: Date, kind: String = "focus", notes: String? = nil,
                location: String? = nil, source: String = "app", seriesId: String? = nil, version: Int? = nil) {
        self.id = id; self.title = title; self.start = start; self.end = end; self.kind = kind
        self.notes = notes; self.location = location; self.source = source; self.seriesId = seriesId
        self.version = version
    }

    /// Apple Calendar owns it: only its kind may change here, and it can't be deleted here (409 apple_event_read_only).
    public var isFromApple: Bool { source == "apple" }

    /// One day of a repeating event: a change or delete applies to that day only.
    public var isOccurrence: Bool { seriesId != nil }
}

/// The event editor's fields. The same limits as the Calendar contract (calendar.ts).
public struct EventDraft: Sendable, Equatable {
    public static let kinds = ["focus", "meeting", "recovery"]
    static let maxTitle = 500
    static let maxLocation = 500
    static let maxNotes = 20_000

    public var title: String
    public var start: Date
    public var end: Date
    /// "focus", "meeting" or "recovery".
    public var kind: String
    public var location: String
    public var notes: String

    public init(title: String, start: Date, end: Date, kind: String = "focus", location: String = "", notes: String = "") {
        self.title = title; self.start = start; self.end = end; self.kind = kind
        self.location = location; self.notes = notes
    }

    /// The fields of an event as it is now.
    public init(_ event: CalendarEvent) {
        let kind = EventDraft.kinds.contains(event.kind) ? event.kind : "focus"
        self.init(title: event.title, start: event.start, end: event.end, kind: kind,
                  location: event.location ?? "", notes: event.notes ?? "")
    }

    /// A new event starting at the next half hour after `now`, an hour long.
    public static func new(after now: Date, calendar: Calendar = .current) -> EventDraft {
        let wholeMinute = calendar.dateInterval(of: .minute, for: now)?.start ?? now
        let minute = calendar.component(.minute, from: wholeMinute)
        let toNext = minute < 30 ? 30 - minute : 60 - minute
        let start = wholeMinute.addingTimeInterval(TimeInterval(toNext * 60))
        return EventDraft(title: "", start: start, end: start.addingTimeInterval(3600))
    }

    private var trimmedTitle: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// What stops it being saved, in words, or nil when it can be.
    public var problem: String? {
        if trimmedTitle.isEmpty { return "Give it a title." }
        if trimmedTitle.count > EventDraft.maxTitle { return "The title is too long." }
        if end <= start { return "It has to end after it starts." }
        if location.count > EventDraft.maxLocation { return "The location is too long." }
        if notes.count > EventDraft.maxNotes { return "The notes are too long." }
        return nil
    }

    /// The body of POST /v1/calendar/events, without its id. Empty location and notes are left out.
    func createBody() -> [String: Any] {
        var body: [String: Any] = ["title": trimmedTitle, "start": WireDate.string(start), "end": WireDate.string(end),
                                   "kind": kind]
        if let location = EventDraft.text(location) { body["location"] = location }
        if let notes = EventDraft.text(notes) { body["notes"] = notes }
        return body
    }

    /// The body of PATCH /v1/calendar/events/{id}: only what differs from `event`, so an unchanged kind stays the
    /// automatic one and an Apple event sends only its kind. Cleared location and notes are sent as null. Empty when
    /// nothing changed.
    func changes(from event: CalendarEvent) -> [String: Any] {
        let before = EventDraft(event)
        var body: [String: Any] = [:]
        if event.isFromApple {
            if kind != event.kind { body["kind"] = kind }
            return body
        }
        if trimmedTitle != event.title { body["title"] = trimmedTitle }
        if start != event.start { body["start"] = WireDate.string(start) }
        if end != event.end { body["end"] = WireDate.string(end) }
        if kind != event.kind { body["kind"] = kind }
        if EventDraft.text(location) != EventDraft.text(before.location) {
            body["location"] = EventDraft.text(location) ?? NSNull()
        }
        if EventDraft.text(notes) != EventDraft.text(before.notes) {
            body["notes"] = EventDraft.text(notes) ?? NSNull()
        }
        return body
    }

    /// Trimmed text, or nil when there is none.
    private static func text(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

public struct TaskItem: Identifiable, Sendable, Equatable {
    public var id: String
    public var title: String
    /// Its time when it has one (a block or a timed due date).
    public var at: Date?
    public var done: Bool
    /// The person asked Find a time to place it (Task's `autoAllocate`).
    public var autoAllocate: Bool
    public init(id: String, title: String, at: Date? = nil, done: Bool = false, autoAllocate: Bool = false) {
        self.id = id; self.title = title; self.at = at; self.done = done; self.autoAllocate = autoAllocate
    }
}

// MARK: Find a time: POST /v1/planner/allocate, GET /v1/planner/plan/{date}

/// A day's plan from Find a time. With work hours, a morning band and a work day it follows the recovery rhythm
/// (zones kept free, a break after deep work, tasks split to fit, recovery moved around the person's own blocks).
public struct DayPlan: Sendable, Equatable {
    public var date: String
    public var generatedAt: Date
    public var basis: PlanBasis
    /// In time order.
    public var placed: [PlanBlock]
    public var unplaced: [UnplacedTask]
    public var warnings: PlanWarnings
    public var version: Int
    public init(date: String, generatedAt: Date, basis: PlanBasis = PlanBasis(), placed: [PlanBlock] = [],
                unplaced: [UnplacedTask] = [], warnings: PlanWarnings = PlanWarnings(), version: Int = 1) {
        self.date = date; self.generatedAt = generatedAt; self.basis = basis; self.placed = placed
        self.unplaced = unplaced; self.warnings = warnings; self.version = version
    }
}

/// What the plan was built on.
public struct PlanBasis: Sendable, Equatable {
    public var quickCheckAt: Date?
    public var attention: Double?
    public var sleepMinutes: Double?
    public var sharpestHours: SharpestHours?
    public var capacityOpening: Double?
    public var workLimitMinutes: Int?
    public init(quickCheckAt: Date? = nil, attention: Double? = nil, sleepMinutes: Double? = nil,
                sharpestHours: SharpestHours? = nil, capacityOpening: Double? = nil, workLimitMinutes: Int? = nil) {
        self.quickCheckAt = quickCheckAt; self.attention = attention; self.sleepMinutes = sleepMinutes
        self.sharpestHours = sharpestHours; self.capacityOpening = capacityOpening; self.workLimitMinutes = workLimitMinutes
    }
}

public struct SharpestHours: Sendable, Equatable {
    public var startHour: Int
    public var endHour: Int
    public init(startHour: Int, endHour: Int) {
        self.startHour = startHour; self.endHour = endHour
    }
}

/// One block of the plan. `kind` "recovery" is a 10-min rest after deep work: no task, rest only, not work.
public struct PlanBlock: Sendable, Equatable {
    public var taskId: String?
    public var blockId: String?
    public var start: Date
    public var end: Date
    public var demand: Double
    public var workType: String?
    public var reason: String
    /// focus, meeting or recovery. An older Planner sends none, which reads as focus.
    public var kind: String
    /// Set on the pieces of a task split around recovery zones.
    public var part: PlanPart?
    public init(taskId: String?, blockId: String?, start: Date, end: Date, demand: Double, workType: String? = nil,
                reason: String, kind: String = "focus", part: PlanPart? = nil) {
        self.taskId = taskId; self.blockId = blockId; self.start = start; self.end = end; self.demand = demand
        self.workType = workType; self.reason = reason; self.kind = kind; self.part = part
    }

    public var isRecovery: Bool { kind == "recovery" }

    /// "Recovery" for a rest block, "Title (1/2)" for a piece, else the task's title.
    public func title(taskTitle: String) -> String {
        if isRecovery { return "Recovery" }
        if let part { return "\(taskTitle) (\(part.index)/\(part.of))" }
        return taskTitle
    }
}

public struct PlanPart: Sendable, Equatable {
    public var index: Int
    public var of: Int
    public init(index: Int, of: Int) {
        self.index = index; self.of = of
    }
}

/// A task that didn't fit. `reason` is the Planner's code (noFreeTime, overCapacity, workLimit, dueLater, tooLong,
/// missingEstimate), kept as sent so a new code still reads; `detail` is the sentence to show.
public struct UnplacedTask: Sendable, Equatable {
    public var taskId: String
    public var reason: String
    public var detail: String
    public init(taskId: String, reason: String, detail: String) {
        self.taskId = taskId; self.reason = reason; self.detail = detail
    }
}

/// committedOnRecovery: recovery zones that one of the person's own blocks (a meeting, their own focus) sat on.
/// recoveryMoved: how many of those recoveries moved to free time; the rest were skipped.
public struct PlanWarnings: Sendable, Equatable {
    public var committedOnRecovery: Int
    public var recoveryMoved: Int
    public init(committedOnRecovery: Int = 0, recoveryMoved: Int = 0) {
        self.committedOnRecovery = committedOnRecovery; self.recoveryMoved = recoveryMoved
    }

    /// The short note to show, or nil when nothing sat on recovery time.
    public var note: String? {
        guard committedOnRecovery > 0 else { return nil }
        let blocks = committedOnRecovery == 1 ? "1 of your blocks was" : "\(committedOnRecovery) of your blocks were"
        let moved: String
        if recoveryMoved == 0 {
            moved = "no free time to move the recovery"
        } else if recoveryMoved == committedOnRecovery {
            moved = committedOnRecovery == 1 ? "the recovery moved to free time" : "the recoveries moved to free time"
        } else {
            moved = "\(recoveryMoved) moved to free time"
        }
        return "\(blocks) on recovery time; \(moved)."
    }
}

public struct AlertItem: Identifiable, Sendable, Equatable {
    public var id: String
    public var featureId: String
    public var title: String
    public var detail: String?
    public var date: String
    public var done: Bool
    /// The app action the alert asks for, from `action.ref`, e.g. a mode id.
    public var actionRef: String?
    public init(id: String, featureId: String, title: String, detail: String? = nil, date: String, done: Bool = false, actionRef: String? = nil) {
        self.id = id; self.featureId = featureId; self.title = title; self.detail = detail
        self.date = date; self.done = done; self.actionRef = actionRef
    }
}

/// One day of `GET /v1/metrics/daily`.
public struct DailyMetrics: Sendable, Equatable {
    public var date: String
    public var burnoutIndexScore: Double?
    public var burnoutIndexRisk: String?
    public init(date: String, burnoutIndexScore: Double? = nil, burnoutIndexRisk: String? = nil) {
        self.date = date; self.burnoutIndexScore = burnoutIndexScore; self.burnoutIndexRisk = burnoutIndexRisk
    }
}

// MARK: Search and metrics

/// One of the 25 sections of `GET /v1/metrics/catalogue`.
public struct CatalogueSection: Identifiable, Sendable, Equatable {
    public var id: String
    public var title: String
    public var parameters: [CatalogueParameter]
    public init(id: String, title: String, parameters: [CatalogueParameter]) {
        self.id = id; self.title = title; self.parameters = parameters
    }
}

public struct CatalogueParameter: Identifiable, Sendable, Equatable {
    public var name: String
    /// Nil when nothing measures it.
    public var field: String?
    /// series, labels, profile, route or none.
    public var read: String
    public var unit: String
    public var format: String
    public var id: String { (field ?? "none") + "/" + name }
    /// Only `series` rows can draw the metric page today.
    public var hasSeries: Bool { read == "series" && field != nil }
    public init(name: String, field: String?, read: String, unit: String, format: String = "") {
        self.name = name; self.field = field; self.read = read; self.unit = unit; self.format = format
    }
}

public struct FieldAvailability: Sendable, Equatable {
    public var field: String
    public var latestDate: String
    /// Numbers only; words and yes/no values are shown as text.
    public var latestNumber: Double?
    public var latestText: String?
    public init(field: String, latestDate: String, latestNumber: Double? = nil, latestText: String? = nil) {
        self.field = field; self.latestDate = latestDate; self.latestNumber = latestNumber; self.latestText = latestText
    }
}

public struct MetricPoint: Sendable, Equatable {
    /// yyyy-MM-dd
    public var date: String
    public var value: Double?
    public init(date: String, value: Double?) { self.date = date; self.value = value }
}

public struct MetricSeries: Sendable, Equatable {
    public var field: String
    public var unit: String?
    public var points: [MetricPoint]
    public init(field: String, unit: String?, points: [MetricPoint]) {
        self.field = field; self.unit = unit; self.points = points
    }
}

// MARK: Sessions

public struct StartedSession: Sendable, Equatable {
    public var id: String
    /// True when a session was already running and this answered with it.
    public var joined: Bool
    /// What the backend granted, which a tired-day limit can make shorter than asked. Nil when open-ended.
    public var plannedMinutes: Int?
    /// The running session's title when `joined` is true. Nil when it has none.
    public var title: String?
    /// The running session's kind when `joined` is true: focus, meeting, recovery, walk or brainstorm.
    public var kind: String?
    /// The device the running session was started on when `joined` is true: phone, desktop, web.
    public var device: String?
    /// When the running session ends when `joined` is true. Nil when open-ended.
    public var endsAt: Date?
    public init(id: String, joined: Bool = false, plannedMinutes: Int? = nil, title: String? = nil, kind: String? = nil,
                device: String? = nil, endsAt: Date? = nil) {
        self.id = id; self.joined = joined; self.plannedMinutes = plannedMinutes
        self.title = title; self.kind = kind; self.device = device; self.endsAt = endsAt
    }
}

public enum SessionAction: String, Sendable { case pause, resume, stop, complete }

public struct SessionTrace: Sendable, Equatable {
    /// pending until the day is computed; noData without band data.
    public var status: String
    public var stressDrop: Double?
    public init(status: String, stressDrop: Double? = nil) { self.status = status; self.stressDrop = stressDrop }
}

// MARK: Quick check: POST /v1/checks

/// One Quick check as the phone ran it. The backend works out the measures and whether it counts.
public struct QuickCheckRun: Sendable, Equatable {
    public var id: String
    public var takenAt: Date
    public var durationSeconds: Int
    /// One per dot, in order; an unanswered dot holds its timeout.
    public var reactionTimesMs: [Double]
    /// Taps while no dot showed.
    public var falseStarts: Int
    /// nil when the memory part was skipped.
    public var memory: SpanResult?
    /// "full" with the memory part, else "pvtB".
    public var kind: String { memory == nil ? "pvtB" : "full" }

    public init(id: String = UUID().uuidString, takenAt: Date, durationSeconds: Int, reactionTimesMs: [Double],
                falseStarts: Int, memory: SpanResult? = nil) {
        self.id = id; self.takenAt = takenAt; self.durationSeconds = durationSeconds
        self.reactionTimesMs = reactionTimesMs; self.falseStarts = falseStarts; self.memory = memory
    }
}

/// The memory part's result: rounds answered right out of rounds answered.
public struct SpanResult: Sendable, Equatable {
    public var spanCorrect: Int
    public var spanAttempts: Int
    public init(spanCorrect: Int, spanAttempts: Int) {
        self.spanCorrect = spanCorrect; self.spanAttempts = spanAttempts
    }
}

/// What the backend made of a Quick check.
public struct QuickCheckResult: Sendable, Equatable {
    public var id: String
    public var valid: Bool
    /// The server's code when not valid: not_3_minutes, too_few_responses or too_many_false_starts. The screen turns
    /// it into words.
    public var invalidReason: String?
    public var meanRtMs: Double
    public var medianRtMs: Double
    public var lapses: Int
    public var falseStarts: Int
    /// 0-1, Basner's performance score.
    public var performance: Double
    /// Counts toward your normal (a morning check 30+ min after waking, after 7+ h of sleep).
    public var qualifiesForNormal: Bool
    /// Today's scores after this check.
    public var scores: ScoresToday

    public init(id: String, valid: Bool, invalidReason: String?, meanRtMs: Double, medianRtMs: Double, lapses: Int,
                falseStarts: Int, performance: Double, qualifiesForNormal: Bool, scores: ScoresToday) {
        self.id = id; self.valid = valid; self.invalidReason = invalidReason
        self.meanRtMs = meanRtMs; self.medianRtMs = medianRtMs; self.lapses = lapses; self.falseStarts = falseStarts
        self.performance = performance; self.qualifiesForNormal = qualifiesForNormal; self.scores = scores
    }
}

// MARK: Day zones

/// The four shades behind the calendar. The backend places them; the app only draws them.
public enum ZoneKind: String, Sendable, CaseIterable {
    case heavyWork, lightWork, shortRecovery, heavyRecovery
}

public struct Zone: Identifiable, Sendable, Equatable {
    public var kind: ZoneKind
    public var start: Date
    public var end: Date
    public var id: String { "\(kind.rawValue)-\(start.timeIntervalSince1970)" }
    public init(kind: ZoneKind, start: Date, end: Date) { self.kind = kind; self.start = start; self.end = end }
}

/// Only `ready` days have zones. `unknown` is a status this app version doesn't know yet.
public enum ZoneStatus: String, Sendable {
    case ready, needsSchedule, noScore, dayOff, unknown
}

/// One day from `GET /v1/planner/zones`. The score is the day's morning capacity score, which the Planner reads from
/// Capacity: the app never asks for it on its own.
public struct ZoneDay: Identifiable, Sendable, Equatable {
    /// yyyy-MM-dd (a `DayKey`).
    public var date: String
    public var status: ZoneStatus
    public var score: Int?
    /// peak, focused, steady, shallow or depleted.
    public var level: String?
    public var heavyMinutes: Int
    public var reason: String?
    public var zones: [Zone]
    public var id: String { date }
    public init(date: String, status: ZoneStatus, score: Int? = nil, level: String? = nil, heavyMinutes: Int = 0,
                reason: String? = nil, zones: [Zone] = []) {
        self.date = date; self.status = status; self.score = score; self.level = level
        self.heavyMinutes = heavyMinutes; self.reason = reason; self.zones = zones
    }
}

/// The profile's work hours, which the zones are placed inside. Times are "HH:MM" on the person's own clock.
public struct WorkSchedule: Sendable, Equatable {
    public var workdayStart: String
    public var workdayEnd: String
    public var lunchStart: String
    /// 0 to 240.
    public var lunchMinutes: Int
    /// 1 = Monday ... 7 = Sunday, sorted, no repeats.
    public var workDays: [Int]

    public init(workdayStart: String, workdayEnd: String, lunchStart: String, lunchMinutes: Int, workDays: [Int]) {
        self.workdayStart = workdayStart; self.workdayEnd = workdayEnd; self.lunchStart = lunchStart
        self.lunchMinutes = lunchMinutes; self.workDays = workDays
    }

    /// What the form opens with when nothing is set: the website's default.
    public static let suggested = WorkSchedule(workdayStart: "09:00", workdayEnd: "18:00", lunchStart: "13:00",
                                               lunchMinutes: 60, workDays: [1, 2, 3, 4, 5])

    /// Minutes after midnight for "HH:MM", or nil when it isn't a clock time. Two digits each, as the backend wants.
    public static func minutes(_ clock: String) -> Int? {
        let parts = clock.split(separator: ":", omittingEmptySubsequences: false)
        let twoDigits = parts.allSatisfy { part in part.count == 2 && part.allSatisfy { c in c.isASCII && c.isNumber } }
        guard parts.count == 2, twoDigits, let h = Int(parts[0]), let m = Int(parts[1]), (0..<24).contains(h),
              (0..<60).contains(m)
        else { return nil }
        return h * 60 + m
    }

    /// "HH:MM" for minutes after midnight.
    public static func clock(_ minutes: Int) -> String {
        String(format: "%02d:%02d", minutes / 60, minutes % 60)
    }

    /// What's wrong, in words for the form, or nil when the backend will take it. Same rules and wording as the
    /// website's `scheduleProblem`, plus the backend's 240-minute lunch limit.
    public var problem: String? {
        guard let start = Self.minutes(workdayStart), let end = Self.minutes(workdayEnd),
              let lunch = Self.minutes(lunchStart) else { return "Choose every time." }
        if end <= start { return "Work must end after it starts." }
        if lunchMinutes < 0 { return "Lunch can't have a negative length." }
        if lunchMinutes > 240 { return "Lunch can be at most 4 hours." }
        if lunch < start || lunch + lunchMinutes > end { return "Lunch must fit inside your work hours." }
        if workDays.isEmpty { return "Choose at least one work day." }
        if workDays.contains(where: { !(1...7).contains($0) }) || Set(workDays).count != workDays.count {
            return "Choose each work day once."
        }
        return nil
    }
}

// MARK: Beta day score

/// The answer to "How hard was this for you?" after a focus session. It sets the work's demand, so its cost.
public enum Difficulty: String, Sendable, CaseIterable, Identifiable, Equatable {
    case easy, light, normal, hard, veryHard
    public var id: String { rawValue }

    /// Easy, Light, Normal, Hard, Very hard.
    public var label: String {
        switch self {
        case .easy: return "Easy"
        case .light: return "Light"
        case .normal: return "Normal"
        case .hard: return "Hard"
        case .veryHard: return "Very hard"
        }
    }
}

/// What a recovery break was. Each lifts the day score for a while.
public enum RecoveryType: String, Sendable, CaseIterable, Identifiable, Equatable {
    case walk, nap, rest, outside, social
    public var id: String { rawValue }

    /// Walk, Nap, Rest (no screen), Time outside, Talk with someone.
    public var label: String {
        switch self {
        case .walk: return "Walk"
        case .nap: return "Nap"
        case .rest: return "Rest (no screen)"
        case .outside: return "Time outside"
        case .social: return "Talk with someone"
        }
    }
}

/// One recorded session, as GET /v1/sessions lists it.
public struct SessionRecord: Sendable, Equatable, Identifiable {
    public var id: String
    /// focus, meeting, recovery, walk, brainstorm
    public var kind: String
    public var title: String?
    public var startedAt: Date?
    public var endedAt: Date?
    /// activeMinutes when sent, else minutes, else 0.
    public var minutes: Int
    /// completed, pending, rescheduled, stopped, logged
    public var outcome: String?
    public var difficulty: Difficulty?
    public var recoveryType: RecoveryType?
    /// Points the session cost (positive), when charged.
    public var capacityCost: Double?
    public var isFocus: Bool { kind == "focus" }

    public init(id: String, kind: String, title: String? = nil, startedAt: Date? = nil, endedAt: Date? = nil,
                minutes: Int = 0, outcome: String? = nil, difficulty: Difficulty? = nil,
                recoveryType: RecoveryType? = nil, capacityCost: Double? = nil) {
        self.id = id; self.kind = kind; self.title = title; self.startedAt = startedAt; self.endedAt = endedAt
        self.minutes = minutes; self.outcome = outcome; self.difficulty = difficulty
        self.recoveryType = recoveryType; self.capacityCost = capacityCost
    }
}

public struct LoggedBreak: Sendable, Equatable {
    public var session: SessionRecord
    /// False when the same id had been sent before.
    public var created: Bool
    public init(session: SessionRecord, created: Bool) {
        self.session = session; self.created = created
    }
}

/// One break GET /v1/capacity/recovery-options offers, with the most it lifts the score.
public struct RecoveryOption: Sendable, Equatable, Identifiable {
    /// nap, walk, rest, outside, social
    public var id: String
    public var title: String
    public var minutes: Int
    public var maxPoints: Double
    /// 0 for a give-back that doesn't fade.
    public var fadesAfterMinutes: Int
    public var note: String
    /// The matching type, nil for an id the app doesn't know.
    public var type: RecoveryType? { RecoveryType(rawValue: id) }

    /// "+9, fades within 50 min"; "+10, fades within 2 h" (whole hours as h); one decimal kept only when not whole.
    public var hint: String {
        let points = "+" + RecoveryOption.pointsText(maxPoints)
        if fadesAfterMinutes <= 0 { return "\(points), does not fade" }
        let fades: String
        if fadesAfterMinutes >= 60 && fadesAfterMinutes % 60 == 0 {
            fades = "\(fadesAfterMinutes / 60) h"
        } else {
            fades = "\(fadesAfterMinutes) min"
        }
        return "\(points), fades within \(fades)"
    }

    public init(id: String, title: String, minutes: Int, maxPoints: Double, fadesAfterMinutes: Int, note: String) {
        self.id = id; self.title = title; self.minutes = minutes; self.maxPoints = maxPoints
        self.fadesAfterMinutes = fadesAfterMinutes; self.note = note
    }

    /// "9" for a whole number, else one decimal, "2.5".
    static func pointsText(_ value: Double) -> String {
        guard value.isFinite, abs(value) < 1_000_000 else { return "0" }
        let tenths = (value * 10).rounded() / 10
        if tenths == tenths.rounded() { return String(Int(tenths)) }
        return String(format: "%.1f", tenths)
    }
}

/// One block the Review inbox asks about.
public struct ReviewItem: Sendable, Equatable, Identifiable {
    public var blockId: String
    public var id: String { blockId }
    /// A `DayKey`.
    public var date: String
    public var title: String
    /// focus, meeting, recovery
    public var kind: String
    public var start: Date
    public var end: Date
    public var minutes: Int
    public var isRecovery: Bool { kind == "recovery" }

    public init(blockId: String, date: String, title: String, kind: String, start: Date, end: Date, minutes: Int) {
        self.blockId = blockId; self.date = date; self.title = title; self.kind = kind
        self.start = start; self.end = end; self.minutes = minutes
    }
}

public enum ReviewIntensity: String, Sendable, CaseIterable, Identifiable {
    case light, steady, deep
    public var id: String { rawValue }

    /// Light, Steady, Deep.
    public var label: String {
        switch self {
        case .light: return "Light"
        case .steady: return "Steady"
        case .deep: return "Deep"
        }
    }
}

/// The answer to one inbox item. A recovery block sends `recoveryType`; others send intensity and drain.
public struct ReviewAnswer: Sendable, Equatable {
    public var item: ReviewItem
    public var happened: Bool
    public var intensity: ReviewIntensity?
    /// 1 (gave energy) ... 5 (drained).
    public var drain: Int?
    public var recoveryType: RecoveryType?

    public init(item: ReviewItem, happened: Bool, intensity: ReviewIntensity? = nil, drain: Int? = nil,
                recoveryType: RecoveryType? = nil) {
        self.item = item; self.happened = happened; self.intensity = intensity; self.drain = drain
        self.recoveryType = recoveryType
    }
}

/// The profile's usual sleep times, "HH:mm" each.
public struct SleepSchedule: Sendable, Equatable {
    public var bedtime: String
    public var wakeTime: String
    public init(bedtime: String, wakeTime: String) {
        self.bedtime = bedtime; self.wakeTime = wakeTime
    }
}

/// One Apple Health sample, mapped out of HealthKit by the app (AuraKit doesn't import HealthKit).
public struct SleepSample: Sendable, Equatable {
    public enum Kind: String, Sendable {
        case inBed, asleepCore, asleepDeep, asleepREM, asleepUnspecified, awake

        /// True for the four asleep stages.
        public var isAsleep: Bool {
            switch self {
            case .asleepCore, .asleepDeep, .asleepREM, .asleepUnspecified: return true
            case .inBed, .awake: return false
            }
        }
    }

    public var start: Date
    public var end: Date
    public var kind: Kind
    public init(start: Date, end: Date, kind: Kind) {
        self.start = start; self.end = end; self.kind = kind
    }
}

/// One night for PUT /v1/sleep/nights/{date}.
public struct SleepNight: Sendable, Equatable {
    /// The `DayKey` of the wake, in `timeZone`.
    public var date: String
    /// An IANA id.
    public var timeZone: String
    public var bedtimeAt: Date
    public var wakeAt: Date
    public var totalSleepMin: Int
    public var awakeMin: Int?
    public var inBedMin: Int?

    public init(date: String, timeZone: String, bedtimeAt: Date, wakeAt: Date, totalSleepMin: Int,
                awakeMin: Int? = nil, inBedMin: Int? = nil) {
        self.date = date; self.timeZone = timeZone; self.bedtimeAt = bedtimeAt; self.wakeAt = wakeAt
        self.totalSleepMin = totalSleepMin; self.awakeMin = awakeMin; self.inBedMin = inBedMin
    }
}

public struct SleepNightResult: Sendable, Equatable {
    public var created: Bool
    /// band, appleHealth or checkin: a higher source already there is kept.
    public var source: String
    public init(created: Bool, source: String) {
        self.created = created; self.source = source
    }
}

// MARK: Errors

public enum AuraError: Error, Equatable, Sendable {
    case offline
    case signedOut
    /// Health data consent is missing: the app shows the consent step, then retries.
    case consentRequired
    case server(code: String, message: String, status: Int)

    /// The backend's error code for `.server`, else nil.
    public var serverCode: String? {
        if case .server(let code, _, _) = self { return code }
        return nil
    }
}
