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

    var model: CalendarEvent? {
        guard let startDate = WireDate.parse(start), let endDate = WireDate.parse(end) else { return nil }
        return CalendarEvent(id: id, title: title ?? "", start: startDate, end: endDate, kind: kind ?? "focus")
    }
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

    var model: TaskItem {
        // A due date without a time only names the day, so it isn't the task's time.
        let due: Date? = hasTime == true ? WireDate.parse(dueAt) : nil
        let done = status == "completed" || completedAt != nil
        return TaskItem(id: id, title: title ?? "", at: WireDate.parse(scheduledAt) ?? due, done: done)
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
    }

    let session: Session
    let joined: Bool?
}

struct WireTrace: Decodable {
    let status: String?
    let stressDrop: Double?
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

// MARK: Profile: GET /v1/me, PUT /v1/me/profile

struct WireMe: Decodable {
    let timezone: String?
    /// Null until the person sets it. One the app can't read counts as none, so the rest of the profile still reads.
    let workSchedule: WireLossy<WireWorkSchedule>?
    /// Not optional: a profile write has to name the version it read.
    let version: Int
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
