import AuraKit
import SwiftUI

/// The account, the version and Sign out. The Figma has no frame for this page.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmingSignOut = false
    @State private var signingOut = false

    private var email: String? {
        guard case .some(.signedIn(let address?)) = model.authState, !address.isEmpty else { return nil }
        return address
    }

    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "-"
        let build = info?["CFBundleVersion"] as? String ?? "-"
        return "\(short) (\(build))"
    }

    var body: some View {
        AuraPage {
            AuraCard {
                VStack(alignment: .leading, spacing: 14) {
                    if let email {
                        row(label: "Account", value: email)
                        Divider()
                    }
                    row(label: "Version", value: version)
                }
            }
            signOutButton
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.large)
    }

    private func row(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.system(size: 13))
                .foregroundStyle(Color.gray)
            Text(value)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(.black)
        }
        .accessibilityElement(children: .combine)
    }

    private var signOutButton: some View {
        PillButton(title: signingOut ? "Signing out..." : "Sign out", style: .secondary, fills: true) {
            confirmingSignOut = true
        }
        .disabled(signingOut)
        .accessibilityIdentifier("settings-sign-out")
        .confirmationDialog("Sign out of Aura Clarus?", isPresented: $confirmingSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { signOut() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You'll need a new email code to sign in again.")
        }
    }

    private func signOut() {
        signingOut = true
        Task {
            await model.signOut()
            signingOut = false
        }
    }
}
