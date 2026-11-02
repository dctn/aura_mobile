import AuraKit
import SwiftUI

/// Frames iPhone 18 Pro - 6 and - 7. `headline` is the Now card's title when opened from one, which also gives the
/// header that card's colours; otherwise the header takes the shelf's colour.
struct ModeDetailView: View {
    @Environment(AppModel.self) private var model
    let mode: Mode
    let headline: String?

    @State private var minutes: Int
    @State private var sessionTitle = ""
    @State private var calendarState = ModeCalendarState.idle

    init(mode: Mode, headline: String? = nil) {
        self.mode = mode
        self.headline = headline
        _minutes = State(initialValue: mode.minutes)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                heading
                actions.padding(.top, 12)
                if let line = calendarState.line {
                    Text(line)
                        .font(.system(size: 15))
                        .foregroundStyle(ModeDetailLook.grey)
                        .padding(.top, 10)
                }
                Text(mode.note)
                    .font(.system(size: 16))
                    .foregroundStyle(ModeDetailLook.grey)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 8)
                    .padding(.top, 14)
                rows
                if let evidence = mode.evidence {
                    ModeWhyCard(evidence: evidence).padding(.top, 15)
                }
            }
            .padding(.horizontal, 22)
            .padding(.top, 94)
            .padding(.bottom, 24)
        }
        .background {
            ModeDetailBackdrop(mode: mode, fromNowCard: headline != nil)
        }
        .toolbarBackground(.hidden, for: .navigationBar)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var heading: some View {
        VStack(spacing: 15) {
            Image(systemName: mode.shelf.symbol)
                .font(.system(size: 36))
                .foregroundStyle(mode.shelf.color)
                .accessibilityHidden(true)
            Text(headline ?? mode.name)
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(.black)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 200)
                .accessibilityAddTraits(.isHeader)
        }
    }

    private var actions: some View {
        HStack(spacing: 8) {
            lengthPill
            actionPill
        }
        .fixedSize(horizontal: false, vertical: true)
        .controlSize(.large)
    }

    @ViewBuilder
    private var lengthPill: some View {
        if let choices = mode.choices, !choices.isEmpty {
            Menu {
                Picker("Length", selection: $minutes) {
                    ForEach(choices, id: \.self) { choice in
                        Text(Mode.lengthText(choice)).tag(choice)
                    }
                }
            } label: {
                lengthLabel
            }
            .accessibilityLabel("Length")
            .accessibilityValue(Mode.lengthText(minutes))
            .accessibilityIdentifier("mode-length")
        } else {
            lengthLabel
        }
    }

    private var lengthLabel: some View {
        Text(Mode.lengthText(minutes))
            .font(.system(size: 19, weight: .medium))
            .foregroundStyle(.black)
            .padding(.horizontal, 20)
            .frame(maxHeight: .infinity)
            .glassEffect(.regular.interactive(), in: Capsule())
    }

    @ViewBuilder
    private var actionPill: some View {
        if mode.run == .plan {
            PillButton(title: "Add to calendar") {
                Task { await addToCalendar() }
            }
            .disabled(calendarState == .adding)
            .accessibilityIdentifier("mode-add-to-calendar")
        } else {
            PillButton(title: "Start") {
                model.run.start(mode: mode, minutes: minutes, title: sessionTitle)
            }
            .accessibilityIdentifier("mode-start")
        }
    }

    /// Frame 01's rows: the breath pattern, and a title on the Focus shelf.
    @ViewBuilder
    private var rows: some View {
        if mode.run == .breathe, let pattern = mode.pattern {
            ModeDetailRow(title: "Breath") {
                Text(pattern.line)
                    .font(.system(size: 15))
                    .foregroundStyle(ModeDetailLook.grey)
            }
            .padding(.top, 15)
        }
        if mode.shelf == .focus {
            ModeDetailRow(title: "Title") {
                TextField("Optional", text: $sessionTitle)
                    .font(.system(size: 15))
                    .multilineTextAlignment(.trailing)
                    .accessibilityLabel("Title")
                    .accessibilityIdentifier("mode-title")
            }
            .padding(.top, 15)
        }
    }

    /// Frame 15: a plan mode goes on the calendar. The same title on that day already counts.
    private func addToCalendar() async {
        calendarState = .adding
        let slot = mode.planSlot(now: Date())
        do {
            let existing = try await model.api.events(from: slot.start, to: slot.end)
            if existing.contains(where: { $0.title == mode.name }) {
                calendarState = .alreadyThere
                return
            }
            try await model.api.addEvent(title: mode.name, start: slot.start, end: slot.end, kind: mode.kind)
            calendarState = .added
        } catch {
            model.note(error)
            calendarState = .failed
        }
    }
}

private enum ModeCalendarState: Equatable {
    case idle, adding, added, alreadyThere, failed

    var line: String? {
        switch self {
        case .idle, .adding: return nil
        case .added: return "Added to your calendar."
        case .alreadyThere: return "Already in your calendar."
        case .failed: return "Couldn't add it. Try again."
        }
    }
}

private enum ModeDetailLook {
    static let grey = Color(hex: 0x9E9E9F)
}

/// The soft header fading into the page, measured from frames - 6 and - 7.
private struct ModeDetailBackdrop: View {
    let mode: Mode
    let fromNowCard: Bool

    private var fade: LinearGradient {
        return LinearGradient(
            stops: [
                Gradient.Stop(color: .black, location: 0),
                Gradient.Stop(color: .black.opacity(0.66), location: 0.2),
                Gradient.Stop(color: .black.opacity(0.48), location: 0.33),
                Gradient.Stop(color: .black.opacity(0.2), location: 0.6),
                Gradient.Stop(color: .black.opacity(0.1), location: 0.73),
                Gradient.Stop(color: .clear, location: 1),
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    var body: some View {
        ZStack(alignment: .top) {
            AuraColor.background
            headerTint
                .frame(height: 300)
                .mask { fade }
        }
        .ignoresSafeArea()
    }

    @ViewBuilder
    private var headerTint: some View {
        if fromNowCard {
            LinearGradient(colors: RecoveryCardStyle(mode: mode).headerColors, startPoint: .leading, endPoint: .trailing)
        } else {
            mode.shelf.color
        }
    }
}

private struct ModeWhyCard: View {
    let evidence: String

    var body: some View {
        AuraCard(padding: 22) {
            VStack(alignment: .leading, spacing: 12) {
                Text("Why this Works")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(.black)
                Text(evidence)
                    .font(.system(size: 16))
                    .lineSpacing(4)
                    .foregroundStyle(ModeDetailLook.grey)
            }
        }
    }
}

private struct ModeDetailRow<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: Trailing

    var body: some View {
        AuraCard(padding: 18) {
            HStack(spacing: 12) {
                Text(title)
                    .font(.system(size: 15))
                    .foregroundStyle(.black)
                Spacer(minLength: 8)
                trailing
            }
        }
    }
}
