import AuraKit
import SwiftUI

/// What the break sheet asks for: the Recovery break mode, run under the type's name for this many minutes.
struct RecoveryBreakChoice: Equatable {
    var type: RecoveryType
    var title: String
    var minutes: Int
    /// The type's own line for the run screen; empty keeps the mode's.
    var note: String
}

/// "Take a recovery break": pick a kind of break and a length, then Start. The kinds and their lifts come from the
/// Capacity service, or from the app's own copy of them when it can't be reached.
struct RecoveryBreakSheet: View {
    /// Called with the pick just before the sheet closes; the caller starts the run once it has gone.
    let onStart: (RecoveryBreakChoice) -> Void

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var options: [RecoveryOption] = []
    @State private var selectedId: String? = nil
    @State private var minutes = 20

    private static let lengths = [10, 15, 20, 30]

    init(onStart: @escaping (RecoveryBreakChoice) -> Void) {
        self.onStart = onStart
    }

    /// The Capacity service's values, in its order, for when it can't be reached.
    static let fallbackOptions: [RecoveryOption] = [
        RecoveryOption(id: "nap", title: RecoveryType.nap.label, minutes: 20, maxPoints: 10, fadesAfterMinutes: 120,
                       note: ""),
        RecoveryOption(id: "walk", title: RecoveryType.walk.label, minutes: 20, maxPoints: 9, fadesAfterMinutes: 50,
                       note: ""),
        RecoveryOption(id: "rest", title: RecoveryType.rest.label, minutes: 20, maxPoints: 5, fadesAfterMinutes: 40,
                       note: ""),
        RecoveryOption(id: "outside", title: RecoveryType.outside.label, minutes: 20, maxPoints: 9,
                       fadesAfterMinutes: 50, note: ""),
        RecoveryOption(id: "social", title: RecoveryType.social.label, minutes: 20, maxPoints: 5,
                       fadesAfterMinutes: 40, note: ""),
    ]

    private var selected: RecoveryOption? {
        guard let selectedId else { return nil }
        return options.first { $0.id == selectedId }
    }

    var body: some View {
        AuraPage {
            header
            if options.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.top, 24)
            } else {
                typeCard
                lengthRow
                Text("Under 10 minutes gives no lift; the full lift comes at 20 minutes.")
                    .font(.system(size: 15))
                    .foregroundStyle(Color.gray)
                    .fixedSize(horizontal: false, vertical: true)
                buttons
            }
        }
        .task { await load() }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle("Take a recovery break")
                .accessibilityIdentifier("break-sheet")
            Text("Pick what you'll do. It runs as a timed break and lifts your capacity.")
                .font(.system(size: 15))
                .foregroundStyle(Color.gray)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 8)
    }

    private var typeCard: some View {
        AuraCard(padding: 20) {
            VStack(spacing: 0) {
                ForEach(options) { option in
                    if option.id != options.first?.id { Divider() }
                    typeRow(option)
                }
            }
        }
    }

    private func typeRow(_ option: RecoveryOption) -> some View {
        let isSelected = option.id == selectedId
        return Button {
            selectedId = option.id
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(option.title)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(Color.black)
                    Text(option.hint)
                        .font(.system(size: 14))
                        .foregroundStyle(Color.gray)
                }
                Spacer(minLength: 8)
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(isSelected ? AuraColor.blue : AuraColor.footnote)
            }
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("break-type-\(option.id)")
    }

    private var lengthRow: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("How long")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Color.black)
            HStack(spacing: 8) {
                ForEach(Self.lengths, id: \.self) { length in
                    lengthChip(length)
                }
            }
        }
    }

    private func lengthChip(_ length: Int) -> some View {
        let isSelected = length == minutes
        return Button {
            minutes = length
        } label: {
            Text("\(length) min")
                .font(.system(size: 15, weight: isSelected ? .semibold : .medium))
                .foregroundStyle(isSelected ? Color.white : Color.black)
                .lineLimit(1)
                .fixedSize()
                .padding(.horizontal, 14)
                .frame(height: 38)
                .background(isSelected ? AuraColor.blue : AuraColor.card, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("break-length-\(length)")
    }

    private var buttons: some View {
        HStack(spacing: 10) {
            PillButton(title: "Cancel", style: .secondary, fills: true) {
                dismiss()
            }
            .accessibilityIdentifier("break-cancel")

            PillButton(title: "Start", fills: true) {
                start()
            }
            .disabled(selected?.type == nil)
            .accessibilityIdentifier("break-start")
        }
    }

    /// Only kinds the app knows are listed, since the session must send one of them.
    private func load() async {
        guard options.isEmpty else { return }
        var loaded: [RecoveryOption] = []
        do {
            loaded = try await model.api.recoveryOptions(minutes: 20)
        } catch {
            model.note(error)
        }
        let known = loaded.filter { $0.type != nil }
        options = known.isEmpty ? Self.fallbackOptions : known
    }

    private func start() {
        guard let option = selected, let type = option.type else { return }
        onStart(RecoveryBreakChoice(type: type, title: option.title, minutes: minutes, note: option.note))
        dismiss()
    }
}
