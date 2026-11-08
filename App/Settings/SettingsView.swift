import AuraKit
import SwiftUI

/// Work hours, the account, the version and Sign out. The Figma has no frame for this page.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmingSignOut = false
    @State private var signingOut = false
    @State private var schedule: WorkSchedule?
    @State private var scheduleLoaded = false
    @State private var scheduleFailed = false
    @State private var editingHours = false

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

    /// "09:00–18:00 · Mon–Fri · Lunch 13:00", "Not set", or nothing while it loads.
    private var workHoursText: String? {
        if scheduleLoaded {
            guard let schedule else { return ZoneText.schedule(nil) }
            return "\(ZoneText.schedule(schedule)) · Lunch \(schedule.lunchStart)"
        }
        return scheduleFailed ? "Couldn't load" : nil
    }

    var body: some View {
        AuraPage {
            workHoursSection
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
        .metricBackBar("Settings")
        .task(id: model.dataVersion) { await loadSchedule() }
        .sheet(isPresented: $editingHours) {
            WorkHoursForm(saved: schedule)
        }
    }

    private var workHoursSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Work hours")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.black)
                .accessibilityAddTraits(.isHeader)
            WorkHoursRow(title: "Work and lunch", value: workHoursText, identifier: "settings-work-hours") {
                editingHours = true
            }
        }
    }

    /// Runs again after the form saves, since saving bumps `dataVersion`.
    private func loadSchedule() async {
        do {
            schedule = try await model.api.workSchedule()
            scheduleLoaded = true
            scheduleFailed = false
        } catch {
            if Task.isCancelled { return }
            model.note(error)
            scheduleFailed = !scheduleLoaded
        }
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
