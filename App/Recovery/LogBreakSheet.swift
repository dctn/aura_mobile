import AuraKit
import SwiftUI

/// "Add a break I took": records a break from today or yesterday that wasn't started in the app.
struct LogBreakSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var breakType: RecoveryType = .walk
    @State private var isYesterday: Bool
    /// Only its hour and minute count; the day comes from Today or Yesterday.
    @State private var startTime: Date
    @State private var minutes = 20
    @State private var saving = false
    @State private var errorText: String? = nil
    @State private var savedText: String? = nil
    /// One id for the sheet, so trying Save again resends the same break instead of adding a second.
    @State private var logId = UUID().uuidString.lowercased()

    private static let typeColumns = [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)]

    init() {
        let now = Date()
        let calendar = Calendar.current
        let start: Date
        if ProcessInfo.processInfo.arguments.contains("--uitesting") {
            // UI tests: 09:15 today, which overlaps the stub's 09:00 to 09:50 focus session, so Save is refused.
            start = calendar.date(bySettingHour: 9, minute: 15, second: 0, of: now) ?? now
        } else {
            start = Self.roundedDown(now.addingTimeInterval(-20 * 60), calendar: calendar)
        }
        _startTime = State(initialValue: start)
        _isYesterday = State(initialValue: !calendar.isDate(start, inSameDayAs: now))
    }

    /// Down to the last 5 minutes, so the picker opens on a tidy time.
    private static func roundedDown(_ date: Date, calendar: Calendar) -> Date {
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        let minute = ((parts.minute ?? 0) / 5) * 5
        return calendar.date(bySettingHour: parts.hour ?? 0, minute: minute, second: 0, of: date) ?? date
    }

    /// The picked time on the picked day.
    private var startedAt: Date {
        let calendar = Calendar.current
        let parts = calendar.dateComponents([.hour, .minute], from: startTime)
        let today = calendar.startOfDay(for: Date())
        let day = isYesterday ? (calendar.date(byAdding: .day, value: -1, to: today) ?? today) : today
        return calendar.date(bySettingHour: parts.hour ?? 0, minute: parts.minute ?? 0, second: 0, of: day) ?? day
    }

    var body: some View {
        AuraPage {
            header
            typeCard
            whenCard
            if let errorText {
                Text(errorText)
                    .font(.system(size: 15))
                    .foregroundStyle(AuraColor.red)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("log-error")
            }
            if let savedText {
                Text(savedText)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AuraColor.green)
                    .accessibilityIdentifier("log-saved")
            }
            buttons
        }
        .interactiveDismissDisabled(saving)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle("Add a break I took")
                .accessibilityIdentifier("log-break-sheet")
            Text("A break from today or yesterday that you didn't start here. It counts towards your capacity.")
                .font(.system(size: 15))
                .foregroundStyle(Color.gray)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 8)
    }

    private var typeCard: some View {
        AuraCard(padding: 20) {
            VStack(alignment: .leading, spacing: 14) {
                Text("What you did")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Color.black)
                LazyVGrid(columns: Self.typeColumns, alignment: .leading, spacing: 8) {
                    ForEach(RecoveryType.allCases) { choice in
                        typeChip(choice)
                    }
                }
            }
        }
    }

    private func typeChip(_ choice: RecoveryType) -> some View {
        let isSelected = choice == breakType
        return Button {
            breakType = choice
            changed()
        } label: {
            Text(choice.label)
                .font(.system(size: 15, weight: isSelected ? .semibold : .medium))
                .foregroundStyle(isSelected ? Color.white : Color.black)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.horizontal, 12)
                .frame(maxWidth: .infinity, minHeight: 38)
                .background(isSelected ? AuraColor.blue : AuraColor.track, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("log-type-\(choice.rawValue)")
    }

    private var whenCard: some View {
        AuraCard(padding: 20) {
            VStack(spacing: 0) {
                dayRow
                Divider()
                startRow
                Divider()
                minutesRow
            }
        }
    }

    private var dayRow: some View {
        HStack(spacing: 8) {
            dayChip("Today", yesterday: false, identifier: "log-day-today")
            dayChip("Yesterday", yesterday: true, identifier: "log-day-yesterday")
            Spacer(minLength: 0)
        }
        .frame(minHeight: 54)
    }

    private func dayChip(_ title: String, yesterday: Bool, identifier: String) -> some View {
        let isSelected = isYesterday == yesterday
        return Button {
            isYesterday = yesterday
            changed()
        } label: {
            Text(title)
                .font(.system(size: 15, weight: isSelected ? .semibold : .medium))
                .foregroundStyle(isSelected ? Color.white : Color.black)
                .lineLimit(1)
                .fixedSize()
                .padding(.horizontal, 16)
                .frame(height: 38)
                .background(isSelected ? AuraColor.blue : AuraColor.track, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier(identifier)
    }

    private var startRow: some View {
        HStack(spacing: 8) {
            Text("Started")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Color.black)
            Spacer(minLength: 8)
            DatePicker("Started", selection: $startTime, displayedComponents: .hourAndMinute)
                .labelsHidden()
                .tint(AuraColor.blue)
                .accessibilityIdentifier("log-start-time")
        }
        .frame(minHeight: 54)
        .onChange(of: startTime) { _, _ in changed() }
    }

    private var minutesRow: some View {
        Stepper(value: $minutes, in: 10...240, step: 5) {
            HStack(spacing: 8) {
                Text("How long")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Color.black)
                Spacer(minLength: 8)
                Text("\(minutes) min")
                    .font(.system(size: 17))
                    .monospacedDigit()
                    .foregroundStyle(Color.black)
            }
        }
        .accessibilityIdentifier("log-minutes")
        .frame(minHeight: 54)
        .onChange(of: minutes) { _, _ in changed() }
    }

    private var buttons: some View {
        HStack(spacing: 10) {
            PillButton(title: savedText == nil ? "Cancel" : "Done", style: .secondary, fills: true) {
                dismiss()
            }
            .disabled(saving)
            .accessibilityIdentifier("log-cancel")

            PillButton(title: saving ? "Saving..." : "Save", fills: true) {
                save()
            }
            .disabled(saving || savedText != nil)
            .accessibilityIdentifier("log-save")
        }
    }

    /// An edit after a refusal clears the message, so the next Save is judged afresh.
    private func changed() {
        errorText = nil
    }

    /// Saves, reloads the screens through `dataVersion`, then closes after a moment so "Break added." can be read.
    private func save() {
        let id = logId
        let picked = breakType
        let start = startedAt
        let length = minutes
        saving = true
        errorText = nil
        Task {
            do {
                let logged = try await model.api.logBreak(id: id, type: picked, startedAt: start, minutes: length)
                saving = false
                savedText = logged.created ? "Break added." : "Already added."
                model.dataVersion += 1
                try? await Task.sleep(for: .milliseconds(1500))
                dismiss()
            } catch {
                saving = false
                errorText = Self.message(for: error)
                model.note(error)
            }
        }
    }

    /// The backend's refusals in plain words.
    static func message(for error: Error) -> String {
        switch (error as? AuraError)?.serverCode {
        case "log_too_old": return "That's too long ago: breaks from today or yesterday only."
        case "log_in_future": return "That break hasn't ended yet."
        case "log_overlaps": return "That overlaps a session you already recorded."
        default: return "Couldn't save. Try again."
        }
    }
}
