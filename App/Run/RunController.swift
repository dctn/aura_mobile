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
    var sessionId: String?
    /// True once the session has been asked for, after any 3-2-1.
    var sessionBegun = false
    /// Set when the run is over: time up or Mark as done. The Done sheet shows from here.
    var finishedAt: Date?
    /// Wind-down's "For tomorrow" text, one thing a line.
    var tomorrowList = ""
    /// True when the final `.complete` didn't reach the backend, so the Done sheet doesn't say "Saved".
    var completeFailed = false
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
    /// The last session action sent. Each one waits for the one before, so they reach the backend in order.
    @ObservationIgnored private var lastAction: Task<Void, Never>? = nil
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

    /// One run at a time: while one is going, this brings it back instead.
    func start(mode: Mode, minutes: Int, title: String? = nil) {
        guard mode.run != .plan else { return }
        if active != nil {
            isExpanded = true
            return
        }
        var engine = RunEngine(minutes: minutes, hasCountdown: mode.hasCountdown, now: Date())
        if isShortRun, !engine.isOpenEnded { engine.duration = 3 }
        let typed = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        active = ActiveRun(mode: mode, minutes: minutes, title: typed.isEmpty ? mode.name : typed, engine: engine)
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
        lastAction = nil
        cancelEndNotification()
        active = nil
        isExpanded = false
        save()
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
        guard let run = active else { return }
        send(.stop)
        sendTomorrowList(run)
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
    }

    private func update(canBeep: Bool) {
        guard let run = active, run.finishedAt == nil else { return }
        let now = Date()
        if run.engine.snapshot(at: now).phase == .timeUp {
            finish(canBeep: canBeep)
            return
        }
        if now >= run.engine.startsAt, !run.sessionBegun { beginSession() }
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
        active = run
        wake?.cancel()
        wake = nil
        cancelEndNotification()
        send(.complete, isFinal: true)
        sendTomorrowList(run)
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
        let api = self.api
        let mode = run.mode
        let title = run.title
        let startsAt = run.engine.startsAt
        let planned: Int? = run.engine.isOpenEnded ? nil : run.minutes
        sessionStart = Task<String?, Never> { [weak self] in
            do {
                let session = try await api.startSession(kind: mode.kind, mode: mode.sessionMode, plannedMinutes: planned, title: title)
                self?.sessionStarted(id: session.id, startsAt: startsAt)
                return session.id
            } catch {
                self?.report(error)
                return nil
            }
        }
    }

    private func sessionStarted(id: String, startsAt: Date) {
        guard active?.engine.startsAt == startsAt else { return }
        active?.sessionId = id
        save()
    }

    private func send(_ action: SessionAction, isFinal: Bool = false) {
        let api = self.api
        let savedId = active?.sessionId
        let started = sessionStart
        let previous = lastAction
        lastAction = Task<Void, Never> { [weak self] in
            _ = await previous?.value
            var known = savedId
            if known == nil { known = await started?.value }
            guard let id = known else {
                if isFinal { self?.noteCompleteFailed() }
                return
            }
            do {
                try await api.sessionAction(id: id, action: action)
            } catch {
                if isFinal { self?.noteCompleteFailed() }
                self?.report(error)
            }
        }
    }

    private func noteCompleteFailed() {
        guard active != nil else { return }
        active?.completeFailed = true
        save()
    }

    /// Wind-down: each line becomes a task due tomorrow.
    private func sendTomorrowList(_ run: ActiveRun) {
        guard run.mode.run == .winddown else { return }
        let lines = run.tomorrowList
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard !lines.isEmpty else { return }
        let calendar = Calendar.current
        let due = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: Date()))
        let api = self.api
        Task { [weak self] in
            for line in lines {
                do {
                    try await api.addTask(title: line, due: due)
                } catch {
                    self?.report(error)
                }
            }
        }
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
        Task { [weak self] in
            self?.scheduleEndNotification()
        }
    }
}
