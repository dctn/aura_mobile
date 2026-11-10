import AuraKit
import SwiftUI

/// The morning check-in at the top of Today, until it's sent: how alert you feel and, unless Apple Health already
/// sent last night, when you fell asleep and woke. Sending it shows a thank-you until Home is next opened.
struct MorningCheckinCard: View {
    /// Tells Today the card was sent here, so it keeps showing the thank-you.
    let onSent: () -> Void

    @Environment(AppModel.self) private var model
    @State private var alertness: Double = 5
    @State private var fellAsleep = MorningCheckinCard.clockToday(hour: 23, minute: 0)
    @State private var wokeUp = MorningCheckinCard.clockToday(hour: 7, minute: 0)
    /// The profile's times fill the pickers once, so coming back to Home doesn't undo an edit.
    @State private var scheduleLoaded = false
    @State private var sending = false
    @State private var sent = false
    @State private var connecting = false
    @State private var errorText: String?

    /// Apple Health sent last night, so the times aren't asked.
    private var asksTimes: Bool { !model.sleepSentToday }
    private var offersAppleHealth: Bool { model.sleepSource.isAvailable && !model.appleHealthConnected }

    var body: some View {
        AuraCard {
            VStack(alignment: .leading, spacing: 12) {
                header
                if sent {
                    Text("Thanks — morning check-in sent.")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(.black)
                        .accessibilityIdentifier("checkin-done")
                } else {
                    form
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("morning-checkin")
        .task { await loadSchedule() }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: "sun.max.fill")
                .font(.system(size: 17))
            Text("Morning")
                .font(.system(size: 15, weight: .medium))
        }
        .foregroundStyle(AuraColor.orange)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var form: some View {
        alertnessPart
        if asksTimes {
            VStack(spacing: 0) {
                timeRow("When did you fall asleep?", selection: $fellAsleep, identifier: "checkin-fell-asleep")
                Divider()
                timeRow("When did you wake up?", selection: $wokeUp, identifier: "checkin-woke")
            }
        }
        if offersAppleHealth {
            appleHealthButton
        }
        if let errorText {
            Text(errorText)
                .font(.system(size: 15))
                .foregroundStyle(AuraColor.red)
                .fixedSize(horizontal: false, vertical: true)
        }
        PillButton(title: sending ? "Sending..." : "Send", fills: true) {
            send()
        }
        .disabled(sending)
        .accessibilityIdentifier("checkin-send")
    }

    private var alertnessPart: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("How alert do you feel?")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.black)
                Spacer(minLength: 8)
                Text("\(Int(alertness.rounded())) of 9")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AuraColor.blue)
            }
            Slider(
                value: $alertness,
                in: 1...9,
                step: 1,
                label: { Text("Alertness") },
                minimumValueLabel: { scaleLabel("1") },
                maximumValueLabel: { scaleLabel("9") }
            )
            .tint(AuraColor.blue)
            .accessibilityIdentifier("checkin-alertness")
        }
    }

    private func scaleLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(Color.gray)
    }

    private func timeRow(_ title: String, selection: Binding<Date>, identifier: String) -> some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(.black)
                .lineLimit(2)
            Spacer(minLength: 8)
            DatePicker(title, selection: selection, displayedComponents: .hourAndMinute)
                .labelsHidden()
                .tint(AuraColor.blue)
                .accessibilityIdentifier(identifier)
        }
        .frame(minHeight: 50)
    }

    private var appleHealthButton: some View {
        Button {
            connect()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "heart.text.square.fill")
                    .foregroundStyle(AuraColor.red)
                Text(connecting ? "Opening Apple Health..." : "Use Apple Health for sleep")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AuraColor.blue)
            }
        }
        .buttonStyle(.plain)
        .disabled(connecting)
        .accessibilityIdentifier("checkin-apple-health")
    }

    /// The profile's usual bedtime and wake time fill the pickers; without them they stay at 23:00 and 07:00.
    private func loadSchedule() async {
        guard !scheduleLoaded else { return }
        do {
            let schedule = try await model.api.sleepSchedule()
            scheduleLoaded = true
            guard let schedule else { return }
            if let bed = Self.clockToday(schedule.bedtime) { fellAsleep = bed }
            if let wake = Self.clockToday(schedule.wakeTime) { wokeUp = wake }
        } catch {
            if Task.isCancelled { return }
            model.note(error)
        }
    }

    private func send() {
        guard !sending else { return }
        sending = true
        errorText = nil
        let level = Int(alertness.rounded())
        let times = asksTimes
        let asleepAt: String? = times ? ZoneText.clock(fellAsleep) : nil
        let wokeAt: String? = times ? ZoneText.clock(wokeUp) : nil
        Task {
            do {
                try await model.api.morningCheckin(alertness: level, fellAsleepAt: asleepAt, wokeAt: wokeAt, at: Date())
                sent = true
                onSent()
                model.markMorningCheckinSent()
            } catch {
                model.note(error)
                errorText = "Couldn't send that. Try again."
            }
            sending = false
        }
    }

    private func connect() {
        guard !connecting else { return }
        connecting = true
        errorText = nil
        Task {
            do {
                try await model.connectAppleHealth()
            } catch {
                errorText = "Couldn't open Apple Health. Enter your times instead."
            }
            connecting = false
        }
    }

    /// Today at `hour`:`minute` on the phone's clock. Only the time of day is used.
    nonisolated private static func clockToday(hour: Int, minute: Int) -> Date {
        let calendar = Calendar.current
        return calendar.date(bySettingHour: hour, minute: minute, second: 0, of: Date()) ?? Date()
    }

    /// "23:00" as today at 23:00, or nil when it isn't a clock time.
    nonisolated private static func clockToday(_ clock: String) -> Date? {
        let parts = clock.split(separator: ":").compactMap { Int($0) }
        guard parts.count >= 2, (0..<24).contains(parts[0]), (0..<60).contains(parts[1]) else { return nil }
        return clockToday(hour: parts[0], minute: parts[1])
    }
}
