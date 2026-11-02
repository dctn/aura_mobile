import Foundation

/// Where a breathing run is. Follows demo_website/src/run/breath.ts.
public struct BreathPhase: Equatable, Sendable {
    public enum Word: String, Sendable { case inhale = "In", hold = "Hold", exhale = "Out" }
    public var word: Word
    /// Whole seconds left in this phase, 1 at the least.
    public var left: Int
    /// The circle: `smallest` (empty) to 1 (full).
    public var scale: Double

    /// Frame 08: the circle eases between 62% and 100%.
    public static let smallest = 0.62

    public static func at(_ seconds: Double, pattern: BreathPattern?) -> BreathPhase {
        let p = pattern ?? .coherent
        // word, length, how full a hold stays
        let all: [(Word, Double, Double)] = [
            (.inhale, p.in, 0), (.hold, p.hold ?? 0, 1), (.exhale, p.out, 0), (.hold, p.hold2 ?? 0, 0),
        ]
        let steps = all.filter { $0.1 > 0 }
        let cycle = steps.reduce(0.0) { $0 + $1.1 }
        guard cycle > 0 else { return BreathPhase(word: .inhale, left: 1, scale: smallest) }
        var t = seconds.truncatingRemainder(dividingBy: cycle)
        if t < 0 { t += cycle }
        var index = 0
        while index < steps.count - 1, t >= steps[index].1 {
            t -= steps[index].1
            index += 1
        }
        let (word, length, held) = steps[index]
        let x: Double
        switch word {
        case .inhale: x = t / length
        case .exhale: x = 1 - t / length
        case .hold: x = held
        }
        let eased = 0.5 - 0.5 * cos(Double.pi * x)
        let left = max(1, Int((length - t - 1e-9).rounded(.up)))
        return BreathPhase(word: word, left: left, scale: smallest + (1 - smallest) * eased)
    }
}

public enum RunPhase: Equatable, Sendable {
    /// The 3-2-1 before a Focus run.
    case countdown(left: Int)
    case running
    case paused
    /// The time ran out (not reached by an open-ended run).
    case timeUp
}

/// What the run screen draws at one moment.
public struct RunSnapshot: Equatable, Sendable {
    public var phase: RunPhase
    public var elapsed: TimeInterval
    /// Nil when the run is open-ended.
    public var remaining: TimeInterval?
    /// 0 to 1; nil when the run is open-ended.
    public var progress: Double?
    /// The countdown's ring, 0 to 1, full at the start of the 3-2-1.
    public var countdownProgress: Double
}

/// A run worked out from the clock, so it stays right while the app is in the background. Times are stored and
/// nothing counts ticks. Codable so the app can restore a run after it's closed.
public struct RunEngine: Codable, Equatable, Sendable {
    public static let countdownSeconds: TimeInterval = 3

    /// The planned length in seconds; 0 is open-ended.
    public var duration: TimeInterval
    public var hasCountdown: Bool
    /// When the clock starts, after any countdown.
    public private(set) var startsAt: Date
    public private(set) var pausedAt: Date?
    public private(set) var pausedTotal: TimeInterval = 0

    public init(minutes: Int, hasCountdown: Bool, now: Date) {
        duration = TimeInterval(max(0, minutes)) * 60
        self.hasCountdown = hasCountdown
        startsAt = hasCountdown ? now.addingTimeInterval(Self.countdownSeconds) : now
    }

    public var isOpenEnded: Bool { duration == 0 }
    public var isPaused: Bool { pausedAt != nil }

    public mutating func pause(at now: Date) {
        guard pausedAt == nil, now >= startsAt else { return }
        pausedAt = now
    }

    public mutating func resume(at now: Date) {
        guard let paused = pausedAt else { return }
        pausedTotal += max(0, now.timeIntervalSince(paused))
        pausedAt = nil
    }

    public func elapsed(at now: Date) -> TimeInterval {
        let end = pausedAt ?? now
        let seconds = end.timeIntervalSince(startsAt) - pausedTotal
        let clamped = max(0, seconds)
        return isOpenEnded ? clamped : min(clamped, duration)
    }

    /// When the time runs out if nothing changes, for the local notification. Nil when open-ended or paused.
    public func endDate() -> Date? {
        guard !isOpenEnded, !isPaused else { return nil }
        return startsAt.addingTimeInterval(duration + pausedTotal)
    }

    public func snapshot(at now: Date) -> RunSnapshot {
        let spent = elapsed(at: now)
        let remaining: TimeInterval? = isOpenEnded ? nil : max(0, duration - spent)
        let progress: Double? = isOpenEnded ? nil : min(1, spent / duration)
        let phase: RunPhase
        var ring = 0.0
        if now < startsAt {
            let left = startsAt.timeIntervalSince(now)
            phase = .countdown(left: Int(left.rounded(.up)))
            ring = left / Self.countdownSeconds
        } else if isPaused {
            phase = .paused
        } else if let remaining, remaining <= 0 {
            phase = .timeUp
        } else {
            phase = .running
        }
        return RunSnapshot(phase: phase, elapsed: spent, remaining: remaining, progress: progress, countdownProgress: ring)
    }

    /// The guided prompt to show: one every (length / count), never past the last.
    public func promptIndex(at now: Date, count: Int) -> Int {
        guard count > 0, duration > 0 else { return 0 }
        return min(count - 1, Int(elapsed(at: now) / (duration / Double(count))))
    }

    /// "09:54" (or "1:02:03" past an hour).
    public static func clock(_ seconds: TimeInterval) -> String {
        let total = Int(seconds.rounded(.up))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }
}
