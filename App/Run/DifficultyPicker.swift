import AuraKit
import SwiftUI

/// How hard a focus session was, Easy to Very hard: five chips in two rows (Easy, Light, Normal / Hard, Very hard),
/// so all of them show on a phone. The question above it is the caller's, and so is the side padding.
struct DifficultyPicker: View {
    let selected: Difficulty?
    let onPick: (Difficulty) -> Void

    var body: some View {
        let columns: [GridItem] = Array(repeating: GridItem(.flexible(), spacing: 8), count: 3)
        LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
            ForEach(Difficulty.allCases) { difficulty in
                chip(difficulty)
            }
        }
        .padding(.vertical, 2)
    }

    private func chip(_ difficulty: Difficulty) -> some View {
        let isSelected = difficulty == selected
        return Button {
            onPick(difficulty)
        } label: {
            Text(difficulty.label)
                .font(.system(size: 15, weight: isSelected ? .semibold : .medium))
                .foregroundStyle(isSelected ? Color.white : Color.black)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.horizontal, 10)
                .frame(maxWidth: .infinity, minHeight: 38)
                .background(isSelected ? AuraColor.blue : AuraColor.card, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("difficulty-\(difficulty.rawValue)")
    }
}
