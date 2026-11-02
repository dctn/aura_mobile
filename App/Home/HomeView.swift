import AuraKit
import SwiftUI

/// Frame iPhone 18 Pro - 4. Each section loads on its own, so one failure doesn't blank the page.
struct HomeView: View {
    @State private var modeRoute: HomeModeRoute?

    var body: some View {
        AuraPage {
            HomeInsightsSection()
            HomeTodaySection(onStart: { route in modeRoute = route })
            HomeBurnoutSection()
            HomeWorkSection()
            HomePinsSection()
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink {
                    SettingsView()
                } label: {
                    Image(systemName: "gearshape")
                }
                .accessibilityLabel("Settings")
                .accessibilityIdentifier("home-settings")
            }
        }
        .navigationDestination(item: $modeRoute) { route in
            HomeModeDestination(route: route)
        }
    }
}

/// An alert's Start: which mode to open, and the alert's title as its headline.
struct HomeModeRoute: Hashable {
    let modeId: String
    let headline: String
}

private struct HomeModeDestination: View {
    @Environment(AppModel.self) private var model
    let route: HomeModeRoute

    var body: some View {
        if let mode = model.mode(id: route.modeId) {
            ModeDetailView(mode: mode, headline: route.headline)
        } else {
            NotDesignedYet(title: route.headline)
        }
    }
}

/// The two lines of copy several sections share.
enum HomeCopy {
    static let footnote = "Each against your own normal (100) over the last 14 days. The band is your usual range. For more information, see the"
    /// There's no URL yet, so the link stays inactive.
    static let privacyLink = "Adarna privacy policy"
}

/// A small line shown when a section couldn't load.
struct HomeRetryLine: View {
    let text: String
    let retry: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(text)
                .font(.system(size: 15))
                .foregroundStyle(AuraColor.footnote)
            Button(action: retry) {
                Text("Try again")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AuraColor.blue)
            }
            .buttonStyle(.plain)
        }
    }
}
