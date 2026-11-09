import AuraKit
import SwiftUI

/// Find a time, as a sheet opened from Calendar. A row of day chips picks today or one of the next six days; the
/// sheet first shows a preview of that day's plan, which writes nothing, and "Add to calendar" runs it for real.
struct FindATimeSheet: View {
    /// The day the sheet opens on, as a `DayKey`. The day row can change it.
    let date: String
    /// Called once the plan is on the calendar, just before the sheet closes.
    let onConfirmed: () -> Void

    private enum Phase {
        case loading
        case preview(DayPlan)
        /// The Planner wants today's Quick check first (409 `quick_check_required`).
        case needsCheck
        case failed
    }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var phase: Phase = .loading
    /// Task titles by id, for the rows. Empty when the tasks couldn't load; the rows then say "Task".
    @State private var titles: [String: String] = [:]
    /// Sent with the preview and again with the confirm, so both plan the same way.
    @State private var skipCheck = false
    /// Bumped to run the preview again.
    @State private var attempt = 0
    @State private var confirming = false
    @State private var confirmError: String?
    @State private var showingQuickCheck = false
    /// The day being previewed and confirmed, as a `DayKey`.
    @State private var selectedDate: String

    private static let quickCheckCode = "quick_check_required"
    /// Today plus the next six days.
    private static let dayCount = 7

    /// One chip in the day row.
    private struct DayChoice: Identifiable {
        /// Days after today: 0 is today.
        let offset: Int
        /// The `DayKey` sent to the Planner.
        let key: String
        /// Midnight of the day, for the labels.
        let day: Date
        var id: Int { offset }
    }

    init(date: String, onConfirmed: @escaping () -> Void) {
        self.date = date
        self.onConfirmed = onConfirmed
        _selectedDate = State(initialValue: date)
    }

    /// Today and the next six days, keyed the way the backend reads dates.
    private var dayChoices: [DayChoice] {
        let calendar = DayKey.calendar
        let today = calendar.startOfDay(for: Date())
        return (0..<Self.dayCount).map { offset in
            let day = calendar.date(byAdding: .day, value: offset, to: today) ?? today
            return DayChoice(offset: offset, key: DayKey.string(day, calendar: calendar), day: day)
        }
    }

    /// Days after today for the selected day, or nil when it isn't in the row.
    private var selectedOffset: Int? {
        dayChoices.first { $0.key == selectedDate }?.offset
    }

    /// "today", "tomorrow" or the long date ("Wednesday, 11 Nov" in the phone's own words), for the sentences.
    private var selectedDayPhrase: String {
        switch selectedOffset {
        case 0?: return "today"
        case 1?: return "tomorrow"
        default:
            guard let day = DayKey.date(selectedDate) else { return selectedDate }
            return day.formatted(Self.longDayStyle)
        }
    }

    /// The labels' calendar: Gregorian in the phone's time zone, like `DayKey`, but in the phone's language.
    private static var labelCalendar: Calendar {
        var calendar = DayKey.calendar
        calendar.locale = Locale.current
        return calendar
    }

    /// "Wed 11", or the phone's own order and words.
    private static var chipStyle: Date.FormatStyle {
        Date.FormatStyle(locale: Locale.current, calendar: labelCalendar, timeZone: TimeZone.current)
            .weekday(.abbreviated)
            .day()
    }

    /// "Wednesday, 11 Nov", or the phone's own order and words.
    private static var longDayStyle: Date.FormatStyle {
        Date.FormatStyle(locale: Locale.current, calendar: labelCalendar, timeZone: TimeZone.current)
            .weekday(.wide)
            .day()
            .month(.abbreviated)
    }

    private var needsCheck: Bool {
        if case .needsCheck = phase { return true }
        return false
    }

    private var canConfirm: Bool {
        guard !confirming, case .preview(let plan) = phase else { return false }
        return !plan.placed.isEmpty
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    dayRow
                    content
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 22)
                .padding(.vertical, 12)
            }
            .background(AuraColor.background)
            .safeAreaInset(edge: .bottom) {
                bottomBar
                    .padding(.horizontal, 22)
                    .padding(.top, 10)
                    .padding(.bottom, 12)
                    .frame(maxWidth: .infinity)
                    .background(AuraColor.background)
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .task(id: "\(selectedDate)-\(attempt)") { await loadPreview() }
        .fullScreenCover(isPresented: $showingQuickCheck) {
            QuickCheckScreen { result in quickCheckEnded(result) }
        }
        .interactiveDismissDisabled(confirming)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle("Find a time")
                .accessibilityIdentifier("find-time-sheet")
            Text("A preview of \(selectedDayPhrase). Nothing is added until you confirm.")
                .font(.system(size: 15))
                .foregroundStyle(Color.gray)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 8)
    }

    // MARK: Day row

    /// Today, Tomorrow, then "Wed 11" and on: seven chips that scroll sideways when they don't fit. It stays above the
    /// Quick check panel too, so another day can be picked from there.
    private var dayRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(dayChoices) { choice in
                    dayChip(choice)
                }
            }
            .padding(.horizontal, 22)
            .padding(.vertical, 2)
        }
        // Runs edge to edge, past the page's side margins.
        .padding(.horizontal, -22)
    }

    private func dayChip(_ choice: DayChoice) -> some View {
        let selected = choice.key == selectedDate
        return Button {
            select(choice.key)
        } label: {
            Text(chipTitle(choice))
                .font(.system(size: 15, weight: selected ? .semibold : .medium))
                .foregroundStyle(selected ? Color.white : Color.black)
                .lineLimit(1)
                .fixedSize()
                .padding(.horizontal, 16)
                .frame(height: 38)
                .background(selected ? AuraColor.blue : AuraColor.card, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(confirming)
        .accessibilityLabel(Text(chipSpokenTitle(choice)))
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("find-time-day-\(choice.offset)")
    }

    private func chipTitle(_ choice: DayChoice) -> String {
        switch choice.offset {
        case 0: return "Today"
        case 1: return "Tomorrow"
        default: return choice.day.formatted(Self.chipStyle)
        }
    }

    /// VoiceOver reads the whole date for the later days.
    private func chipSpokenTitle(_ choice: DayChoice) -> String {
        choice.offset < 2 ? chipTitle(choice) : choice.day.formatted(Self.longDayStyle)
    }

    /// A new day runs its own preview. The old one goes at once, so "Add to calendar" can't confirm it for the new day.
    private func select(_ key: String) {
        guard key != selectedDate, !confirming else { return }
        selectedDate = key
        confirmError = nil
        phase = .loading
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.top, 24)
        case .preview(let plan):
            preview(plan)
        case .needsCheck:
            checkPanel
        case .failed:
            AuraCard(padding: 20) {
                HomeRetryLine(text: "Couldn't plan your day.") { attempt += 1 }
            }
        }
    }

    // MARK: Preview

    @ViewBuilder
    private func preview(_ plan: DayPlan) -> some View {
        let blocks = plan.placed.sorted { $0.start < $1.start }
        if blocks.isEmpty {
            Text(emptyText)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.black)
                .accessibilityIdentifier("find-time-empty")
        } else {
            AuraCard(padding: 14) {
                VStack(spacing: 10) {
                    ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                        blockRow(block)
                    }
                }
            }
        }
        if let note = plan.warnings.note {
            warningCard(note)
        }
        if !plan.unplaced.isEmpty {
            unplacedSection(plan.unplaced)
        }
    }

    /// "Nothing to place today.", "... tomorrow." or "... on Wednesday, 11 Nov."
    private var emptyText: String {
        let phrase = selectedDayPhrase
        let needsOn = selectedOffset.map { $0 > 1 } ?? true
        return needsOn ? "Nothing to place on \(phrase)." : "Nothing to place \(phrase)."
    }

    /// One block: time, title and the Planner's reason, on a blue tint for work or green for a rest.
    private func blockRow(_ block: PlanBlock) -> some View {
        let color = block.isRecovery ? AuraColor.green : AuraColor.blue
        return VStack(alignment: .leading, spacing: 2) {
            Text(ZoneText.range(block.start, block.end))
                .font(.system(size: 13).monospacedDigit())
                .foregroundStyle(Color.gray)
            Text(block.title(taskTitle: title(for: block.taskId)))
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(.black)
                .fixedSize(horizontal: false, vertical: true)
            if block.isRecovery {
                Text("Rest, not work")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(AuraColor.green)
            }
            if !block.reason.isEmpty {
                Text(block.reason)
                    .font(.system(size: 13))
                    .foregroundStyle(Color.gray)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 12)
        .padding(.leading, 16)
        .padding(.trailing, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color.opacity(0.12))
        .overlay(alignment: .leading) {
            Rectangle()
                .fill(color)
                .frame(width: 4)
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("find-time-block")
    }

    private func warningCard(_ note: String) -> some View {
        AuraCard(padding: 20) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "info.circle.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(AuraColor.blue)
                    .accessibilityHidden(true)
                Text(note)
                    .font(.system(size: 15))
                    .foregroundStyle(.black)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("find-time-warning")
    }

    private func unplacedSection(_ tasks: [UnplacedTask]) -> some View {
        ZoneCardSection(title: "Didn't fit") {
            ForEach(Array(tasks.enumerated()), id: \.offset) { index, task in
                if index > 0 { Divider() }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title(for: task.taskId))
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(.black)
                    Text(task.detail)
                        .font(.system(size: 13))
                        .foregroundStyle(Color.gray)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("find-time-unplaced")
    }

    // MARK: Quick check

    private var checkPanel: some View {
        AuraCard(padding: 20) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Take today's Quick check first")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.black)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("find-time-check")
                Text("Planning today uses this morning's Quick check, the 3-minute tap test. You can do it now, or plan without it.")
                    .font(.system(size: 15))
                    .foregroundStyle(Color.gray)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: Buttons

    @ViewBuilder
    private var bottomBar: some View {
        if needsCheck {
            checkButtons
        } else {
            VStack(spacing: 10) {
                if let confirmError {
                    Text(confirmError)
                        .font(.system(size: 15))
                        .foregroundStyle(AuraColor.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("find-time-error")
                }
                HStack(spacing: 10) {
                    PillButton(title: "Cancel", style: .secondary, fills: true) {
                        dismiss()
                    }
                    .disabled(confirming)
                    .accessibilityIdentifier("find-time-cancel")

                    PillButton(title: confirming ? "Adding..." : "Add to calendar", fills: true) {
                        confirm()
                    }
                    .disabled(!canConfirm)
                    .accessibilityIdentifier("find-time-confirm")
                }
            }
        }
    }

    private var checkButtons: some View {
        VStack(spacing: 10) {
            PillButton(title: "Do the Quick check", fills: true) {
                showingQuickCheck = true
            }
            .accessibilityIdentifier("find-time-do-check")

            PillButton(title: "Plan anyway", style: .secondary, fills: true) {
                planAnyway()
            }
            .accessibilityIdentifier("find-time-plan-anyway")

            Button {
                dismiss()
            } label: {
                Text("Not now")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(AuraColor.blue)
                    .frame(maxWidth: .infinity, minHeight: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("find-time-not-now")
        }
    }

    // MARK: Loading

    private func title(for taskId: String?) -> String {
        guard let taskId, let title = titles[taskId], !title.isEmpty else { return "Task" }
        return title
    }

    /// The tasks (for titles) and a dry run load together. Only the dry run can fail the sheet.
    private func loadPreview() async {
        phase = .loading
        confirmError = nil
        let api = model.api
        let day = selectedDate
        let skip = skipCheck

        async let tasksCall = api.tasks()
        async let planCall = api.allocate(date: day, dryRun: true, skipCheck: skip, taskIds: nil)

        let loadedTasks = try? await tasksCall
        let planResult: Result<DayPlan, Error>
        do { planResult = .success(try await planCall) } catch { planResult = .failure(error) }

        if Task.isCancelled { return }

        if let loadedTasks {
            titles = Dictionary(loadedTasks.map { ($0.id, $0.title) }, uniquingKeysWith: { first, _ in first })
        }
        switch planResult {
        case .success(let plan):
            phase = .preview(plan)
        case .failure(let error):
            show(error)
        }
    }

    /// The Quick check answer opens the check panel; anything else is "Couldn't plan your day."
    private func show(_ error: Error) {
        if (error as? AuraError)?.serverCode == Self.quickCheckCode {
            phase = .needsCheck
            return
        }
        model.note(error)
        phase = .failed
    }

    /// A check that counts lets the Planner use it, so the preview runs again with it. Stopping early or a check
    /// that didn't count leaves the Quick check panel as it was.
    private func quickCheckEnded(_ result: QuickCheckResult?) {
        guard let result, result.valid else { return }
        skipCheck = false
        attempt += 1
    }

    /// Plans without the Quick check, and keeps that for the confirm.
    private func planAnyway() {
        skipCheck = true
        attempt += 1
    }

    /// The real run for the selected day, with the same `skipCheck` as the preview. The sheet stays open if it fails.
    private func confirm() {
        let api = model.api
        let day = selectedDate
        let skip = skipCheck
        confirming = true
        confirmError = nil
        Task {
            do {
                _ = try await api.allocate(date: day, dryRun: false, skipCheck: skip, taskIds: nil)
                confirming = false
                onConfirmed()
                dismiss()
            } catch {
                confirming = false
                if (error as? AuraError)?.serverCode == Self.quickCheckCode {
                    phase = .needsCheck
                } else {
                    model.note(error)
                    confirmError = "Couldn't add the plan. Try again."
                }
            }
        }
    }
}
