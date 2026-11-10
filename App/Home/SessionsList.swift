import AuraKit
import SwiftUI

/// Home → Work: yesterday's and today's sessions, newest first. A finished focus session asks how hard it was,
/// and the answer sets what it cost the day score.
struct SessionsList: View {
    @Environment(AppModel.self) private var model
    @State private var sessions: [SessionRecord]?
    @State private var failed = false
    @State private var retry = 0
    @State private var rating: SessionRecord?

    private var loadKey: String { "\(model.dataVersion)-\(retry)" }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Sessions")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.black)
                .accessibilityAddTraits(.isHeader)
            AuraCard(padding: 20) {
                content
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sessions-list")
        .task(id: loadKey) { await load() }
        .sheet(item: $rating) { session in
            SessionRatingSheet(session: session) { updated in
                replace(updated)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if failed {
            HomeRetryLine(text: "Couldn't load your sessions.") { retry += 1 }
        } else if let sessions {
            if sessions.isEmpty {
                Text("No sessions yesterday or today")
                    .font(.system(size: 15))
                    .foregroundStyle(AuraColor.footnote)
            } else {
                rows(sessions)
            }
        } else {
            ProgressView()
                .frame(maxWidth: .infinity)
        }
    }

    private func rows(_ items: [SessionRecord]) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, session in
                if index > 0 { Divider() }
                SessionRow(session: session) {
                    rating = session
                }
            }
        }
    }

    private func replace(_ updated: SessionRecord) {
        guard var list = sessions, let index = list.firstIndex(where: { $0.id == updated.id }) else { return }
        var merged = updated
        // The answer may leave out what the list already knew.
        if merged.title == nil { merged.title = list[index].title }
        if merged.startedAt == nil { merged.startedAt = list[index].startedAt }
        list[index] = merged
        sessions = list
    }

    private func load() async {
        let now = Date()
        let yesterday = DayKey.calendar.date(byAdding: .day, value: -1, to: now) ?? now
        do {
            let loaded = try await model.api.sessions(from: DayKey.string(yesterday), to: DayKey.string(now))
            sessions = loaded
            failed = false
        } catch {
            if Task.isCancelled { return }
            model.note(error)
            failed = sessions == nil
        }
    }
}

/// One session: its time and length, its title, how it ended, then its rating or "Rate it".
private struct SessionRow: View {
    let session: SessionRecord
    let onRate: () -> Void

    /// A focus session that has ended can be rated; the backend refuses one still running.
    private var canRate: Bool {
        session.isFocus && (session.endedAt != nil || session.outcome != nil)
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                Text(SessionText.when(session))
                    .font(.system(size: 11))
                    .foregroundStyle(Color.gray)
                Text(SessionText.title(session))
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.black)
                if let outcome = SessionText.outcome(session) {
                    Text(outcome)
                        .font(.system(size: 13))
                        .foregroundStyle(Color.gray)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 8)
            trailing
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("session-row-\(session.id)")
    }

    @ViewBuilder
    private var trailing: some View {
        if let difficulty = session.difficulty, session.isFocus {
            Text(difficulty.label)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(AuraColor.blue)
                .padding(.horizontal, 12)
                .frame(height: 30)
                .background(AuraColor.background, in: Capsule())
                .accessibilityLabel(Text("Rated \(difficulty.label)"))
                .accessibilityIdentifier("session-rating-\(session.id)")
        } else if canRate {
            Button(action: onRate) {
                Text("Rate it")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 14)
                    .frame(height: 34)
                    .background(AuraColor.blue, in: Capsule())
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("session-rate-\(session.id)")
        }
    }
}

/// "How hard was this for you?" and the five chips. Picking one saves it and closes the sheet.
private struct SessionRatingSheet: View {
    let session: SessionRecord
    let onRated: (SessionRecord) -> Void

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var picked: Difficulty?
    @State private var saving = false
    @State private var errorText: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("How hard was this for you?")
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(.black)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text("\(SessionText.title(session)), \(SessionText.when(session))")
                .font(.system(size: 15))
                .foregroundStyle(Color.gray)
            DifficultyPicker(selected: picked ?? session.difficulty) { difficulty in
                rate(difficulty)
            }
            .disabled(saving)
            Text("Your answer sets what this session cost your day score.")
                .font(.system(size: 13))
                .foregroundStyle(Color.gray)
                .fixedSize(horizontal: false, vertical: true)
            if let errorText {
                Text(errorText)
                    .font(.system(size: 15))
                    .foregroundStyle(AuraColor.red)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 22)
        .padding(.top, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(AuraColor.background)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("session-rating-sheet")
        .interactiveDismissDisabled(saving)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    private func rate(_ difficulty: Difficulty) {
        guard !saving else { return }
        picked = difficulty
        saving = true
        errorText = nil
        let id = session.id
        Task {
            do {
                let updated = try await model.api.setDifficulty(sessionId: id, difficulty)
                onRated(updated)
                dismiss()
            } catch {
                model.note(error)
                errorText = Self.message(for: error)
                saving = false
            }
        }
    }

    private static func message(for error: Error) -> String {
        switch (error as? AuraError)?.serverCode ?? "" {
        case "session_not_ended": return "This session is still running. Rate it once it ends."
        case "not_focus_session": return "Only focus sessions are rated."
        default: return "Couldn't save that. Try again."
        }
    }
}

/// The words a session row shows.
enum SessionText {
    /// Its title, else its break type, else its kind.
    static func title(_ session: SessionRecord) -> String {
        if let title = session.title, !title.isEmpty { return title }
        if let type = session.recoveryType { return type.label }
        switch session.kind {
        case "focus": return "Focus"
        case "recovery": return "Break"
        case "meeting": return "Meeting"
        default: return session.kind.capitalized
        }
    }

    /// "09:00 · 50 min", or "Yesterday 15:00 · 20 min".
    static func when(_ session: SessionRecord) -> String {
        let length = ZoneText.hours(session.minutes)
        guard let start = session.startedAt else { return length }
        let day = Calendar.current.isDateInYesterday(start) ? "Yesterday " : ""
        return "\(day)\(ZoneText.clock(start)) · \(length)"
    }

    /// "Completed", or "Rest (no screen) · logged" for a break logged afterwards.
    static func outcome(_ session: SessionRecord) -> String? {
        if session.outcome == "logged" {
            guard let type = session.recoveryType else { return "Logged" }
            return "\(type.label) · logged"
        }
        let word: String?
        switch session.outcome ?? "" {
        case "completed": word = "Completed"
        case "stopped": word = "Stopped early"
        case "pending": word = "Not finished"
        case "rescheduled": word = "Rescheduled"
        default: word = nil
        }
        // A break that ran keeps its type in view when it has its own title.
        if let type = session.recoveryType, let title = session.title, !title.isEmpty {
            guard let word else { return type.label }
            return "\(type.label) · \(word)"
        }
        return word
    }
}
