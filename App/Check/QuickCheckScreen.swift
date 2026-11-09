import AuraKit
import QuartzCore
import SwiftUI
import UIKit

/// The Quick check, full screen and dark like the design ("Active screens · concepts", last row): an intro,
/// the 3-minute reaction test, the memory part, then what the backend made of it.
/// `onFinished` gets the backend's answer after Done, or nil when the person left without one.
struct QuickCheckScreen: View {
    let onFinished: (QuickCheckResult?) -> Void

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var flow: QuickCheckFlow? = nil
    @State private var closed = false

    private static let isUITesting = ProcessInfo.processInfo.arguments.contains("--uitesting")

    init(onFinished: @escaping (QuickCheckResult?) -> Void) {
        self.onFinished = onFinished
    }

    var body: some View {
        Group {
            if let flow {
                QuickCheckStage(flow: flow, onClose: close)
            } else {
                QuickCheckLook.background.ignoresSafeArea()
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            if flow == nil {
                let model = self.model
                flow = QuickCheckFlow(api: model.api, short: model.quickCheckShort, uiTesting: Self.isUITesting) {
                    [weak model] error in
                    // Missing consent opens the consent step from here.
                    model?.note(error)
                }
            }
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { flow?.interrupt() }
        }
        .onDisappear {
            flow?.stop()
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    private func close(_ result: QuickCheckResult?) {
        guard !closed else { return }
        closed = true
        flow?.stop()
        onFinished(result)
        dismiss()
    }
}

// MARK: The frame around every step

private struct QuickCheckStage: View {
    let flow: QuickCheckFlow
    let onClose: (QuickCheckResult?) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var askingStop = false
    @State private var showingInfo = false

    private var accent: Color {
        switch flow.step {
        case .reaction: return QuickCheckLook.green
        case .memory: return QuickCheckLook.purple
        case .intro, .sending, .result: return QuickCheckLook.dim
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            stepView
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(QuickCheckLook.background.ignoresSafeArea())
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: flow.step)
        .alert("Stop the check?", isPresented: $askingStop) {
            Button("Stop", role: .destructive) { onClose(nil) }
            Button("Keep going", role: .cancel) {}
        } message: {
            Text("It won't be sent.")
        }
    }

    private var topBar: some View {
        HStack {
            Button(action: hide) {
                HStack(spacing: 6) {
                    Text("Hide").font(.system(size: 17))
                    Image(systemName: "chevron.down").font(.system(size: 13, weight: .medium))
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .disabled(flow.step == .sending && !flow.sendFailed)
            .accessibilityIdentifier("quick-check-hide")
            Spacer()
            Button {
                showingInfo = true
            } label: {
                Image(systemName: "info.circle")
                    .font(.system(size: 22))
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("About this step")
            .accessibilityIdentifier("quick-check-info")
        }
        .buttonStyle(.plain)
        .foregroundStyle(accent)
        .padding(.horizontal, 22)
        .padding(.top, 4)
        .alert(infoTitle, isPresented: $showingInfo) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(infoText)
        }
    }

    @ViewBuilder
    private var stepView: some View {
        switch flow.step {
        case .intro:
            QuickCheckIntro(interrupted: flow.interrupted) { flow.startReaction() }
        case .reaction:
            QuickCheckReaction(flow: flow)
        case .memory:
            QuickCheckMemory(flow: flow)
        case .sending:
            QuickCheckSending(failed: flow.sendFailed) { flow.submit() }
        case .result:
            if let result = flow.result {
                QuickCheckResultView(result: result, bestSpan: flow.memoryDone ? flow.span.bestSpan : nil) {
                    onClose(result)
                }
            }
        }
    }

    /// The intro closes at once (nothing has run); a running part asks first.
    private func hide() {
        switch flow.step {
        case .intro, .sending:
            onClose(nil)
        case .reaction, .memory:
            askingStop = true
        case .result:
            onClose(flow.result)
        }
    }

    private var infoTitle: String {
        switch flow.step {
        case .reaction: return "Reaction"
        case .memory: return "Memory"
        case .intro, .sending, .result: return "Quick check"
        }
    }

    private var infoText: String {
        switch flow.step {
        case .intro:
            return "Two short tests of how alert you are right now: a 3-minute reaction test, then a memory test. "
                + "Aura uses it to plan your day. Hide closes this; nothing is sent."
        case .reaction:
            return "Tap the card the moment it turns green. A tap before green is a false start, "
                + "and an answer of 355 ms or slower counts as a slow answer. It runs for 3 minutes."
        case .memory:
            return "Digits show for a moment; type them back in order. A right answer adds a digit, "
                + "and two misses at the same length end it. Skip sends the check without this part."
        case .sending, .result:
            return "Aura scores the check. It counts when it ran about 3 minutes, with at least 30 answers "
                + "and few false starts. Your normal uses morning checks taken 30+ minutes after waking, "
                + "after 7+ hours of sleep."
        }
    }
}

private struct QuickCheckHeading: View {
    let kicker: String
    let title: String
    let accent: Color

    var body: some View {
        VStack(spacing: 4) {
            Text(kicker)
                .font(.system(size: 11, weight: .bold))
                .tracking(0.6)
                .foregroundStyle(accent)
            Text(title)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(Color.white)
                .accessibilityAddTraits(.isHeader)
        }
    }
}

/// A full-width capsule button in the dark design's colours.
private struct QuickCheckPill: View {
    let title: String
    let fill: Color
    let ink: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 19, weight: .semibold))
                .foregroundStyle(ink)
                .frame(maxWidth: .infinity, minHeight: 54)
                .background(fill, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: Intro

private struct QuickCheckIntro: View {
    /// The app was left during the reaction test, so it starts again.
    let interrupted: Bool
    let onStart: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("QUICK CHECK")
                            .font(.system(size: 11, weight: .bold))
                            .tracking(0.6)
                            .foregroundStyle(QuickCheckLook.dim)
                        Text("Quick check")
                            .font(.system(size: 30, weight: .bold))
                            .foregroundStyle(Color.white)
                            .accessibilityAddTraits(.isHeader)
                            .accessibilityIdentifier("quick-check-intro")
                        Text("About 4 minutes, in two steps.")
                            .font(.system(size: 17))
                            .foregroundStyle(Color.white.opacity(0.85))
                    }
                    if interrupted {
                        Text("The check stopped when you left the app. The reaction test needs you here the whole time, so it starts again.")
                            .font(.system(size: 15))
                            .foregroundStyle(QuickCheckLook.green)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("quick-check-interrupted")
                    }
                    stepRow(number: 1, color: QuickCheckLook.green, title: "Reaction",
                            text: "Tap the card the moment it turns green. For 3 minutes.")
                    stepRow(number: 2, color: QuickCheckLook.purple, title: "Memory",
                            text: "A row of digits shows for a moment. Type them back.")
                    Text("Best in the morning, 30+ minutes after waking.")
                        .font(.system(size: 15))
                        .foregroundStyle(QuickCheckLook.dim)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 26)
                .padding(.top, 24)
            }
            QuickCheckPill(title: "Start", fill: QuickCheckLook.green, ink: Color.black, action: onStart)
                .accessibilityIdentifier("quick-check-start")
                .padding(.horizontal, 22)
                .padding(.bottom, 16)
        }
    }

    private func stepRow(number: Int, color: Color, title: String, text: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Text(String(number))
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Color.black)
                .frame(width: 28, height: 28)
                .background(color, in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(color)
                Text(text)
                    .font(.system(size: 15))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(QuickCheckLook.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

// MARK: Reaction

private struct QuickCheckReaction: View {
    let flow: QuickCheckFlow

    /// True from a touch-down until that touch ends, so one touch is one tap.
    @State private var touching = false
    /// Resets itself when the system cancels a touch, which `onEnded` doesn't see.
    @GestureState private var pressed = false

    var body: some View {
        VStack(spacing: 0) {
            QuickCheckHeading(kicker: "QUICK CHECK · 1 OF 2", title: "Reaction", accent: QuickCheckLook.green)
            card
                .padding(.horizontal, 24)
                .padding(.top, 28)
            recentRow
                .padding(.horizontal, 36)
                .padding(.top, 26)
            Spacer(minLength: 0)
        }
        .padding(.top, 16)
        .onChange(of: pressed) { _, isPressed in
            if !isPressed { touching = false }
        }
    }

    private var isDot: Bool { flow.reactionPhase == .dot }

    private var card: some View {
        let shape = RoundedRectangle(cornerRadius: 40, style: .continuous)
        // No animation on the card: the green must show on the frame the dot is counted from.
        return ZStack {
            shape.fill(isDot ? QuickCheckLook.green : QuickCheckLook.card)
            VStack(spacing: 10) {
                cardMain
                Text(leftText)
                    .font(.system(size: 15))
                    .monospacedDigit()
                    .foregroundStyle(isDot ? Color.black.opacity(0.55) : QuickCheckLook.dim)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 380)
        .contentShape(shape)
        .gesture(touchDown)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Reaction card")
        .accessibilityValue(cardValue)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("quick-check-reaction")
    }

    /// Counts the touch when the finger lands, not when it lifts: a tap-up would add the finger's press time.
    private var touchDown: some Gesture {
        DragGesture(minimumDistance: 0)
            .updating($pressed) { _, state, _ in
                state = true
            }
            .onChanged { _ in
                guard !touching else { return }
                touching = true
                flow.tap(at: CACurrentMediaTime())
            }
            .onEnded { _ in
                touching = false
            }
    }

    @ViewBuilder
    private var cardMain: some View {
        switch flow.reactionPhase {
        case .dot:
            Text("Tap!")
                .font(.system(size: 52, weight: .bold))
                .foregroundStyle(Color.black)
        case .result(let ms):
            Text("\(Int(ms.rounded())) ms")
                .font(.system(size: 44, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(Color.white)
        case .tooSoon:
            Text("Too soon")
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(QuickCheckLook.orange)
        case .notStarted, .waiting:
            Text("Wait for green")
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(QuickCheckLook.dim)
        case .finished:
            Text("Done")
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(Color.white)
        }
    }

    /// Read by VoiceOver and by the UI test, which taps only on "Tap!".
    private var cardValue: String {
        switch flow.reactionPhase {
        case .dot: return "Tap!"
        case .result(let ms): return "\(Int(ms.rounded())) ms"
        case .tooSoon: return "Too soon"
        case .notStarted, .waiting: return "Waiting"
        case .finished: return "Done"
        }
    }

    private var leftText: String {
        let seconds = max(0, flow.secondsLeft)
        return "\(seconds / 60):\(String(format: "%02d", seconds % 60)) left"
    }

    /// The last 3 times, newest first: "312 ms   —   —".
    private var recentRow: some View {
        let times = flow.recentTimes
        return HStack(spacing: 0) {
            ForEach(0..<3, id: \.self) { index in
                Text(index < times.count ? timeText(times[index]) : "—")
                    .font(.system(size: 17, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(index < times.count ? Color.white : QuickCheckLook.dim)
                    .frame(maxWidth: .infinity)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(recentLabel(times))
        .accessibilityIdentifier("quick-check-recent")
    }

    /// An unanswered dot holds the timeout; it shows as "Missed".
    private func timeText(_ ms: Double) -> String {
        if ms >= flow.reactionConfig.timeout * 1000 - 0.5 { return "Missed" }
        return "\(Int(ms.rounded())) ms"
    }

    private func recentLabel(_ times: [Double]) -> String {
        if times.isEmpty { return "No times yet" }
        let words: [String] = times.map { timeText($0) }
        return "Last times: " + words.joined(separator: ", ")
    }
}

// MARK: Memory

private struct QuickCheckMemory: View {
    let flow: QuickCheckFlow

    private let keyWidth: CGFloat = 86
    private let keyHeight: CGFloat = 56

    var body: some View {
        VStack(spacing: 0) {
            QuickCheckHeading(kicker: "QUICK CHECK · 2 OF 2", title: "Memory", accent: QuickCheckLook.purple)
            prompt
                .frame(height: 104)
                .padding(.top, 36)
            boxes
                .frame(height: 52)
                .padding(.top, 14)
            keypadArea
                .padding(.top, 30)
            Spacer(minLength: 0)
            Button {
                flow.skipMemory()
            } label: {
                Text("Skip")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(QuickCheckLook.dim)
                    .frame(minWidth: 88, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Sends the check without the memory part")
            .accessibilityIdentifier("quick-check-skip-memory")
            .padding(.bottom, 12)
        }
        .padding(.top, 16)
    }

    private var span: SpanTest { flow.span }

    @ViewBuilder
    private var prompt: some View {
        VStack(spacing: 14) {
            if let right = flow.feedback {
                Text(right ? "Right" : "Not quite")
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(right ? QuickCheckLook.green : QuickCheckLook.orange)
                    .accessibilityIdentifier("quick-check-feedback")
            } else if !flow.memoryBegun {
                Text("Get ready")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(QuickCheckLook.dim)
            } else if span.phase == .showing {
                Text(span.digits.map(String.init).joined(separator: " "))
                    .font(.system(size: 48, weight: .light))
                    .tracking(6)
                    .monospacedDigit()
                    .foregroundStyle(Color.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .padding(.horizontal, 20)
                    .accessibilityLabel(span.digits.map(String.init).joined(separator: " "))
                    .accessibilityIdentifier("quick-check-digits")
                Text("Remember these · \(span.digits.count) digits")
                    .font(.system(size: 15))
                    .foregroundStyle(QuickCheckLook.dim)
            } else if span.phase == .entering {
                Text("Type them back")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(Color.white)
                Text("\(span.digits.count) digits")
                    .font(.system(size: 15))
                    .foregroundStyle(QuickCheckLook.dim)
            }
        }
    }

    /// One box per digit: empty while the row shows, then what was typed.
    @ViewBuilder
    private var boxes: some View {
        let row: [Int] = flow.feedback != nil ? flow.feedbackRow : span.entered
        let count = flow.feedback != nil ? flow.feedbackRow.count : (flow.memoryBegun ? span.digits.count : 0)
        let wide = count <= 6
        let tint: Color? = flow.feedback.map { $0 ? QuickCheckLook.green : QuickCheckLook.orange }
        HStack(spacing: wide ? 10 : 6) {
            ForEach(0..<count, id: \.self) { index in
                Text(index < row.count ? String(row[index]) : " ")
                    .font(.system(size: wide ? 24 : 20, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Color.white)
                    .frame(width: wide ? 46 : 32, height: 52)
                    .background(QuickCheckLook.card, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay {
                        if let tint {
                            RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(tint, lineWidth: 2)
                        }
                    }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.typedLabel(row))
        .accessibilityIdentifier("quick-check-entry")
    }

    private static func typedLabel(_ row: [Int]) -> String {
        if row.isEmpty { return "Nothing typed" }
        let words: [String] = row.map { String($0) }
        return "Typed " + words.joined(separator: " ")
    }

    /// The keypad only exists while typing, so a test (or a person) can't type while the row shows.
    /// Its space is kept so nothing jumps.
    private var keypadArea: some View {
        ZStack {
            if span.phase == .entering || flow.feedback != nil {
                keypad
                    .disabled(flow.feedback != nil)
            }
        }
        .frame(height: keyHeight * 4 + 30)
    }

    private var keypad: some View {
        VStack(spacing: 10) {
            ForEach(0..<3, id: \.self) { row in
                HStack(spacing: 12) {
                    ForEach(1...3, id: \.self) { column in
                        key(row * 3 + column)
                    }
                }
            }
            HStack(spacing: 12) {
                Color.clear.frame(width: keyWidth, height: keyHeight)
                key(0)
                Button {
                    flow.deleteLast()
                } label: {
                    Image(systemName: "delete.left")
                        .font(.system(size: 20))
                        .foregroundStyle(Color.white)
                        .frame(width: keyWidth, height: keyHeight)
                        .background(QuickCheckLook.key, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Delete")
                .accessibilityIdentifier("quick-check-delete")
            }
        }
    }

    private func key(_ digit: Int) -> some View {
        Button {
            flow.type(digit)
        } label: {
            Text(String(digit))
                .font(.system(size: 24))
                .foregroundStyle(Color.white)
                .frame(width: keyWidth, height: keyHeight)
                .background(QuickCheckLook.key, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(digit))
        .accessibilityIdentifier("quick-check-key-\(digit)")
    }
}

// MARK: Sending

private struct QuickCheckSending: View {
    let failed: Bool
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            if failed {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 34))
                    .foregroundStyle(QuickCheckLook.orange)
                    .accessibilityHidden(true)
                Text("Couldn't send the check.")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .accessibilityIdentifier("quick-check-send-failed")
                Text("Your results are still here. Try again when you're online.")
                    .font(.system(size: 15))
                    .foregroundStyle(QuickCheckLook.dim)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
                QuickCheckPill(title: "Try again", fill: Color.white, ink: Color.black, action: onRetry)
                    .accessibilityIdentifier("quick-check-retry")
                    .padding(.horizontal, 40)
                    .padding(.top, 8)
            } else {
                ProgressView()
                    .controlSize(.large)
                    .tint(Color.white)
                Text("Sending your check")
                    .font(.system(size: 15))
                    .foregroundStyle(QuickCheckLook.dim)
                    .accessibilityIdentifier("quick-check-sending")
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: Result

private struct QuickCheckResultView: View {
    let result: QuickCheckResult
    /// Nil when the memory part was skipped.
    let bestSpan: Int?
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("QUICK CHECK · DONE")
                            .font(.system(size: 11, weight: .bold))
                            .tracking(0.6)
                            .foregroundStyle(QuickCheckLook.dim)
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: result.valid ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                                .font(.system(size: 26))
                                .foregroundStyle(result.valid ? QuickCheckLook.green : QuickCheckLook.orange)
                                .accessibilityHidden(true)
                            Text(verdict)
                                .font(.system(size: 24, weight: .bold))
                                .foregroundStyle(Color.white)
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityAddTraits(.isHeader)
                                .accessibilityIdentifier("quick-check-verdict")
                        }
                    }
                    stats
                    if result.valid && !result.qualifiesForNormal {
                        Text(Self.normalNote)
                            .font(.system(size: 15))
                            .foregroundStyle(QuickCheckLook.dim)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("quick-check-normal-note")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 26)
                .padding(.top, 24)
            }
            QuickCheckPill(title: "Done", fill: Color.white, ink: Color.black, action: onDone)
                .accessibilityIdentifier("quick-check-done")
                .padding(.horizontal, 22)
                .padding(.bottom, 16)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("quick-check-result")
    }

    private static let normalNote = "This one won't count toward your normal, which uses morning checks taken "
        + "30+ minutes after waking, after 7+ hours of sleep. It still counts for today."

    private var verdict: String {
        if result.valid { return "This check counts" }
        return "This check didn't count: \(Self.reasonText(result.invalidReason))"
    }

    /// The server sends a code, not words.
    static func reasonText(_ code: String?) -> String {
        switch code ?? "" {
        case "not_3_minutes": return "It wasn't the full 3 minutes."
        case "too_few_responses": return "Too few taps were answered."
        case "too_many_false_starts": return "Too many taps came before the green."
        default: return "It didn't meet the rules for a check."
        }
    }

    private var stats: some View {
        VStack(spacing: 0) {
            statRow("Reaction time", detail: "The middle of your answers", value: "\(Int(result.medianRtMs.rounded())) ms")
            QuickCheckLook.divider.frame(height: 0.5)
            statRow("Lapses", detail: "Slow answers, 355 ms or more", value: String(result.lapses))
            QuickCheckLook.divider.frame(height: 0.5)
            statRow("False starts", detail: "Taps before green", value: String(result.falseStarts))
            if let bestSpan {
                QuickCheckLook.divider.frame(height: 0.5)
                statRow("Best span", detail: "The longest row you typed back",
                        value: bestSpan == 0 ? "None" : "\(bestSpan) digits")
            }
        }
        .padding(.horizontal, 18)
        .background(QuickCheckLook.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func statRow(_ title: String, detail: String, value: String) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Color.white)
                Text(detail)
                    .font(.system(size: 13))
                    .foregroundStyle(QuickCheckLook.dim)
            }
            Spacer(minLength: 8)
            Text(value)
                .font(.system(size: 20, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(Color.white)
        }
        .padding(.vertical, 14)
        .accessibilityElement(children: .combine)
    }
}

// MARK: Look

/// Colours sampled from the dark Quick check frames; the rest of the app is light.
private enum QuickCheckLook {
    static let background = Color(hex: 0x0D0D0F)
    static let card = Color(hex: 0x2C2C2E)
    static let key = Color(hex: 0x2C2C2E)
    static let divider = Color(hex: 0x3A3A3C)
    static let dim = Color(hex: 0x8E8E93)
    static let green = Color(hex: 0x30D158)
    static let purple = Color(hex: 0xBF5AF2)
    static let orange = Color(hex: 0xFF9F0A)
}
