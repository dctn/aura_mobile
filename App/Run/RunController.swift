import AudioToolbox
import AuraKit
import Foundation
import Observation
import UIKit
import UserNotifications

/// The run in progress. Lives in AppModel so it survives switching tabs, and in UserDefaults so it survives a restart.
struct ActiveRun: Codable, Equatable {
    var mode: Mode
    var minutes: Int
    var title: String
    var engine: RunEngine
    /// Sent as the new session's id. A repeated id returns the stored session, so a start can be tried again.
    var startId = UUID().uuidString.lowercased()
    /// The id the backend answered with, which isn't `startId` when the start joined a running session.
    var sessionId: String?
    /// True once the session has been asked for, after any 3-2-1.
    var sessionBegun = false
    /// The backend refused the start because today's work limit is used up. Not tried again.
    var workLimitReached = false
    /// Set when the run is over: time up or Mark as done. The Done sheet shows from here.
    var finishedAt: Date?
    /// Wind-down's "For tomorrow" text, one thing a line.
    var tomorrowList = ""
    /// True once the final `.complete` reached the backend, or the backend had already ended the session.
    var completeDelivered = false
    /// Wind-down: the lines still to go out as tasks. Nil until the run ends.
    var tomorrowLeft: [String]?
    /// The session that was already running when this run asked for one, maybe on another device. Set until the
    /// person picks in the alert; meanwhile no action goes to either session.
    var otherSession: OtherSession?
    /// The recovery break's type ("walk", "nap", ...) when the person picked one, sent instead of the mode's own.
    /// Kept as text so a saved run still decodes if the types change.
    var recoveryTypeRaw: String?

    /// The type a recovery session sends: the one picked, else the mode's. Nil for every other kind.
    var recoveryType: RecoveryType? {
        guard mode.kind == "recovery" else { return nil }
        if let raw = recoveryTypeRaw, let picked = RecoveryType(rawValue: raw) { return picked }
        return mode.recoveryType
    }

    /// The Done sheet only says "Saved" when this is true.
    var isSaved: Bool {
        return completeDelivered && (tomorrowLeft ?? []).isEmpty
    }

    var tomorrowLines: [String] {
        guard mode.run == .winddown else { return [] }
        return tomorrowList
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }
}

/// What the backend said about the session it joined instead of starting one.
struct OtherSession: Codable, Equatable {
    var id: String
    var title: String?
    var kind: String?
    var device: String?
    var endsAt: Date?
}

/// Drives every run from `RunEngine`, which works from the clock. Nothing counts: one task sleeps until the next thing
/// that has to happen (the 3-2-1 ending, the time running out), and screens draw from `engine.snapshot(at:)`.
@MainActor
@Observable
final class RunController {
    private let api: any AuraAPI
    private let defaults: UserDefaults
    /// UI tests: no restore, no saving, and no notifications, whose permission prompt would cover the screen.
    private let isUITesting: Bool
    /// `--uitesting-short-run`: every timed run lasts 3 seconds, so the walk can reach the Done sheet.
    private let isShortRun: Bool

    private(set) var active: ActiveRun? = nil
    /// True while the full run screen shows; false leaves it in the bar above the tabs.
    var isExpanded = false
    /// Every failed session call lands here, and the run carries on. The app passes these to `AppModel.note`.
    var onError: (@MainActor (Error) -> Void)? = nil
    private(set) var lastError: Error? = nil

    @ObservationIgnored private var wake: Task<Void, Never>? = nil
    @ObservationIgnored private var sessionStart: Task<String?, Never>? = nil
    /// The last thing sent. Each one waits for the one before, so they reach the backend in order. Outlives `close()`,
    /// so a new run's start can't overtake the old run's stop and join the session that stop is about to end.
    @ObservationIgnored private var lastAction: Task<Void, Never>? = nil
    // What's in flight, by the run's `engine.startsAt`, so a retry doesn't send it twice.
    @ObservationIgnored private var startingFor: Date? = nil
    @ObservationIgnored private var completingFor: Date? = nil
    @ObservationIgnored private var sendingTasksFor: Date? = nil
    @ObservationIgnored private var foregroundObserver: (any NSObjectProtocol)? = nil

    private static let storeKey = "activeRun"
    private static let endNotificationId = "run-end"
    private static let beepSound: SystemSoundID = 1005

    init(api: any AuraAPI, defaults: UserDefaults = .standard) {
        self.api = api
        self.defaults = defaults
        let arguments = ProcessInfo.processInfo.arguments
        self.isUITesting = arguments.contains("--uitesting")
        self.isShortRun = arguments.contains("--uitesting-short-run")
        if !isUITesting { restore() }
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    // MARK: Starting and stopping

    /// One run at a time: while one is going, this brings it back instead. `recoveryType` only counts for a recovery
    /// mode; without it the mode's own type is sent.
    func start(mode: Mode, minutes: Int, title: String? = nil, recoveryType: RecoveryType? = nil) {
        guard mode.run != .plan else { return }
        if active != nil {
            isExpanded = true
            return
        }
        var engine = RunEngine(minutes: minutes, hasCountdown: mode.hasCountdown, now: Date())
        if isShortRun, !engine.isOpenEnded { engine.duration = 3 }
        let typed = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        var run = ActiveRun(mode: mode, minutes: minutes, title: typed.isEmpty ? mode.name : typed, engine: engine)
        run.recoveryTypeRaw = recoveryType?.rawValue
        active = run
        isExpanded = true
        save()
        askThenScheduleEnd()
        refresh()
    }

    /// Clears the run on the phone with no backend call. `endSession` and `markDone` are what screens call to finish.
    func close() {
        wake?.cancel()
        wake = nil
        sessionStart = nil
        cancelEndNotification()
        active = nil
        isExpanded = false
        save()
    }

    /// Sign-out, while the token still works: ends the session on the backend, waits a few seconds at most, then clears.
    func endForSignOut() async {
        if let run = active {
            if run.finishedAt != nil {
                retryWhatFailed()
            } else if run.sessionBegun {
                send(.stop)
            }
        }
        await wait(for: lastAction, seconds: 3)
        close()
    }

    /// A task group can't do this: it wouldn't return until `task.value` did, however long that takes.
    private func wait(for task: Task<Void, Never>?, seconds: Double) async {
        guard let task else { return }
        let (stream, continuation) = AsyncStream<Void>.makeStream()
        Task {
            await task.value
            continuation.finish()
        }
        let timer = Task {
            try? await Task.sleep(for: .seconds(seconds))
            continuation.finish()
        }
        for await _ in stream {}
        timer.cancel()
    }

    /// The alert's "Stop it and start mine": ends the other session, then asks again with a new id, so the answer is
    /// this run's own session. Whatever this run already missed (a pause, the final complete) goes out after.
    func stopOtherAndStart() {
        guard var run = active, let other = run.otherSession else { return }
        run.otherSession = nil
        run.startId = UUID().uuidString.lowercased()
        active = run
        save()
        let api = self.api
        let previous = lastAction
        lastAction = Task<Void, Never> { [weak self] in
            _ = await previous?.value
            do {
                try await api.sessionAction(id: other.id, action: .stop)
            } catch {
                // It already ended another way: the start below goes through all the same.
                if (error as? AuraError)?.serverCode != "action_not_allowed" { self?.report(error) }
            }
        }
        retryWhatFailed()
    }

    /// The alert's "Keep it": the other session carries on untouched, and this run ends on the phone.
    func keepOtherSession() {
        guard active?.otherSession != nil else { return }
        close()
    }

    /// Cancel during the 3-2-1 starts nothing and calls nothing.
    func cancelCountdown() {
        if active?.sessionBegun == true { send(.stop) }
        close()
    }

    func togglePause() {
        guard var run = active, run.finishedAt == nil else { return }
        let now = Date()
        if run.engine.isPaused {
            run.engine.resume(at: now)
            active = run
            send(.resume)
            scheduleEndNotification()
        } else {
            run.engine.pause(at: now)
            guard run.engine.isPaused else { return }
            active = run
            send(.pause)
            cancelEndNotification()
        }
        save()
        refresh()
    }

    /// Frame 13's Mark as done: saved as completed, then the Done sheet.
    func markDone() {
        finish(canBeep: false)
    }

    /// Frame 13's End session: saved as stopped, with no Done sheet.
    func endSession() {
        guard var run = active else { return }
        send(.stop)
        run.tomorrowLeft = run.tomorrowLines
        active = run
        sendTomorrowTasks()
        close()
    }

    func setTomorrowList(_ text: String) {
        guard active != nil else { return }
        active?.tomorrowList = text
        save()
    }

    /// The stress change for the Done sheet, once `.complete` has gone out. Nil with no session or on an error.
    func loadTrace() async -> SessionTrace? {
        _ = await lastAction?.value
        var known = active?.sessionId
        if known == nil { known = await sessionStart?.value }
        guard let id = known else { return nil }
        do {
            return try await api.sessionTrace(id: id)
        } catch {
            report(error)
            return nil
        }
    }

    // MARK: The clock

    /// Does whatever is due now, then sleeps until the next thing is. Also runs when the app comes back to the front.
    func refresh() {
        update(canBeep: true)
        retryWhatFailed()
    }

    private func update(canBeep: Bool) {
        guard let run = active, run.finishedAt == nil else { return }
        let now = Date()
        // Before the time-up check: the whole run may have passed while locked, and `.complete` must follow the start.
        if now >= run.engine.startsAt, !run.sessionBegun { beginSession() }
        if run.engine.snapshot(at: now).phase == .timeUp {
            finish(canBeep: canBeep)
            return
        }
        scheduleWake(now: now)
    }

    private func scheduleWake(now: Date) {
        wake?.cancel()
        wake = nil
        guard let run = active, run.finishedAt == nil else { return }
        var due: Date? = run.engine.endDate()
        if now < run.engine.startsAt { due = run.engine.startsAt }
        guard let next = due else { return }
        let delay = max(0, next.timeIntervalSince(now))
        wake = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            if Task.isCancelled { return }
            self?.refresh()
        }
    }

    private func finish(canBeep: Bool) {
        guard var run = active, run.finishedAt == nil else { return }
        run.finishedAt = Date()
        run.tomorrowLeft = run.tomorrowLines
        active = run
        wake?.cancel()
        wake = nil
        cancelEndNotification()
        // A start that failed earlier goes first, or this `.complete` would find no session and be skipped.
        retryStart()
        send(.complete, isFinal: true)
        sendTomorrowTasks()
        save()
        isExpanded = true
        if canBeep, UIApplication.shared.applicationState == .active {
            AudioServicesPlaySystemSound(Self.beepSound)
        }
    }

    // MARK: Backend

    /// Never blocks the run: when this fails, the run goes on and later session actions are skipped.
    private func beginSession() {
        guard var run = active, !run.sessionBegun else { return }
        run.sessionBegun = true
        active = run
        save()
        requestSession(run)
    }

    /// Waits for whatever is still going out, so it can't be overtaken, then asks for the session with the run's own id.
    private func requestSession(_ run: ActiveRun) {
        let api = self.api
        let id = run.startId
        let mode = run.mode
        let title = run.title
        let recoveryType: RecoveryType? = run.recoveryType
        let startsAt = run.engine.startsAt
        let planned: Int? = run.engine.isOpenEnded ? nil : run.minutes
        let previous = lastAction
        startingFor = startsAt
        sessionStart = Task<String?, Never> { [weak self] in
            _ = await previous?.value
            do {
                let session = try await api.startSession(id: id, kind: mode.kind, mode: mode.sessionMode, plannedMinutes: planned,
                                                         title: title, recoveryType: recoveryType)
                self?.sessionStarted(session, startsAt: startsAt)
                // A joined session belongs to another run, so this run's actions mustn't reach it.
                return session.joined ? nil : session.id
            } catch {
                self?.startFailed(error, startsAt: startsAt)
                return nil
            }
        }
    }

    private func sessionStarted(_ session: StartedSession, startsAt: Date) {
        if startingFor == startsAt { startingFor = nil }
        guard var run = active, run.engine.startsAt == startsAt else { return }
        if session.joined {
            run.otherSession = OtherSession(id: session.id, title: session.title, kind: session.kind,
                                            device: session.device, endsAt: session.endsAt)
            active = run
            save()
            return
        }
        run.sessionId = session.id
        let isShortened = applyGrantedLength(session, to: &run)
        active = run
        save()
        if isShortened {
            scheduleEndNotification()
            refresh()
        }
    }

    /// The backend can grant less than was asked, so the phone mustn't count past that. Only a timed run of our own is
    /// cut: a joined session's length belongs to another run, and flow stays open-ended even when a limit gives it one.
    private func applyGrantedLength(_ session: StartedSession, to run: inout ActiveRun) -> Bool {
        guard !session.joined, !isShortRun, !run.engine.isOpenEnded, run.finishedAt == nil,
              let granted = session.plannedMinutes, granted > 0 else { return false }
        let seconds = TimeInterval(granted) * 60
        guard seconds < run.engine.duration else { return false }
        run.engine.duration = seconds
        run.minutes = granted
        return true
    }

    private func startFailed(_ error: Error, startsAt: Date) {
        if startingFor == startsAt { startingFor = nil }
        if active?.engine.startsAt == startsAt, (error as? AuraError)?.serverCode == "work_limit_reached" {
            active?.workLimitReached = true
            save()
        }
        report(error)
    }

    private func send(_ action: SessionAction, isFinal: Bool = false) {
        let api = self.api
        let savedId = active?.sessionId
        let started = sessionStart
        let previous = lastAction
        let startsAt = active?.engine.startsAt
        if isFinal { completingFor = startsAt }
        lastAction = Task<Void, Never> { [weak self] in
            _ = await previous?.value
            var known = savedId
            if known == nil { known = await started?.value }
            guard let id = known else {
                if isFinal { self?.completeEnded(startsAt: startsAt, delivered: false) }
                return
            }
            do {
                try await api.sessionAction(id: id, action: action)
                if isFinal { self?.completeEnded(startsAt: startsAt, delivered: true) }
            } catch {
                // The session has already ended another way, usually the sweeper's, and its minutes are recorded.
                let alreadyEnded = isFinal && (error as? AuraError)?.serverCode == "action_not_allowed"
                if isFinal { self?.completeEnded(startsAt: startsAt, delivered: alreadyEnded) }
                if !alreadyEnded { self?.report(error) }
            }
        }
    }

    private func completeEnded(startsAt: Date?, delivered: Bool) {
        guard active?.engine.startsAt == startsAt else { return }
        completingFor = nil
        guard delivered else { return }
        active?.completeDelivered = true
        save()
    }

    /// Wind-down: each line left becomes a task for the day after the run ended. A sent line is struck off and saved,
    /// so a retry doesn't add it twice.
    private func sendTomorrowTasks() {
        guard let run = active, let lines = run.tomorrowLeft, !lines.isEmpty,
              sendingTasksFor != run.engine.startsAt else { return }
        let ended = run.finishedAt ?? Date()
        let tomorrow = Calendar.current.date(byAdding: .day, value: 1, to: ended) ?? ended
        let plannedFor = DayKey.string(tomorrow)
        let api = self.api
        let startsAt = run.engine.startsAt
        let previous = lastAction
        sendingTasksFor = startsAt
        lastAction = Task<Void, Never> { [weak self] in
            _ = await previous?.value
            for line in lines {
                do {
                    try await api.addTask(title: line, plannedFor: plannedFor)
                    self?.tomorrowLineSent(line, startsAt: startsAt)
                } catch {
                    self?.report(error)
                }
            }
            self?.tomorrowTasksEnded(startsAt: startsAt)
        }
    }

    private func tomorrowLineSent(_ line: String, startsAt: Date) {
        guard var run = active, run.engine.startsAt == startsAt,
              let index = run.tomorrowLeft?.firstIndex(of: line) else { return }
        run.tomorrowLeft?.remove(at: index)
        active = run
        save()
    }

    private func tomorrowTasksEnded(startsAt: Date) {
        if sendingTasksFor == startsAt { sendingTasksFor = nil }
    }

    /// On restore and on coming back to the front: tries again whatever the backend hasn't got and nothing is sending.
    private func retryWhatFailed() {
        guard let run = active else { return }
        retryStart()
        if run.finishedAt != nil, !run.completeDelivered, !run.workLimitReached, completingFor != run.engine.startsAt {
            send(.complete, isFinal: true)
        }
        sendTomorrowTasks()
    }

    private func retryStart() {
        guard let run = active, run.sessionBegun, run.sessionId == nil, run.otherSession == nil, !run.workLimitReached,
              startingFor != run.engine.startsAt else { return }
        requestSession(run)
        // The pause that went out with no session was skipped.
        if run.engine.isPaused { send(.pause) }
    }

    private func report(_ error: Error) {
        lastError = error
        onError?(error)
    }

    // MARK: Local notification

    private func askThenScheduleEnd() {
        guard !isUITesting else { return }
        Task { [weak self] in
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
            self?.scheduleEndNotification()
        }
    }

    private func scheduleEndNotification() {
        guard !isUITesting else { return }
        cancelEndNotification()
        guard let run = active, run.finishedAt == nil, let end = run.engine.endDate() else { return }
        let content = UNMutableNotificationContent()
        content.title = "\(run.title) done"
        content.body = "Your time is up."
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, end.timeIntervalSinceNow), repeats: false)
        let request = UNNotificationRequest(identifier: Self.endNotificationId, content: content, trigger: trigger)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    private func cancelEndNotification() {
        guard !isUITesting else { return }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Self.endNotificationId])
    }

    // MARK: Saving

    private func save() {
        guard !isUITesting else { return }
        if let active, let data = try? JSONEncoder().encode(active) {
            defaults.set(data, forKey: Self.storeKey)
        } else {
            defaults.removeObject(forKey: Self.storeKey)
        }
    }

    /// Runs from `init`, which is before UIApplication exists, so the beep stays off and the rest waits for a task.
    private func restore() {
        guard let data = defaults.data(forKey: Self.storeKey),
              let saved = try? JSONDecoder().decode(ActiveRun.self, from: data) else { return }
        active = saved
        isExpanded = true
        update(canBeep: false)
        retryWhatFailed()
        Task { [weak self] in
            self?.scheduleEndNotification()
        }
    }
}
