import AuraKit
import SwiftUI
import UIKit

/// Frames 02 to 13: every run, drawn from `engine.snapshot(at:)`.
struct RunScreen: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            if let run = model.run.active {
                RunStage(run: run)
            } else {
                AuraColor.background.ignoresSafeArea()
            }
        }
        .otherSessionAlert(showsHere: true)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }
}

private enum RunAlert {
    case end(String)
    case info(title: String, body: String)

    var title: String {
        switch self {
        case .end(let question): return question
        case .info(let title, _): return title
        }
    }
}

private struct RunStage: View {
    @Environment(AppModel.self) private var model
    let run: ActiveRun

    @State private var alert: RunAlert? = nil
    @State private var alertShown = false
    @State private var doneShown = false
    @FocusState private var listFocused: Bool

    /// Frames every 1/30 s only for the breathing circle. UI tests stay slow so the test runner sees an idle app.
    private static let isUITesting = ProcessInfo.processInfo.arguments.contains("--uitesting")

    private var isDone: Bool { run.finishedAt != nil }
    private var isNight: Bool { run.mode.night == true }

    private var frameInterval: Double {
        if run.mode.run == .breathe, !Self.isUITesting { return 1.0 / 30.0 }
        return 0.25
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: frameInterval, paused: isDone || run.engine.isPaused)) { timeline in
            stageFrame(now: run.finishedAt ?? timeline.date)
        }
        .statusBarHidden(isNight)
        .alert(alert?.title ?? "", isPresented: $alertShown, presenting: alert) { item in
            alertButtons(item)
        } message: { item in
            alertMessage(item)
        }
        .onChange(of: isDone) { _, done in
            if done { alertShown = false }
        }
        .task(id: isDone) {
            guard isDone else { return }
            // Lets the run screen finish coming up, or the end-early dialog finish going away.
            try? await Task.sleep(for: .milliseconds(500))
            if !Task.isCancelled { doneShown = true }
        }
        .sheet(isPresented: $doneShown, onDismiss: { model.run.close() }) {
            RunDoneSheet(run: run)
                .presentationDetents([.height(330)])
                .presentationDragIndicator(.visible)
                .presentationBackground(RunLook.sheet)
        }
    }

    @ViewBuilder
    private func stageFrame(now: Date) -> some View {
        let snapshot = run.engine.snapshot(at: now)
        if case .countdown(let left) = snapshot.phase {
            RunCountdownView(left: left, progress: snapshot.countdownProgress) {
                model.run.cancelCountdown()
            }
        } else {
            VStack(spacing: 0) {
                topBar
                RunCenter(run: run, snapshot: snapshot, now: now, listFocus: $listFocused)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.bottom, listFocused ? 0 : 36)
                if !listFocused {
                    RunControls(
                        isPaused: run.engine.isPaused,
                        isNight: isNight,
                        onPause: { model.run.togglePause() },
                        onEnd: { askToEnd() }
                    )
                }
            }
            .background(pageBackground)
        }
    }

    private var pageBackground: some View {
        (isNight ? Color.black : AuraColor.background)
            .ignoresSafeArea()
            .contentShape(Rectangle())
            .onTapGesture { listFocused = false }
    }

    private var topBar: some View {
        HStack {
            Button {
                model.run.isExpanded = false
            } label: {
                HStack(spacing: 6) {
                    Text("Hide").font(.system(size: 17))
                    Image(systemName: "chevron.down").font(.system(size: 13, weight: .medium))
                }
            }
            .accessibilityIdentifier("run-hide")
            Spacer()
            Button(action: showInfo) {
                Image(systemName: "info.circle").font(.system(size: 22))
            }
            .accessibilityLabel("About this run")
            .accessibilityIdentifier("run-info")
        }
        .buttonStyle(.plain)
        .foregroundStyle(isNight ? RunLook.nightDim : AuraColor.runFocus)
        .padding(.horizontal, 22)
        .padding(.top, 12)
    }

    private func askToEnd() {
        alert = .end(run.engine.endEarlyTitle(at: Date()))
        alertShown = true
    }

    private func showInfo() {
        let length = run.engine.isOpenEnded ? "Open-ended" : RunEngine.lengthText(run.engine.duration)
        let text = "\(run.mode.note) \(length), timed on this phone and saved as a session. "
            + "Hide keeps it running above the tabs. End lets you stop early or count it as done."
        alert = .info(title: run.title, body: text)
        alertShown = true
    }

    @ViewBuilder
    private func alertButtons(_ item: RunAlert) -> some View {
        switch item {
        case .end:
            Button("Mark as done") { model.run.markDone() }
            Button("End session", role: .destructive) { model.run.endSession() }
            Button("Cancel", role: .cancel) {}
        case .info:
            Button("OK", role: .cancel) {}
        }
    }

    @ViewBuilder
    private func alertMessage(_ item: RunAlert) -> some View {
        switch item {
        case .end:
            EmptyView()
        case .info(_, let body):
            Text(body)
        }
    }
}

// MARK: The middle of the screen

private struct RunCenter: View {
    @Environment(AppModel.self) private var model
    let run: ActiveRun
    let snapshot: RunSnapshot
    let now: Date
    var listFocus: FocusState<Bool>.Binding

    private var accent: Color { run.mode.runAccent }
    private var isNight: Bool { run.mode.night == true }
    private var clockText: String { RunEngine.clock(snapshot.remaining ?? snapshot.elapsed) }

    var body: some View {
        switch run.mode.run {
        case .breathe:
            breathe
        case .guided:
            guided
        case .winddown:
            windDown
        case .timer, .flow, .plan:
            plain
        }
    }

    private var heading: some View {
        RunHeading(kicker: run.mode.kicker(minutes: run.minutes), title: run.title, accent: accent, isNight: isNight)
    }

    private var line: some View {
        RunProgressLine(progress: snapshot.progress, accent: accent, isNight: isNight)
    }

    /// Frames 03, 04, 05, 06 and 10.
    private var plain: some View {
        VStack(spacing: 0) {
            heading
            RunClockText(text: clockText, size: 72, isNight: isNight).padding(.top, 8)
            RunNoteText(text: run.mode.note, isNight: isNight).padding(.top, 8)
            line.padding(.top, 24)
        }
    }

    /// Frame 07.
    private var breathe: some View {
        let phase = BreathPhase.at(snapshot.elapsed, pattern: run.mode.pattern)
        return VStack(spacing: 0) {
            heading
            RunBreathCircle(scale: phase.scale, accent: accent).padding(.top, 26)
            Text("\(Text(phase.word.rawValue).fontWeight(.semibold)) \(Text(String(phase.left)).foregroundStyle(accent.opacity(0.7)))")
                .font(.system(size: 15))
                .foregroundStyle(accent)
                .padding(.top, 8)
            RunClockText(text: clockText, size: 44, isNight: false).padding(.top, 6)
            RunNoteText(text: run.mode.note, isNight: false).padding(.top, 8)
            line.padding(.top, 22)
        }
    }

    /// Frame 09: one prompt every length ÷ 6.
    private var guided: some View {
        let prompts = run.mode.prompts ?? []
        let index = run.engine.promptIndex(at: now, count: prompts.count)
        return VStack(spacing: 0) {
            heading
            RunClockText(text: clockText, size: 72, isNight: false).padding(.top, 8)
            RunNoteText(text: run.mode.note, isNight: false).padding(.top, 8)
            line.padding(.top, 22)
            if index < prompts.count {
                Text("\(index + 1) OF \(prompts.count)")
                    .font(.system(size: 11, weight: .bold))
                    .tracking(0.6)
                    .foregroundStyle(accent)
                    .padding(.top, 28)
                Text(prompts[index])
                    .font(.system(size: 15))
                    .foregroundStyle(RunLook.ink)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
                    .padding(.top, 8)
            }
        }
    }

    /// Frame 11.
    private var windDown: some View {
        VStack(spacing: 0) {
            heading
            RunClockText(text: clockText, size: 72, isNight: false).padding(.top, 8)
            RunNoteText(text: run.mode.note, isNight: false).padding(.top, 8)
            line.padding(.top, 22)
            RunTomorrowList(text: tomorrowBinding, focus: listFocus).padding(.top, 28)
        }
    }

    private var tomorrowBinding: Binding<String> {
        let controller = model.run
        return Binding(
            get: { controller.active?.tomorrowList ?? "" },
            set: { controller.setTomorrowList($0) }
        )
    }
}

private struct RunHeading: View {
    let kicker: String
    let title: String
    let accent: Color
    let isNight: Bool

    var body: some View {
        VStack(spacing: 4) {
            Text(kicker)
                .font(.system(size: 11, weight: .bold))
                .tracking(0.6)
                .foregroundStyle(accent)
            Text(title)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(isNight ? Color.white : RunLook.ink)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .padding(.horizontal, 30)
        }
    }
}

private struct RunClockText: View {
    let text: String
    let size: CGFloat
    let isNight: Bool

    var body: some View {
        Text(text)
            .font(.system(size: size, weight: .light))
            .monospacedDigit()
            .foregroundStyle(isNight ? Color.white : RunLook.ink)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
    }
}

private struct RunNoteText: View {
    let text: String
    let isNight: Bool

    var body: some View {
        Text(text)
            .font(.system(size: 15))
            .foregroundStyle(isNight ? RunLook.nightDim : RunLook.note)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 50)
    }
}

/// Shows nothing for an open-ended run (frame 04).
private struct RunProgressLine: View {
    let progress: Double?
    let accent: Color
    let isNight: Bool

    var body: some View {
        if let progress {
            Capsule()
                .fill(isNight ? RunLook.nightButton : RunLook.track)
                .frame(width: 282, height: 4)
                .overlay(alignment: .leading) {
                    Capsule()
                        .fill(accent)
                        .frame(width: max(4, 282 * CGFloat(progress)), height: 4)
                }
        }
    }
}

/// Frames 07 and 08: the inner circle eases between 62% and 100% of the halo.
private struct RunBreathCircle: View {
    let scale: Double
    let accent: Color

    private let diameter: CGFloat = 222

    var body: some View {
        ZStack {
            Circle().fill(accent.opacity(0.1))
            Circle()
                .fill(accent.opacity(0.2))
                .overlay(Circle().stroke(accent, lineWidth: 2.5))
                .frame(width: diameter * CGFloat(scale), height: diameter * CGFloat(scale))
            Circle()
                .fill(accent.opacity(0.7))
                .frame(width: 14, height: 14)
        }
        .frame(width: diameter, height: diameter)
        .accessibilityHidden(true)
    }
}

/// Frame 11's "For tomorrow". Each line becomes a task when the run finishes.
private struct RunTomorrowList: View {
    @Binding var text: String
    var focus: FocusState<Bool>.Binding

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("FOR TOMORROW")
                .font(.system(size: 12))
                .foregroundStyle(RunLook.note)
            field
            Text("One thing a line; they go on your task list for tomorrow.")
                .font(.system(size: 12))
                .foregroundStyle(RunLook.note)
        }
        .padding(.horizontal, 16)
    }

    private var field: some View {
        ZStack(alignment: .topLeading) {
            if text.isEmpty {
                Text("Reply to Ana\nBook the dentist")
                    .font(.system(size: 15))
                    .foregroundStyle(RunLook.note)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .allowsHitTesting(false)
            }
            TextEditor(text: $text)
                .font(.system(size: 15))
                .scrollContentBackground(.hidden)
                .padding(.horizontal, 11)
                .padding(.vertical, 4)
                .focused(focus)
                .accessibilityLabel("For tomorrow")
                .accessibilityIdentifier("run-tomorrow")
        }
        .frame(height: 104)
        .background(AuraColor.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

// MARK: Controls

private struct RunControls: View {
    let isPaused: Bool
    let isNight: Bool
    let onPause: () -> Void
    let onEnd: () -> Void

    var body: some View {
        HStack(spacing: 40) {
            Button(action: onPause) {
                Image(systemName: isPaused ? "play.fill" : "pause.fill")
                    .font(.system(size: 24))
                    .foregroundStyle(isNight ? Color.white : Color.black)
                    .frame(width: 72, height: 72)
                    .background(isNight ? RunLook.nightButton : RunLook.track, in: Circle())
            }
            .accessibilityLabel(isPaused ? "Resume" : "Pause")
            .accessibilityIdentifier("run-pause")

            Button(action: onEnd) {
                Image(systemName: "xmark")
                    .font(.system(size: 24, weight: .regular))
                    .foregroundStyle(.white)
                    .frame(width: 72, height: 72)
                    .background(AuraColor.red, in: Circle())
            }
            .accessibilityLabel("End")
            .accessibilityIdentifier("run-end")
        }
        .buttonStyle(.plain)
        .padding(.bottom, 30)
    }
}

// MARK: 3-2-1

/// Frame 02. Focus shelf only.
private struct RunCountdownView: View {
    let left: Int
    let progress: Double
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ring.frame(maxHeight: .infinity)
            Button("Cancel", action: onCancel)
                .buttonStyle(.plain)
                .font(.system(size: 15))
                .foregroundStyle(AuraColor.runFocus)
                .accessibilityIdentifier("run-countdown-cancel")
                .padding(.bottom, 52)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AuraColor.background.ignoresSafeArea())
    }

    private var ring: some View {
        ZStack {
            Circle().stroke(RunLook.track, lineWidth: 12)
            Circle()
                .trim(from: 0, to: CGFloat(progress))
                .stroke(RunLook.go, style: StrokeStyle(lineWidth: 12))
                .rotationEffect(.degrees(-90))
            Text(String(left))
                .font(.system(size: 60, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(RunLook.ink)
        }
        .frame(width: 188, height: 188)
    }
}

// MARK: Done

/// Frame 12. The stress change comes from the session trace, and is only shown when there is one.
private struct RunDoneSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let run: ActiveRun

    @State private var isLoaded = false
    @State private var trace: SessionTrace? = nil

    private var accent: Color { run.mode.runAccent }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Circle().fill(accent.opacity(0.2)).frame(width: 78, height: 78)
                Circle().fill(accent).frame(width: 64, height: 64)
                Image(systemName: "checkmark")
                    .font(.system(size: 28, weight: .regular))
                    .foregroundStyle(.white)
            }
            .padding(.top, 34)
            Text("\(run.title) done")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(RunLook.ink)
                .multilineTextAlignment(.center)
                .padding(.top, 14)
            Text(summary)
                .font(.system(size: 15))
                .foregroundStyle(RunLook.note)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 44)
                .padding(.top, 6)
            Spacer(minLength: 12)
            doneButton
        }
        .frame(maxWidth: .infinity)
        .task {
            trace = await model.run.loadTrace()
            isLoaded = true
        }
    }

    private var doneButton: some View {
        Button {
            dismiss()
        } label: {
            Text("Done")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 50)
                .background(accent, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("run-done")
        .padding(.horizontal, 16)
        .padding(.bottom, 28)
    }

    private var summary: String {
        let seconds = run.engine.elapsed(at: run.finishedAt ?? Date())
        var parts = ["\(RunEngine.lengthText(seconds))."]
        if isLoaded {
            parts.append(saveLine)
            if let stress = stressLine { parts.append(stress) }
        }
        return parts.joined(separator: " ")
    }

    private var saveLine: String {
        let current = model.run.active ?? run
        if current.workLimitReached { return "Not saved: you've reached today's work limit." }
        return current.isSaved ? "Saved to your sessions." : "Not saved: Aura couldn't be reached."
    }

    /// Only recovery and walk sessions have a stress change. Same words as the website.
    private var stressLine: String? {
        guard run.mode.kind != "focus", run.mode.kind != "meeting", let trace else { return nil }
        if let drop = trace.stressDrop {
            if drop > 0 { return "Stress fell \(Int(drop.rounded())) points." }
            return "Stress didn't drop this time."
        }
        switch trace.status {
        case "pending": return "Your stress change shows once today's data is computed."
        case "noData": return "No band data for this session, so there is no stress change to show."
        default: return nil
        }
    }
}

// MARK: Hidden

/// Frame 14: a hidden run in a glass bar above the tabs. Shows nothing unless a run is hidden.
struct MiniRunBar: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let run = model.run.active, !model.run.isExpanded, run.finishedAt == nil {
            RunMiniBarRow(run: run)
        }
    }
}

private struct RunMiniBarRow: View {
    @Environment(AppModel.self) private var model
    let run: ActiveRun

    var body: some View {
        HStack(spacing: 10) {
            Button {
                model.run.isExpanded = true
            } label: {
                reopenLabel
            }
            .accessibilityIdentifier("mini-run-bar")

            Button {
                model.run.togglePause()
            } label: {
                Image(systemName: run.engine.isPaused ? "play.fill" : "pause.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(.black)
                    .frame(width: 40, height: 40)
            }
            .accessibilityLabel(run.engine.isPaused ? "Resume" : "Pause")
            .accessibilityIdentifier("mini-run-pause")
        }
        .buttonStyle(.plain)
        .padding(.leading, 10)
        .padding(.trailing, 8)
        .frame(height: 50)
        .glassEffect(.regular, in: Capsule())
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private var reopenLabel: some View {
        HStack(spacing: 10) {
            Image(systemName: run.mode.runSymbol)
                .font(.system(size: 15))
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(run.mode.runAccent, in: Circle())
            VStack(alignment: .leading, spacing: 1) {
                Text(run.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(RunLook.ink)
                    .lineLimit(1)
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    Text(status(at: context.date))
                        .font(.system(size: 13))
                        .monospacedDigit()
                        .foregroundStyle(RunLook.note)
                }
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }

    private func status(at date: Date) -> String {
        let snapshot = run.engine.snapshot(at: date)
        if run.engine.isPaused { return "Paused" }
        if let remaining = snapshot.remaining { return "\(RunEngine.clock(remaining)) left" }
        return RunEngine.clock(snapshot.elapsed)
    }
}

// MARK: Look

/// Colours sampled from "Modes · active screens" that AuraColor doesn't have.
enum RunLook {
    static let ink = Color(hex: 0x1C1C1E)
    static let note = Color(hex: 0x86868A)
    static let track = Color(hex: 0xE5E5E6)
    static let sheet = Color(hex: 0xF2F2F7)
    static let go = Color(hex: 0x1ED760)
    static let nightDim = Color(hex: 0x8D8D93)
    static let nightButton = Color(hex: 0x262629)
}

extension Mode {
    /// A run's accent, from the frames: by what kind of run it is, and otherwise its shelf's colour (frame 06).
    var runAccent: Color {
        switch run {
        case .breathe: return AuraColor.runBreathe
        case .guided: return AuraColor.runMeditate
        case .flow: return AuraColor.runFlow
        case .winddown: return AuraColor.runNap
        case .timer, .plan:
            if kind == "meeting" { return AuraColor.runMeeting }
            if kind == "focus" { return AuraColor.runFocus }
            if night == true { return AuraColor.runNap }
            return shelf.color
        }
    }

    /// The icon in the bar above the tabs.
    var runSymbol: String {
        return run == .breathe ? "wind" : shelf.symbol
    }
}
