import Foundation

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
}
