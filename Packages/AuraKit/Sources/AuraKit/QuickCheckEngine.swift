import Foundation

/// The 3-minute reaction test (PVT-B, Basner 2011) as a state machine.
/// It reads no clock: the screen passes in the time in seconds (e.g. from `CACurrentMediaTime()`), so tests can
/// script every moment. The backend scores the raw times; this only runs the test.
public struct ReactionTest: Sendable {
    public struct Config: Sendable, Equatable {
        /// The whole run, 180 s.
        public var duration: TimeInterval
        /// From one answer (or the start) to the next dot, at the least: 1 s.
        public var minGap: TimeInterval
        /// And at the most: 4 s.
        public var maxGap: TimeInterval
        /// How long the reaction time (or "Too soon") shows, inside the gap: 1 s.
        public var showResultFor: TimeInterval
        /// A dot not tapped within this records timeout*1000 ms: 10 s.
        public var timeout: TimeInterval

        public static let standard = Config(duration: 180, minGap: 1, maxGap: 4, showResultFor: 1, timeout: 10)

        /// The same rules over `seconds` (UI tests).
        public static func short(seconds: TimeInterval) -> Config {
            var config = Config.standard
            config.duration = seconds
            return config
        }

        public init(duration: TimeInterval, minGap: TimeInterval, maxGap: TimeInterval,
                    showResultFor: TimeInterval, timeout: TimeInterval) {
            self.duration = duration
            self.minGap = minGap
            self.maxGap = maxGap
            self.showResultFor = showResultFor
            self.timeout = timeout
        }
    }

    public enum Phase: Sendable, Equatable {
        case notStarted
        /// Dark card, no dot yet.
        case waiting
        /// Green "Tap!".
        case dot
        /// The reaction just made, shown for showResultFor (card dark again).
        case result(ms: Double)
        /// A tap with no dot, shown for showResultFor.
        case tooSoon
        case finished
    }

    public enum TapOutcome: Sendable, Equatable { case reaction(ms: Double), falseStart, ignored }

    public let config: Config
    private let random: @Sendable () -> Double

    public private(set) var phase: Phase = .notStarted
    /// One per dot, in order; an unanswered dot holds timeout*1000.
    public private(set) var reactionTimesMs: [Double] = []
    /// Taps while no dot showed.
    public private(set) var falseStarts: Int = 0

    private var startedAt: TimeInterval = 0
    /// The newest time the screen passed in.
    private var latest: TimeInterval = 0
    private var nextDotAt: TimeInterval = 0
    private var dotShownAt: TimeInterval = 0
    /// When `.result` / `.tooSoon` ends.
    private var messageUntil: TimeInterval = 0

    /// `random` gives a number in 0..<1 for each gap; tests pass a script.
    public init(config: Config = .standard, random: @escaping @Sendable () -> Double = { Double.random(in: 0..<1) }) {
        self.config = config
        self.random = random
    }

    /// The newest reactions first, at most 3 (the row under the card). Timeouts are in it too.
    public var recent: [Double] {
        Array(reactionTimesMs.suffix(3).reversed())
    }

    public var isFinished: Bool { phase == .finished }

    /// Whole seconds from start to the end of the run (the configured duration once finished).
    /// While running it is the time up to the newest tick or tap.
    public var durationSeconds: Int {
        switch phase {
        case .notStarted:
            return 0
        case .finished:
            return Int(max(0, config.duration).rounded())
        case .waiting, .dot, .result, .tooSoon:
            return Int(max(0, latest - startedAt))
        }
    }

    /// Starts the run: the first dot comes after one gap. Does nothing once started; make a new test to run again.
    public mutating func start(at t: TimeInterval) {
        guard phase == .notStarted else { return }
        startedAt = t
        latest = t
        nextDotAt = t + drawGap()
        phase = config.duration > 0 ? .waiting : .finished
    }

    /// Moves time on: shows the dot when its gap is over, records a timeout, clears result/tooSoon, ends the run.
    public mutating func tick(at t: TimeInterval) {
        guard isRunning else { return }
        latest = max(latest, t)
        if t >= endsAt {
            // A dot still showing is dropped, not recorded.
            phase = .finished
            return
        }
        // A late tick can pass several moments at once; they are handled in order.
        var changed = true
        var steps = 0
        while changed && steps < 10_000 {
            changed = false
            steps += 1
            switch phase {
            case .notStarted, .finished:
                return
            case .waiting:
                if t >= nextDotAt {
                    // The dot counts from the tick that put it on screen.
                    dotShownAt = t
                    phase = .dot
                    changed = true
                }
            case .dot:
                let timesOutAt = dotShownAt + config.timeout
                if t >= timesOutAt {
                    reactionTimesMs.append(config.timeout * 1000)
                    nextDotAt = timesOutAt + drawGap()
                    phase = .waiting
                    changed = true
                }
            case .result, .tooSoon:
                if t >= messageUntil {
                    // Back to waiting; the loop shows the dot at once if it is due.
                    phase = .waiting
                    changed = true
                }
            }
        }
    }

    /// A tap on the card. On the dot it records the reaction; with no dot on screen it is a false start
    /// (a dot that is due but not yet shown by a tick has not been seen, so it is a false start too).
    @discardableResult
    public mutating func tap(at t: TimeInterval) -> TapOutcome {
        guard isRunning else { return .ignored }
        latest = max(latest, t)
        if t >= endsAt {
            phase = .finished
            return .ignored
        }
        if case .dot = phase {
            if t - dotShownAt < config.timeout {
                let ms = max(0, t - dotShownAt) * 1000
                reactionTimesMs.append(ms)
                phase = .result(ms: ms)
                messageUntil = t + config.showResultFor
                nextDotAt = t + drawGap()
                return .reaction(ms: ms)
            }
            // The dot ran out before this tap (no tick came in time): it counts as unanswered.
            reactionTimesMs.append(config.timeout * 1000)
        }
        falseStarts += 1
        phase = .tooSoon
        messageUntil = t + config.showResultFor
        nextDotAt = t + drawGap()
        return .falseStart
    }

    /// Time left in the run, never below 0. The full duration before the start.
    public func remaining(at t: TimeInterval) -> TimeInterval {
        switch phase {
        case .notStarted:
            return max(0, config.duration)
        case .finished:
            return 0
        case .waiting, .dot, .result, .tooSoon:
            return max(0, endsAt - t)
        }
    }

    private var isRunning: Bool { phase != .notStarted && phase != .finished }

    private var endsAt: TimeInterval { startedAt + config.duration }

    /// minGap + random()*(maxGap-minGap), with the random number held to 0...1.
    private func drawGap() -> TimeInterval {
        let raw = random()
        let r = raw.isFinite ? min(max(raw, 0), 1) : 0
        let low = max(0, config.minGap)
        let high = max(low, config.maxGap)
        return low + r * (high - low)
    }
}

/// The memory part: digits show, then are typed back; the row grows by one after each right answer.
public struct SpanTest: Sendable {
    public struct Config: Sendable, Equatable {
        /// The first row's length: 3.
        public var startLength: Int
        /// A right answer at this length ends the test: 9.
        public var maxLength: Int
        /// The screen shows the row for length * showPerDigit seconds: 1.
        public var showPerDigit: TimeInterval

        public static let standard = Config(startLength: 3, maxLength: 9, showPerDigit: 1)
        /// One round of 3 digits (UI tests).
        public static let short = Config(startLength: 3, maxLength: 3, showPerDigit: 1)

        public init(startLength: Int, maxLength: Int, showPerDigit: TimeInterval) {
            self.startLength = startLength
            self.maxLength = maxLength
            self.showPerDigit = showPerDigit
        }
    }

    public enum Phase: Sendable, Equatable { case showing, entering, finished }

    public let config: Config
    private let nextDigit: @Sendable () -> Int

    /// `.showing` with no digits until `begin()`.
    public private(set) var phase: Phase = .showing
    /// The row of the current round.
    public private(set) var digits: [Int] = []
    public private(set) var entered: [Int] = []
    /// Rounds answered right.
    public private(set) var spanCorrect: Int = 0
    /// Rounds answered.
    public private(set) var spanAttempts: Int = 0
    /// Longest row answered right, 0 if none.
    public private(set) var bestSpan: Int = 0

    private var length: Int
    private var wrongAtLength: Int = 0
    private var begun = false

    /// `digit` gives a digit 0-9 for each place in a row; tests pass a script.
    public init(config: Config = .standard, digit: @escaping @Sendable () -> Int = { Int.random(in: 0...9) }) {
        self.config = config
        self.nextDigit = digit
        self.length = max(1, config.startLength)
    }

    /// How long the screen shows the current row.
    public var showDuration: TimeInterval { Double(digits.count) * config.showPerDigit }

    public var isFinished: Bool { phase == .finished }

    public var result: SpanResult {
        SpanResult(spanCorrect: spanCorrect, spanAttempts: spanAttempts)
    }

    /// Starts the first round (phase .showing with new digits). Called once; later calls do nothing.
    public mutating func begin() {
        guard !begun else { return }
        begun = true
        newRound()
    }

    /// The row has been shown long enough: phase .entering.
    public mutating func hideDigits() {
        guard phase == .showing, !digits.isEmpty else { return }
        phase = .entering
    }

    /// A keypad digit. When the row is full it is checked: returns true/false then, nil before.
    /// Ignored (nil) outside `.entering` or for a number outside 0-9.
    @discardableResult
    public mutating func type(_ digit: Int) -> Bool? {
        guard phase == .entering, (0...9).contains(digit), entered.count < digits.count else { return nil }
        entered.append(digit)
        guard entered.count == digits.count else { return nil }
        spanAttempts += 1
        let right = entered == digits
        if right {
            spanCorrect += 1
            bestSpan = max(bestSpan, digits.count)
            if digits.count >= longest {
                phase = .finished
            } else {
                length += 1
                wrongAtLength = 0
                newRound()
            }
        } else {
            wrongAtLength += 1
            if wrongAtLength >= 2 {
                phase = .finished
            } else {
                newRound()
            }
        }
        return right
    }

    public mutating func deleteLast() {
        guard phase == .entering, !entered.isEmpty else { return }
        entered.removeLast()
    }

    /// The longest row the test asks for, never shorter than the first.
    private var longest: Int { max(max(1, config.startLength), config.maxLength) }

    private mutating func newRound() {
        digits = drawRow(length)
        entered = []
        phase = .showing
    }

    /// A row with no digit next to the same digit (a repeat is re-drawn).
    private func drawRow(_ count: Int) -> [Int] {
        var row: [Int] = []
        row.reserveCapacity(count)
        while row.count < count {
            var next = Self.oneDigit(nextDigit())
            var tries = 0
            while let last = row.last, next == last, tries < 20 {
                next = Self.oneDigit(nextDigit())
                tries += 1
            }
            // A source that keeps giving the same digit can't stall the row.
            if let last = row.last, next == last {
                next = (last + 1) % 10
            }
            row.append(next)
        }
        return row
    }

    private static func oneDigit(_ value: Int) -> Int {
        ((value % 10) + 10) % 10
    }
}
