import SwiftUI

struct RunScreen: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 20) {
            Text(model.run.active?.title ?? "")
            PillButton(title: "Close", style: .secondary) { model.run.close() }
        }
    }
}

/// Frame 14: shows nothing unless a run is hidden.
struct MiniRunBar: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let active = model.run.active, !model.run.isExpanded {
            Button(active.title) { model.run.isExpanded = true }
                .padding(.bottom, 8)
        }
    }
}
