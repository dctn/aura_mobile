import AuraKit
import SwiftUI

/// Today: the mood check-in and today's open alerts. The Figma's second Mood card is filler.
struct HomeTodaySection: View {
    @Environment(AppModel.self) private var model
    @State private var alerts: [AlertItem] = []
    /// Skipped or started here. A reload can't bring them back while the backend catches up.
    @State private var handled: Set<String> = []
    @State private var failed = false
    @State private var retry = 0

    private let onStart: (HomeModeRoute) -> Void

    init(onStart: @escaping (HomeModeRoute) -> Void) {
        self.onStart = onStart
    }

    private var loadKey: String { "\(model.dataVersion)-\(retry)" }

    private var openAlerts: [AlertItem] {
        let today = DayKey.string(Date())
        return alerts.filter { alert in
            !alert.done && !handled.contains(alert.id) && alert.date.hasPrefix(today)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SectionTitle("Today")
            HomeMoodCard()
            ForEach(openAlerts) { alert in
                alertCard(alert)
            }
            if failed {
                HomeRetryLine(text: "Couldn't load today's alerts.") { retry += 1 }
            }
        }
        .task(id: loadKey) { await load() }
    }

    private func alertCard(_ alert: AlertItem) -> some View {
        HomeAlertCard(
            alert: alert,
            mode: alert.actionRef.flatMap { model.mode(alertRef: $0) },
            onSkip: { skip(alert) },
            onStart: { mode in start(alert, mode: mode) }
        )
    }

    private func load() async {
        let today = DayKey.string(Date())
        do {
            let loaded = try await model.api.alerts(from: today, to: today)
            alerts = loaded
            failed = false
        } catch {
            if Task.isCancelled { return }
            model.note(error)
            failed = alerts.isEmpty
        }
    }

    /// Hides at once and comes back if the backend refuses.
    private func skip(_ alert: AlertItem) {
        handled.insert(alert.id)
        Task {
            do {
                try await model.api.markAlertDone(id: alert.id)
            } catch {
                model.note(error)
                handled.remove(alert.id)
            }
        }
    }

    private func start(_ alert: AlertItem, mode: Mode) {
        handled.insert(alert.id)
        onStart(HomeModeRoute(modeId: mode.id, headline: alert.title))
        Task {
            do {
                try await model.api.markAlertDone(id: alert.id)
            } catch {
                model.note(error)
            }
        }
    }
}

/// One alert. Without a known mode there's nothing to start, so only Skip shows.
private struct HomeAlertCard: View {
    let alert: AlertItem
    let mode: Mode?
    let onSkip: () -> Void
    let onStart: (Mode) -> Void

    private var word: String { mode?.shelf.rawValue ?? "Reminder" }
    private var symbol: String { mode?.shelf.symbol ?? "bell.fill" }
    private var color: Color { mode?.shelf.color ?? AuraColor.blue }

    var body: some View {
        AuraCard(padding: 18) {
            VStack(alignment: .leading, spacing: 14) {
                header
                Text(alert.title)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 6)
                buttons
            }
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: symbol)
                .font(.system(size: 17))
            Text(word)
                .font(.system(size: 15, weight: .medium))
        }
        .foregroundStyle(color)
        .padding(.horizontal, 6)
        .padding(.top, 4)
        .accessibilityElement(children: .combine)
    }

    private var buttons: some View {
        HStack(spacing: 10) {
            PillButton(title: "Skip", style: .secondary, fills: true, action: onSkip)
                .accessibilityIdentifier("home-alert-skip")
            if let mode {
                PillButton(title: "Start", fills: true, action: { onStart(mode) })
                    .accessibilityIdentifier("home-alert-start")
            }
        }
    }
}

/// The mood slider. Releasing it logs once, in the background, and the page stays usable.
private struct HomeMoodCard: View {
    private enum Status {
        case idle, saving, logged, failed
    }

    @Environment(AppModel.self) private var model
    @State private var value: Double = 3
    /// What was last logged; the start position counts, so nothing logs until the slider moves.
    @State private var lastLogged: Double? = 3
    @State private var editing = false
    @State private var movedWhileEditing = false
    @State private var status: Status = .idle
    @State private var attempt = 0

    private var statusText: String? {
        switch status {
        case .idle: return nil
        case .saving: return "Saving..."
        case .logged: return "Logged. Move it again if that changes."
        case .failed: return "Couldn't log that. Move the slider to try again."
        }
    }

    var body: some View {
        AuraCard {
            VStack(alignment: .leading, spacing: 12) {
                header
                Text("How are you feeling right now?")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.black)
                slider
                if let statusText {
                    Text(statusText)
                        .font(.system(size: 13))
                        .foregroundStyle(Color.gray)
                }
            }
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: "brain.head.profile")
                .font(.system(size: 17))
            Text("Mood")
                .font(.system(size: 15, weight: .medium))
        }
        .foregroundStyle(AuraColor.orange)
        .accessibilityElement(children: .combine)
    }

    private var slider: some View {
        Slider(
            value: $value,
            in: 1...5,
            step: 1,
            label: { Text("Mood") },
            minimumValueLabel: { moodIcon("tortoise.fill") },
            maximumValueLabel: { moodIcon("hare.fill") },
            onEditingChanged: { isEditing in sliderEditing(isEditing) }
        )
        .tint(AuraColor.blue)
        .accessibilityIdentifier("home-mood-slider")
        .onChange(of: value) { _, _ in sliderMoved() }
    }

    private func moodIcon(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: 22))
            .foregroundStyle(Color.gray)
    }

    private func sliderEditing(_ isEditing: Bool) {
        editing = isEditing
        if isEditing {
            movedWhileEditing = false
        } else if movedWhileEditing {
            movedWhileEditing = false
            logMood()
        }
    }

    /// A tap or VoiceOver can change the value outside a drag. This also runs after a drag's last
    /// change, when the release has already logged it, so the comparison stops a second log.
    private func sliderMoved() {
        if editing {
            movedWhileEditing = true
        } else if value != lastLogged {
            logMood()
        }
    }

    private func logMood() {
        lastLogged = value
        status = .saving
        attempt += 1
        let thisAttempt = attempt
        let mood = Int(value.rounded())
        let at = Date()
        Task {
            do {
                try await model.api.logMood(mood, at: at)
                if thisAttempt == attempt { status = .logged }
            } catch {
                model.note(error)
                if thisAttempt == attempt {
                    lastLogged = nil
                    status = .failed
                }
            }
        }
    }
}
