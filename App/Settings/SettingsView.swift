import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        AuraPage {
            PillButton(title: "Sign out", style: .secondary, fills: true) {
                Task { await model.signOut() }
            }
        }
        .navigationTitle("Settings")
    }
}
