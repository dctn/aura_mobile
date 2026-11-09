import AuraKit
import Foundation
import Observation
import QuartzCore

/// Runs one Quick check: the intro, the reaction test, the memory part, then sending it. The screen only draws it.
/// Times come from `CACurrentMediaTime()`, a clock that never jumps when the phone's time is changed.
@MainActor
@Observable
final class QuickCheckFlow {
    enum Step: Equatable {
        case intro
        case reaction
        case memory
        case sending
        case result
    }

    private(set) var step: Step = .intro

    // What the reaction card shows. Copied out of the test only when it changes, so the screen redraws a few
    // times a second and not on every tick.
    private(set) var reactionPhase: ReactionTest.Phase = .notStarted
    /// The newest reactions first, at most 3.
    private(set) var recentTimes: [Double] = []
    private(set) var secondsLeft: Int = 0

    private(set) var span: SpanTest
    /// False for the second before the first row shows.
    private(set) var memoryBegun = false
    /// Right or wrong for the row just typed, shown briefly; nil the rest of the time.
    private(set) var feedback: Bool? = nil
    /// The row as it was typed, shown with the feedback (the test has already moved on to the next row).
    private(set) var feedbackRow: [Int] = []

    /// True after the app was left during the reaction test; the intro then says why it starts again.
    private(set) var interrupted = false
    private(set) var sendFailed = false
    private(set) var result: QuickCheckResult? = nil
    /// True when the memory part was done rather than skipped.
    private(set) var memoryDone = false

    let reactionConfig: ReactionTest.Config
    let spanConfig: SpanTest.Config
    /// How often the reaction test moves on. The dot counts from the tick that shows it, so the live app ticks at
    /// 120 Hz. UI tests tick slowly so the test runner sees an idle app.
    private let tickInterval: Duration
    private let api: any AuraAPI
    private let onError: (Error) -> Void

    @ObservationIgnored private var reaction: ReactionTest
    @ObservationIgnored private var startedAt = Date()
    /// Built once, so "Try again" resends the same id.
    @ObservationIgnored private var run: QuickCheckRun? = nil
    @ObservationIgnored private var ticker: Task<Void, Never>? = nil
    @ObservationIgnored private var readyTask: Task<Void, Never>? = nil
    @ObservationIgnored private var hideTask: Task<Void, Never>? = nil
    @ObservationIgnored private var feedbackTask: Task<Void, Never>? = nil
    /// Counts the rows shown, so a late hide never hides the next row early.
    @ObservationIgnored private var round = 0
    @ObservationIgnored private var stopped = false

    init(api: any AuraAPI, short: Bool, uiTesting: Bool, onError: @escaping (Error) -> Void) {
        self.api = api
        self.onError = onError
        let reactionConfig: ReactionTest.Config = short ? ReactionTest.Config.short(seconds: 8) : ReactionTest.Config.standard
        let spanConfig: SpanTest.Config = short ? SpanTest.Config.short : SpanTest.Config.standard
        self.reactionConfig = reactionConfig
        self.spanConfig = spanConfig
        self.tickInterval = uiTesting ? .milliseconds(100) : .milliseconds(8)
        self.reaction = ReactionTest(config: reactionConfig)
        self.span = SpanTest(config: spanConfig)
        self.secondsLeft = Int(reactionConfig.duration.rounded(.up))
    }

    /// True while a part of the check is running, so leaving asks first.
    var isRunning: Bool {
        step == .reaction || step == .memory
    }

    // MARK: Reaction

    func startReaction() {
        guard step == .intro, !stopped else { return }
        reaction = ReactionTest(config: reactionConfig)
        startedAt = Date()
        let now = CACurrentMediaTime()
        reaction.start(at: now)
        step = .reaction
        publishReaction(at: now)
        startTicker()
    }

    /// A touch-down on the card, timed when the touch came in.
    func tap(at time: TimeInterval) {
        guard step == .reaction else { return }
        reaction.tap(at: time)
        publishReaction(at: time)
        if reaction.isFinished { finishReaction() }
    }

    private func startTicker() {
        ticker?.cancel()
        let interval = tickInterval
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.step == .reaction else { return }
                self.tick()
                try? await Task.sleep(for: interval)
            }
        }
    }

    private func tick() {
        let now = CACurrentMediaTime()
        reaction.tick(at: now)
        publishReaction(at: now)
        if reaction.isFinished { finishReaction() }
    }

    private func publishReaction(at now: TimeInterval) {
        let phase = reaction.phase
        if phase != reactionPhase { reactionPhase = phase }
        let recent = reaction.recent
        if recent != recentTimes { recentTimes = recent }
        let left = Int(reaction.remaining(at: now).rounded(.up))
        if left != secondsLeft { secondsLeft = left }
    }

    private func finishReaction() {
        guard step == .reaction else { return }
        ticker?.cancel()
        ticker = nil
        step = .memory
        readyTask = Task { [weak self] in
            // A moment to look away from the card before the first row.
            try? await Task.sleep(for: .seconds(1))
            guard let self, !Task.isCancelled, self.step == .memory else { return }
            self.span.begin()
            self.memoryBegun = true
            self.scheduleHide()
        }
    }

    // MARK: Memory

    /// Hides the row after `length * showPerDigit` seconds.
    private func scheduleHide() {
        round += 1
        let thisRound = round
        let seconds = span.showDuration
        let milliseconds = Int((seconds * 1000).rounded())
        hideTask?.cancel()
        hideTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(milliseconds))
            guard let self, !Task.isCancelled, self.step == .memory, self.round == thisRound else { return }
            self.span.hideDigits()
        }
    }

    func type(_ digit: Int) {
        guard step == .memory, feedback == nil, span.phase == .entering else { return }
        let row = span.entered + [digit]
        guard let right = span.type(digit) else { return }
        feedbackRow = row
        feedback = right
        feedbackTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(800))
            guard let self, !Task.isCancelled, self.step == .memory else { return }
            self.feedback = nil
            self.afterRow()
        }
    }

    func deleteLast() {
        guard step == .memory, feedback == nil else { return }
        span.deleteLast()
    }

    private func afterRow() {
        switch span.phase {
        case .finished:
            send(memory: span.result)
        case .showing:
            scheduleHide()
        case .entering:
            break
        }
    }

    /// Sends the check as reaction only.
    func skipMemory() {
        guard step == .memory else { return }
        send(memory: nil)
    }

    // MARK: Sending

    private func send(memory answered: SpanResult?) {
        cancelMemoryTasks()
        // The backend needs at least one answered row; with none the check goes as reaction only.
        let memory: SpanResult? = (answered?.spanAttempts ?? 0) > 0 ? answered : nil
        memoryDone = memory != nil
        run = QuickCheckRun(takenAt: startedAt,
                            durationSeconds: reaction.durationSeconds,
                            reactionTimesMs: reaction.reactionTimesMs,
                            falseStarts: reaction.falseStarts,
                            memory: memory)
        submit()
    }

    /// Sends the run built once; resending the same id is safe.
    func submit() {
        guard let run, !stopped else { return }
        let api = self.api
        sendFailed = false
        step = .sending
        Task { [weak self] in
            do {
                let answer = try await api.submitCheck(run)
                guard let self, !self.stopped else { return }
                self.result = answer
                self.step = .result
            } catch {
                guard let self, !self.stopped else { return }
                self.onError(error)
                self.sendFailed = true
            }
        }
    }

    /// The app was left: the reaction test's timing no longer holds, so it starts again from the intro. The memory
    /// part isn't timed and carries on.
    func interrupt() {
        guard step == .reaction, !stopped else { return }
        ticker?.cancel()
        ticker = nil
        reaction = ReactionTest(config: reactionConfig)
        reactionPhase = .notStarted
        recentTimes = []
        secondsLeft = Int(reactionConfig.duration.rounded(.up))
        interrupted = true
        step = .intro
    }

    // MARK: Stopping

    /// Stops every timer; nothing more is sent. Safe to call more than once.
    func stop() {
        stopped = true
        ticker?.cancel()
        ticker = nil
        cancelMemoryTasks()
    }

    private func cancelMemoryTasks() {
        readyTask?.cancel()
        readyTask = nil
        hideTask?.cancel()
        hideTask = nil
        feedbackTask?.cancel()
        feedbackTask = nil
    }
}
