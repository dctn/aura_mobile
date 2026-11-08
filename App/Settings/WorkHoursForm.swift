import AuraKit
import SwiftUI

/// The work and lunch hours the day zones are placed inside, as a sheet. Opened from Calendar and Settings.
struct WorkHoursForm: View {
    /// What's saved now, or nil when nothing is saved yet.
    let saved: WorkSchedule?

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var draft: WorkSchedule
    @State private var saving = false
    @State private var errorText: String?

    private struct WeekDay {
        let number: Int
        let letter: String
        let name: String
    }

    private static let weekDays = [
        WeekDay(number: 1, letter: "M", name: "Monday"),
        WeekDay(number: 2, letter: "T", name: "Tuesday"),
        WeekDay(number: 3, letter: "W", name: "Wednesday"),
        WeekDay(number: 4, letter: "T", name: "Thursday"),
        WeekDay(number: 5, letter: "F", name: "Friday"),
        WeekDay(number: 6, letter: "S", name: "Saturday"),
        WeekDay(number: 7, letter: "S", name: "Sunday"),
    ]
    private static let lunchLengths = [30, 45, 60, 90]
    /// 00:00 to 23:45 in 15-minute steps.
    private static let quarters: [String] = (0..<96).map { WorkSchedule.clock($0 * 15) }

    init(saved: WorkSchedule?) {
        self.saved = saved
        var start = saved ?? WorkSchedule.suggested
        start.workdayStart = Self.tidy(start.workdayStart)
        start.workdayEnd = Self.tidy(start.workdayEnd)
        start.lunchStart = Self.tidy(start.lunchStart)
        _draft = State(initialValue: start)
    }

    /// "9:00" as "09:00", so a saved time matches a choice.
    private static func tidy(_ clock: String) -> String {
        guard let minutes = WorkSchedule.minutes(clock) else { return clock }
        return WorkSchedule.clock(minutes)
    }

    /// The quarter hours, plus a saved time that isn't on one, so the picker can show it.
    private static func timeChoices(including value: String) -> [String] {
        if quarters.contains(value) { return quarters }
        return (quarters + [value]).sorted()
    }

    private static func lunchChoices(including value: Int) -> [Int] {
        if lunchLengths.contains(value) { return lunchLengths }
        return (lunchLengths + [value]).sorted()
    }

    var body: some View {
        AuraPage {
            SectionTitle("Work hours")
                .padding(.top, 8)
            Text("Your calendar is shaded inside these hours, with lunch as your main recovery.")
                .font(.system(size: 15))
                .foregroundStyle(Color.gray)
                .fixedSize(horizontal: false, vertical: true)

            AuraCard(padding: 20) {
                VStack(spacing: 0) {
                    timeRow("Work starts", selection: $draft.workdayStart, identifier: "work-hours-start")
                    Divider()
                    timeRow("Work ends", selection: $draft.workdayEnd, identifier: "work-hours-end")
                    Divider()
                    timeRow("Lunch starts", selection: $draft.lunchStart, identifier: "work-hours-lunch")
                    Divider()
                    lunchLengthRow
                }
            }

            workDaysCard

            if let problem = draft.problem {
                Text(problem)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AuraColor.burnout)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("work-hours-problem")
            }
            if let errorText {
                Text(errorText)
                    .font(.system(size: 15))
                    .foregroundStyle(AuraColor.red)
            }
            buttons
        }
        .interactiveDismissDisabled(saving)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private func timeRow(_ title: String, selection: Binding<String>, identifier: String) -> some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(.black)
                .lineLimit(1)
            Spacer(minLength: 8)
            Picker(title, selection: selection) {
                ForEach(Self.timeChoices(including: selection.wrappedValue), id: \.self) { clock in
                    Text(clock).tag(clock)
                }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .tint(AuraColor.blue)
            .accessibilityIdentifier(identifier)
        }
        .frame(minHeight: 50)
    }

    private var lunchLengthRow: some View {
        HStack(spacing: 8) {
            Text("Lunch length")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(.black)
                .lineLimit(1)
            Spacer(minLength: 8)
            Picker("Lunch length", selection: $draft.lunchMinutes) {
                ForEach(Self.lunchChoices(including: draft.lunchMinutes), id: \.self) { minutes in
                    Text("\(minutes) min").tag(minutes)
                }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .tint(AuraColor.blue)
            .accessibilityIdentifier("work-hours-lunch-length")
        }
        .frame(minHeight: 50)
    }

    private var workDaysCard: some View {
        AuraCard(padding: 20) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Work days")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(.black)
                HStack(spacing: 0) {
                    ForEach(Self.weekDays, id: \.number) { day in
                        dayButton(day)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
        }
    }

    private func dayButton(_ day: WeekDay) -> some View {
        let on = draft.workDays.contains(day.number)
        return Button {
            toggle(day.number)
        } label: {
            Text(day.letter)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(on ? Color.white : Color.gray)
                .frame(width: 38, height: 38)
                .background(on ? AuraColor.blue : AuraColor.track, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(day.name)
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier("work-day-\(day.number)")
    }

    private var buttons: some View {
        HStack(spacing: 10) {
            PillButton(title: saved == nil ? "Later" : "Cancel", style: .secondary, fills: true) {
                dismiss()
            }
            .disabled(saving)
            .accessibilityIdentifier("work-hours-cancel")

            PillButton(title: saving ? "Saving..." : "Save", fills: true) {
                save()
            }
            .disabled(saving || draft.problem != nil)
            .accessibilityIdentifier("work-hours-save")
            .overlay(alignment: .leading) {
                if saving {
                    ProgressView()
                        .tint(.white)
                        .padding(.leading, 18)
                        .accessibilityHidden(true)
                }
            }
        }
    }

    private func toggle(_ day: Int) {
        if draft.workDays.contains(day) {
            draft.workDays.removeAll { $0 == day }
        } else {
            draft.workDays = (draft.workDays + [day]).sorted()
        }
    }

    /// Saves, then makes every screen load again through `dataVersion`, so Calendar and Settings show the new hours.
    private func save() {
        let schedule = draft
        saving = true
        errorText = nil
        Task {
            do {
                try await model.api.setWorkSchedule(schedule)
                saving = false
                model.dataVersion += 1
                dismiss()
            } catch {
                saving = false
                errorText = "Couldn't save. Try again."
                model.note(error)
            }
        }
    }
}
