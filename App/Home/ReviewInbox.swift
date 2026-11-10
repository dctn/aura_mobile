import AuraKit
import SwiftUI

/// Home → Work, under the sessions: planned blocks of the last 7 days that nothing recorded, one card each.
/// Saving a card answers it and takes it away. The section hides when there's nothing to review.
struct ReviewInbox: View {
    @Environment(AppModel.self) private var model
    @State private var items: [ReviewItem] = []
    /// Answered here. A reload can't bring them back while the backend catches up.
    @State private var answered: Set<String> = []

    private var openItems: [ReviewItem] {
        items.filter { !answered.contains($0.blockId) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !openItems.isEmpty {
                Text("Did these happen?")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(.black)
                    .accessibilityAddTraits(.isHeader)
                ForEach(openItems) { item in
                    ReviewCard(item: item) {
                        answered.insert(item.blockId)
                    }
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("review-inbox")
        .task(id: model.dataVersion) { await load() }
    }

    private func load() async {
        do {
            let loaded = try await model.api.reviewInbox(date: DayKey.string(Date()), days: 7)
            items = loaded
        } catch {
            if Task.isCancelled { return }
            model.note(error)
        }
    }
}

/// One block: did it happen, then how it went. A break asks what kind it was, since a break without a type gives
/// the day score no lift; other blocks ask intensity and drain, both optional.
private struct ReviewCard: View {
    let item: ReviewItem
    let onSaved: () -> Void

    @Environment(AppModel.self) private var model
    @State private var happened: Bool?
    @State private var intensity: ReviewIntensity?
    @State private var drain: Int?
    @State private var recoveryType: RecoveryType?
    @State private var saving = false
    @State private var errorText: String?

    private var blockId: String { item.blockId }

    var body: some View {
        AuraCard(padding: 20) {
            VStack(alignment: .leading, spacing: 14) {
                header
                question("Did it happen?")
                happenedRow
                if happened == true {
                    if item.isRecovery {
                        typeSection
                    } else {
                        intensitySection
                        drainSection
                    }
                }
                if let errorText {
                    Text(errorText)
                        .font(.system(size: 15))
                        .foregroundStyle(AuraColor.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
                PillButton(title: saving ? "Saving..." : "Save", fills: true) {
                    save()
                }
                .disabled(happened == nil || saving)
                .accessibilityIdentifier("review-save-\(blockId)")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("review-card-\(blockId)")
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(ReviewText.when(item))
                .font(.system(size: 11))
                .foregroundStyle(Color.gray)
            Text(item.title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.black)
        }
        .accessibilityElement(children: .combine)
    }

    private func question(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(.black)
    }

    private var happenedRow: some View {
        HStack(spacing: 8) {
            ReviewChip(title: "Yes", selected: happened == true, identifier: "review-happened-yes-\(blockId)") {
                happened = true
            }
            ReviewChip(title: "No", selected: happened == false, identifier: "review-happened-no-\(blockId)") {
                happened = false
            }
        }
    }

    private var typeSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            question("What kind of break was it?")
            ChipRows(items: RecoveryType.allCases, perRow: 2) { type in
                ReviewChip(title: type.label, selected: recoveryType == type,
                           identifier: "review-type-\(type.rawValue)-\(blockId)") {
                    recoveryType = type
                }
            }
            if recoveryType == nil {
                Text("A break counts toward your day score once you say what it was.")
                    .font(.system(size: 13))
                    .foregroundStyle(Color.gray)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var intensitySection: some View {
        VStack(alignment: .leading, spacing: 10) {
            question("How intense was it?")
            HStack(spacing: 8) {
                ForEach(ReviewIntensity.allCases) { value in
                    ReviewChip(title: value.label, selected: intensity == value,
                               identifier: "review-intensity-\(value.rawValue)-\(blockId)") {
                        intensity = value
                    }
                }
            }
        }
    }

    private var drainSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            question("How draining was it? (1 is not at all)")
            HStack(spacing: 8) {
                ForEach(1...5, id: \.self) { value in
                    ReviewChip(title: "\(value)", selected: drain == value,
                               identifier: "review-drain-\(value)-\(blockId)") {
                        drain = value
                    }
                }
            }
        }
    }

    private func save() {
        guard let happened, !saving else { return }
        let asksType = happened && item.isRecovery
        let asksEffort = happened && !item.isRecovery
        let answer = ReviewAnswer(item: item, happened: happened,
                                  intensity: asksEffort ? intensity : nil,
                                  drain: asksEffort ? drain : nil,
                                  recoveryType: asksType ? recoveryType : nil)
        saving = true
        errorText = nil
        Task {
            do {
                try await model.api.submitReview(answer)
                onSaved()
            } catch {
                // A session already covers this block, so there's nothing left to answer.
                if (error as? AuraError)?.serverCode == "block_recorded" {
                    onSaved()
                    return
                }
                model.note(error)
                errorText = "Couldn't save that. Try again."
                saving = false
            }
        }
    }
}

/// A white chip that turns blue when picked, as on Find a time's day row. It takes its share of the row.
private struct ReviewChip: View {
    let title: String
    let selected: Bool
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 15, weight: selected ? .semibold : .medium))
                .foregroundStyle(selected ? Color.white : Color.black)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.horizontal, 10)
                .frame(maxWidth: .infinity)
                .frame(height: 38)
                .background(selected ? AuraColor.blue : AuraColor.background, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(identifier)
    }
}

/// Chips in rows of `perRow`, so long labels like "Talk with someone" still fit the card.
private struct ChipRows<Item: Hashable, Chip: View>: View {
    let items: [Item]
    let perRow: Int
    @ViewBuilder let chip: (Item) -> Chip

    private var rows: [[Item]] {
        stride(from: 0, to: items.count, by: perRow).map { start in
            Array(items[start..<min(start + perRow, items.count)])
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(spacing: 8) {
                    ForEach(row, id: \.self) { item in
                        chip(item)
                    }
                }
            }
        }
    }
}

/// The words a review card shows.
enum ReviewText {
    /// "Today 10:00–10:30", "Yesterday 15:54–16:04" or "Mon 9 Nov 09:00–10:00".
    static func when(_ item: ReviewItem) -> String {
        let range = ZoneText.range(item.start, item.end)
        let today = DayKey.string(Date())
        if item.date == today { return "Today \(range)" }
        let yesterday = DayKey.calendar.date(byAdding: .day, value: -1, to: Date()) ?? Date()
        if item.date == DayKey.string(yesterday) { return "Yesterday \(range)" }
        guard let day = DayKey.date(item.date) else { return range }
        return "\(day.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))) \(range)"
    }
}
