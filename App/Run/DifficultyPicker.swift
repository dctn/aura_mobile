import AuraKit
import SwiftUI

/// How hard a focus session was, Easy to Very hard: five chips in a row that scrolls sideways when it doesn't fit.
/// The question above it is the caller's, and so is the side padding.
struct DifficultyPicker: View {
    let selected: Difficulty?
    let onPick: (Difficulty) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Difficulty.allCases) { difficulty in
                    chip(difficulty)
                }
            }
            .padding(.vertical, 2)
        }
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
                .fixedSize()
                .padding(.horizontal, 14)
                .frame(height: 38)
                .background(isSelected ? AuraColor.blue : AuraColor.card, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("difficulty-\(difficulty.rawValue)")
    }
}
