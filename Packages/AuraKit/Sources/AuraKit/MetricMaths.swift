import Foundation

/// The metric page's three ranges.
public enum MetricRange: Int, CaseIterable, Identifiable, Sendable {
    case twoWeeks = 14
    case oneMonth = 30
    case twoMonths = 60

    public var id: Int { rawValue }
    public var days: Int { rawValue }

    public var title: String {
        switch self {
        case .twoWeeks: return "2 Weeks"
        case .oneMonth: return "1 Month"
        case .twoMonths: return "2 Months"
        }
    }

    public func span(endingOn today: Date, calendar: Calendar = .current) -> MetricDaySpan {
        MetricMaths.daySpan(days: days, endingOn: today, calendar: calendar)
    }
}

/// A run of days as the backend reads them, both ends included.
public struct MetricDaySpan: Equatable, Sendable {
    public var from: String
    public var to: String
    public init(from: String, to: String) { self.from = from; self.to = to }
}

public enum MetricTrendDirection: Equatable, Sendable {
    case up, steady, down
}

/// A category row that has a latest value.
public struct MetricAvailableRow: Identifiable, Equatable, Sendable {
    public var parameter: CatalogueParameter
    public var availability: FieldAvailability
    public var id: String { parameter.id }
    public init(parameter: CatalogueParameter, availability: FieldAvailability) {
        self.parameter = parameter; self.availability = availability
    }
}

/// A category page's two lists, in catalogue order.
public struct MetricCategoryRows: Equatable, Sendable {
    public var available: [MetricAvailableRow]
    public var empty: [CatalogueParameter]
    public init(available: [MetricAvailableRow], empty: [CatalogueParameter]) {
        self.available = available; self.empty = empty
    }
}

/// A metric found by typing in Search, with the section whose icon it shows.
public struct MetricSearchHit: Identifiable, Equatable, Sendable {
    public var sectionId: String
    public var parameter: CatalogueParameter
    public var id: String { parameter.id }
    public init(sectionId: String, parameter: CatalogueParameter) {
        self.sectionId = sectionId; self.parameter = parameter
    }
}

public struct MetricSearchHits: Equatable, Sendable {
    public var categories: [CatalogueSection]
    public var metrics: [MetricSearchHit]
    public init(categories: [CatalogueSection], metrics: [MetricSearchHit]) {
        self.categories = categories; self.metrics = metrics
    }
}

/// The numbers on the metric page and the burnout card, worked out on the phone.
public enum MetricMaths {
    public static func average(_ points: [MetricPoint]) -> Double? {
        let values = points.compactMap(\.value)
        guard !values.isEmpty else { return nil }
        return values.reduce(0, +) / Double(values.count)
    }

    /// The newest point with a value.
    public static func latest(_ points: [MetricPoint]) -> MetricPoint? {
        points.last { $0.value != nil }
    }

    /// The last 7 days against the 7 before, in percent. Nil without data in both weeks or with a zero base.
    public static func weekTrend(_ points: [MetricPoint]) -> Double? {
        guard points.count >= 14 else { return nil }
        let recent = average(Array(points.suffix(7)))
        let before = average(Array(points.dropLast(7).suffix(7)))
        guard let recent, let before, before != 0 else { return nil }
        return (recent - before) / abs(before) * 100
    }

    public static let burnoutDots = 12

    /// How many of the 12 dots to fill for a burnout score of 0-100. Nil score: none.
    public static func burnoutLevel(score: Double?) -> Int {
        guard let score else { return 0 }
        let clamped = min(100, max(0, score))
        return Int((clamped / 100 * Double(burnoutDots)).rounded(.up))
    }

    // MARK: Days

    /// The backend takes at most 400 days.
    public static let availabilityDays = 400
    public static let recordedDays = 365

    public static func daySpan(days: Int, endingOn today: Date, calendar: Calendar = .current) -> MetricDaySpan {
        let first = calendar.date(byAdding: .day, value: 1 - days, to: today) ?? today
        return MetricDaySpan(from: DayKey.string(first, calendar: calendar), to: DayKey.string(today, calendar: calendar))
    }

    /// One point for every day of the span, oldest first. A day the backend left out has no value.
    public static func dailyPoints(_ points: [MetricPoint], span: MetricDaySpan, calendar: Calendar = .current) -> [MetricPoint] {
        guard let first = DayKey.date(span.from, calendar: calendar),
              let last = DayKey.date(span.to, calendar: calendar) else { return points }
        var values: [String: Double] = [:]
        for point in points {
            if let value = point.value { values[String(point.date.prefix(10))] = value }
        }
        var filled: [MetricPoint] = []
        var day = first
        while day <= last {
            let key = DayKey.string(day, calendar: calendar)
            filled.append(MetricPoint(date: key, value: values[key]))
            guard let next = calendar.date(byAdding: .day, value: 1, to: day) else { break }
            day = next
        }
        return filled
    }

    /// The points that have a value, newest first.
    public static func recordedRows(_ points: [MetricPoint]) -> [MetricPoint] {
        points.filter { $0.value != nil }.sorted { $0.date > $1.date }
    }

    // MARK: Chart

    /// Each bar's height as a share of the chart, the highest value filling it. A day with no value stays nil, a gap.
    public static func barFractions(_ values: [Double?]) -> [Double?] {
        let known = values.compactMap { $0 }
        guard let top = known.max(), let low = known.min() else {
            return values.map { (_: Double?) -> Double? in nil }
        }
        let base = min(0, low)
        let height = top - base
        return values.map { (value: Double?) -> Double? in
            guard let value else { return nil }
            guard height > 0 else { return 0 }
            return (value - base) / height
        }
    }

    // MARK: Trend words

    /// A change smaller than this reads as steady.
    public static let steadyPercent = 2.0

    public static func trendDirection(_ percent: Double) -> MetricTrendDirection {
        if abs(percent) < steadyPercent { return .steady }
        return percent > 0 ? .up : .down
    }

    public static func trendTitle(_ percent: Double) -> String {
        switch trendDirection(percent) {
        case .up: return "Trending Up"
        case .steady: return "Steady"
        case .down: return "Trending Down"
        }
    }

    public static func trendPercentText(_ percent: Double) -> String {
        let whole = Int(percent.rounded())
        return whole < 0 ? "\(whole)%" : "+\(whole)%"
    }

    // MARK: Showing values

    /// Averages and trends mean nothing for words and yes/no values.
    public static func canAverage(format: String) -> Bool {
        format != "bool" && format != "text"
    }

    /// A stored value as the catalogue's `format` prints it. With no format: whole for counts and calories, else one decimal.
    public static func display(_ value: Double, format: String, unit: String = "") -> String {
        switch format {
        case "int", "minutes", "percent":
            return whole(value)
        case "decimal1":
            return fixed(value, digits: 1)
        case "decimal2":
            return fixed(value, digits: 2)
        case "hoursFromMinutes":
            return fixed(value / 60, digits: 1)
        case "metresAsKm":
            return fixed(value / 1000, digits: 1)
        case "hhmm":
            return minutesAndSeconds(value)
        case "hourOfDay":
            return clockTime(value)
        case "bool":
            return value != 0 ? "Yes" : "No"
        default:
            return wholeUnits.contains(unit.lowercased()) ? whole(value) : fixed(value, digits: 1)
        }
    }

    /// The unit printed beside a value: words and yes/no values have none.
    public static func shownUnit(format: String, unit: String) -> String {
        canAverage(format: format) ? unit : ""
    }

    /// A field's latest value as text. Nil when it has none.
    public static func latestText(_ availability: FieldAvailability, format: String, unit: String) -> String? {
        if let number = availability.latestNumber {
            return display(number, format: format, unit: unit)
        }
        return availability.latestText
    }

    /// "Nov 1" from "2026-11-01".
    public static func shortDay(_ key: String) -> String {
        let parts = key.prefix(10).split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, parts[1] >= 1, parts[1] <= 12 else { return key }
        return "\(monthNames[parts[1] - 1]) \(parts[2])"
    }

    private static let wholeUnits: Set<String> = ["steps", "kcal", "cal", "min"]
    private static let monthNames = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

    private static func fixed(_ value: Double, digits: Int) -> String {
        let text = String(format: "%.\(digits)f", value)
        // -0.0 reads as 0.0.
        let isZero = text.allSatisfy { $0 == "-" || $0 == "0" || $0 == "." }
        return isZero && text.hasPrefix("-") ? String(text.dropFirst()) : text
    }

    private static func whole(_ value: Double) -> String {
        guard value.isFinite, abs(value) < 1e15 else { return fixed(value, digits: 0) }
        let number = Int(value.rounded())
        let digits = String(abs(number))
        var grouped = ""
        for (index, digit) in digits.enumerated() {
            if index > 0, (digits.count - index) % 3 == 0 { grouped.append(",") }
            grouped.append(digit)
        }
        return number < 0 ? "-" + grouped : grouped
    }

    private static func minutesAndSeconds(_ seconds: Double) -> String {
        guard seconds.isFinite, abs(seconds) < 1e15 else { return "" }
        let total = Int(seconds.rounded())
        return String(format: "%d:%02d", total / 60, abs(total % 60))
    }

    private static func clockTime(_ hour: Double) -> String {
        guard hour.isFinite, abs(hour) < 1e12 else { return "" }
        let minutes = Int((hour * 60).rounded())
        return String(format: "%d:%02d", minutes / 60, abs(minutes % 60))
    }

    // MARK: Lists

    /// True when the text holds what was typed. Nothing typed matches everything.
    public static func matches(_ query: String, _ text: String) -> Bool {
        let wanted = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return wanted.isEmpty || text.localizedCaseInsensitiveContains(wanted)
    }

    /// Splits a category into rows with a latest value and the rest.
    public static func categoryRows(_ parameters: [CatalogueParameter], availability: [FieldAvailability]) -> MetricCategoryRows {
        var byField: [String: FieldAvailability] = [:]
        for item in availability where item.latestNumber != nil || item.latestText != nil {
            byField[item.field] = item
        }
        var rows = MetricCategoryRows(available: [], empty: [])
        for parameter in parameters {
            if let field = parameter.field, let item = byField[field] {
                rows.available.append(MetricAvailableRow(parameter: parameter, availability: item))
            } else {
                rows.empty.append(parameter)
            }
        }
        return rows
    }

    /// What typing finds. A metric listed in two sections shows once.
    public static func search(_ query: String, in sections: [CatalogueSection]) -> MetricSearchHits {
        let categories = sections.filter { matches(query, $0.title) }
        var seen = Set<String>()
        var metrics: [MetricSearchHit] = []
        for section in sections {
            for parameter in section.parameters where matches(query, parameter.name) {
                if seen.insert(parameter.id).inserted {
                    metrics.append(MetricSearchHit(sectionId: section.id, parameter: parameter))
                }
            }
        }
        return MetricSearchHits(categories: categories, metrics: metrics)
    }
}
