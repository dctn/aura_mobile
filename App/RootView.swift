import AuraKit
import SwiftUI

/// Sign-in first, then the three tabs.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            switch model.authState {
            case .none:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(AuraColor.background)
            case .some(.signedOut):
                SignInView()
            case .some(.signedIn):
                MainTabs()
            }
        }
        .task { await model.restore() }
    }
}

struct MainTabs: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var run = model.run
        TabView {
            Tab("Home", systemImage: "heart.fill") {
                NavigationStack { HomeView() }.runBarAboveTabs()
            }
            Tab("Recovery", systemImage: "leaf.fill") {
                NavigationStack { RecoveryView() }.runBarAboveTabs()
            }
            Tab("Search", systemImage: "magnifyingglass") {
                NavigationStack { SearchView() }.runBarAboveTabs()
            }
        }
        .tint(AuraColor.blue)
        .fullScreenCover(isPresented: $run.isExpanded) {
            RunScreen()
        }
    }
}

private extension View {
    /// Frame 14: a hidden run sits in a bar above the tabs.
    func runBarAboveTabs() -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) { MiniRunBar() }
    }
}
