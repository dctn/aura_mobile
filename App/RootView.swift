import AuraKit
import SwiftUI

/// Sign-in first, then the three tabs.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase

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
        .onChange(of: model.authState) { _, _ in
            model.sendTimeZoneIfNeeded()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.reloadIfNewDay() }
        }
    }
}

struct MainTabs: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var run = model.run
        @Bindable var shared = model
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
        .sheet(isPresented: $shared.needsConsent) {
            ConsentSheet()
        }
    }
}

private extension View {
    /// Frame 14: a hidden run sits in a bar above the tabs.
    func runBarAboveTabs() -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) { MiniRunBar() }
    }
}
