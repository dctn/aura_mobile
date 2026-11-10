import Foundation

/// Last night from Apple Health's sleep samples, for PUT /v1/sleep/nights/{date}. Several sources (phone, watch)
/// can write the same night, so overlapping samples are merged before anything is counted.
public enum SleepNightBuilder {
    /// A gap this long between asleep samples starts another sleep (a nap, or the night before).
    static let clusterGap: TimeInterval = 3 * 60 * 60
    /// The longest night the route takes, bedtime to wake.
    static let maxSpan: TimeInterval = 16 * 60 * 60

    /// A stretch of time, start before end.
    struct Span: Equatable {
        let start: Date
        let end: Date

        var seconds: TimeInterval { end.timeIntervalSince(start) }
    }

    /// Builds last night from Apple Health samples, or nil when there is no asleep sample (inBed alone is no night).
    /// The night is the latest run of asleep samples with no gap of 3 h or more. Awake and in-bed time count only
    /// inside it; in-bed time below the sleep counted is left out, as the route refuses it.
    public static func build(samples: [SleepSample], timeZone: TimeZone) -> SleepNight? {
        let asleep = merge(samples.filter { $0.kind.isAsleep }.map { Span(start: $0.start, end: $0.end) })
        guard let night = latestCluster(asleep), let first = night.first, let last = night.last else { return nil }
        let bedtime = first.start
        let wake = last.end
        guard wake.timeIntervalSince(bedtime) <= maxSpan else { return nil }
        let window = Span(start: bedtime, end: wake)

        let asleepSeconds: TimeInterval = night.reduce(TimeInterval(0)) { $0 + $1.seconds }
        let totalSleepMin = minutes(asleepSeconds)
        let awake = merge(samples.filter { $0.kind == .awake }.map { Span(start: $0.start, end: $0.end) })
        let awakeMin = minutes(seconds(of: awake, inside: window))
        let inBed = merge(samples.filter { $0.kind == .inBed }.map { Span(start: $0.start, end: $0.end) })
        let inBedMin = minutes(seconds(of: inBed, inside: window))

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        calendar.locale = Locale(identifier: "en_US_POSIX")
        return SleepNight(date: DayKey.string(wake, calendar: calendar), timeZone: timeZone.identifier,
                          bedtimeAt: bedtime, wakeAt: wake, totalSleepMin: totalSleepMin,
                          awakeMin: awakeMin > 0 ? awakeMin : nil,
                          inBedMin: inBedMin > 0 && inBedMin >= totalSleepMin ? inBedMin : nil)
    }

    /// Sorted by start, with overlapping or touching spans joined. Empty or backwards samples are dropped.
    static func merge(_ spans: [Span]) -> [Span] {
        let sorted = spans.filter { $0.end > $0.start }.sorted { $0.start < $1.start }
        var merged: [Span] = []
        for span in sorted {
            if let previous = merged.last, span.start <= previous.end {
                merged[merged.count - 1] = Span(start: previous.start, end: max(previous.end, span.end))
            } else {
                merged.append(span)
            }
        }
        return merged
    }

    /// The last run of merged spans with every gap under `clusterGap`, or nil when there are none.
    static func latestCluster(_ merged: [Span]) -> [Span]? {
        guard !merged.isEmpty else { return nil }
        var cluster: [Span] = []
        for span in merged {
            if let previous = cluster.last, span.start.timeIntervalSince(previous.end) >= clusterGap {
                cluster = []
            }
            cluster.append(span)
        }
        return cluster
    }

    /// How much of the merged spans falls inside the window.
    static func seconds(of merged: [Span], inside window: Span) -> TimeInterval {
        var total: TimeInterval = 0
        for span in merged {
            let start = max(span.start, window.start)
            let end = min(span.end, window.end)
            if end > start {
                total += end.timeIntervalSince(start)
            }
        }
        return total
    }

    /// Whole minutes, rounded.
    static func minutes(_ seconds: TimeInterval) -> Int {
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return Int((seconds / 60).rounded())
    }
}
