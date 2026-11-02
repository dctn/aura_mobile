import AuraKit
import SwiftUI

/// Frames iPhone 18 Pro - 6 and - 7. `headline` is the Now card's title when opened from one.
struct ModeDetailView: View {
    @Environment(AppModel.self) private var model
    let mode: Mode
    var headline: String?

    var body: some View {
        AuraPage {
            SectionTitle(headline ?? mode.name)
            PillButton(title: "Start") { model.run.start(mode: mode, minutes: mode.minutes) }
        }
    }
}
