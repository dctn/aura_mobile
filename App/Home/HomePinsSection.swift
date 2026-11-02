import AuraKit
import SwiftUI

/// Pins: the person's own pinned metrics with their latest values. The Figma's four rows are filler.
struct HomePinsSection: View {
    @Environment(AppModel.self) private var model
    @State private var latest: [String: FieldAvailability] = [:]
    /// Each field's catalogue format, so a value in metres shows as km.
    @State private var formats: [String: String] = [:]
    @State private var editing = false
    @State private var failed = false
    @State private var retry = 0

    /// Also loads again when a pin is added from a metric's page.
    private var loadKey: String {
        let fields = model.pins.map(\.field).joined(separator: ",")
        return "\(model.dataVersion)-\(retry)-\(fields)"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            title
            rows
            if failed {
                HomeRetryLine(text: "Couldn't load the latest values.") { retry += 1 }
            }
            Footnote(text: HomeCopy.footnote, linkTitle: HomeCopy.privacyLink)
        }
        .task(id: loadKey) { await load() }
    }

    private var title: some View {
        HStack(spacing: 0) {
            SectionTitle("Pins")
                .fixedSize()
            if !model.pins.isEmpty {
                editButton
            }
            Spacer(minLength: 0)
        }
    }

    /// The small blue glass circle, with a larger area to tap.
    private var editButton: some View {
        Button {
            editing.toggle()
        } label: {
            Image(systemName: editing ? "checkmark" : "square.and.pencil")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 26, height: 26)
                .glassEffect(.regular.tint(AuraColor.blue).interactive(), in: Circle())
                .padding(9)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(editing ? "Done editing pins" : "Edit pins")
        .accessibilityIdentifier("home-pins-edit")
    }

    @ViewBuilder
    private var rows: some View {
        if model.pins.isEmpty {
            Text("You can pin a metric from its page.")
                .font(.system(size: 15))
                .foregroundStyle(AuraColor.footnote)
        } else {
            ForEach(model.pins) { pin in
                pinRow(pin)
            }
        }
    }

    @ViewBuilder
    private func pinRow(_ pin: PinnedMetric) -> some View {
        if editing {
            HStack(spacing: 10) {
                removeButton(pin)
                pinCard(pin)
            }
        } else {
            NavigationLink {
                MetricView(name: pin.name, field: pin.field, unit: pin.unit)
            } label: {
                pinCard(pin)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("home-pin-\(pin.field)")
        }
    }

    private func pinCard(_ pin: PinnedMetric) -> some View {
        let availability = latest[pin.field]
        return ListRowCard(
            symbol: "arrow.clockwise.heart.fill",
            title: pin.name,
            value: valueText(availability, pin: pin),
            unit: availability?.latestNumber == nil ? nil : MetricMaths.shownUnit(format: formats[pin.field] ?? "", unit: pin.unit),
            showsChevron: false
        )
    }

    private func removeButton(_ pin: PinnedMetric) -> some View {
        Button {
            remove(pin)
        } label: {
            Image(systemName: "minus.circle.fill")
                .font(.system(size: 24))
                .foregroundStyle(AuraColor.red)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Remove \(pin.name)")
    }

    private func remove(_ pin: PinnedMetric) {
        model.togglePin(pin)
        if model.pins.isEmpty {
            editing = false
        }
    }

    /// Nil hides the value: no number is better than a made-up one.
    private func valueText(_ availability: FieldAvailability?, pin: PinnedMetric) -> String? {
        guard let availability else { return nil }
        return MetricMaths.latestText(availability, format: formats[pin.field] ?? "", unit: pin.unit)
    }

    /// Values still show without formats, so a failure here isn't an error on the page.
    private func loadFormats() async {
        guard formats.isEmpty, let sections = try? await model.api.catalogue() else { return }
        var byField: [String: String] = [:]
        for section in sections {
            for parameter in section.parameters {
                if let field = parameter.field { byField[field] = parameter.format }
            }
        }
        formats = byField
    }

    private func load() async {
        guard !model.pins.isEmpty else {
            failed = false
            return
        }
        let today = Date()
        // The backend allows at most 400 days.
        let start = Calendar.current.date(byAdding: .day, value: -365, to: today) ?? today
        do {
            let available = try await model.api.availability(from: DayKey.string(start), to: DayKey.string(today))
            var byField: [String: FieldAvailability] = [:]
            for item in available {
                byField[item.field] = item
            }
            latest = byField
            failed = false
            await loadFormats()
        } catch {
            if Task.isCancelled { return }
            model.note(error)
            failed = latest.isEmpty
        }
    }
}
