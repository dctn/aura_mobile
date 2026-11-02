import SwiftUI

/// One plain step, shown when the backend answers `consent_required`.
struct ConsentSheet: View {
    @Environment(AppModel.self) private var model
    @State private var busy = false
    @State private var errorText: String?

    var body: some View {
        AuraPage {
            SectionTitle("Health data")
            AuraCard {
                VStack(alignment: .leading, spacing: 12) {
                    message("To show these numbers, Aura needs your permission to store your band and app data in the cloud, read it there and work out your metrics from it.")
                    message("If you choose Not now, Aura won't store your health data or work out your metrics, so these pages stay empty. You'll be asked again next time they need it.")
                }
            }
            if let errorText {
                Text(errorText)
                    .font(.system(size: 15))
                    .foregroundStyle(AuraColor.red)
            }
            buttons
        }
        .interactiveDismissDisabled(busy)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 15))
            .foregroundStyle(.black)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var buttons: some View {
        HStack(spacing: 10) {
            PillButton(title: "Not now", style: .secondary, fills: true) {
                model.needsConsent = false
            }
            .accessibilityIdentifier("consent-not-now")
            PillButton(title: busy ? "Saving..." : "Allow", fills: true) {
                allow()
            }
            .accessibilityIdentifier("consent-allow")
        }
        .disabled(busy)
    }

    private func allow() {
        busy = true
        errorText = nil
        Task {
            await model.grantConsent()
            busy = false
            // A grant that worked closes this sheet; one that failed leaves it up.
            if model.needsConsent {
                errorText = "That didn't go through. Check your connection and try again."
            }
        }
    }
}
