import SwiftUI

@main
struct AuraClarusApp: App {
    @State private var model = AppModel.make()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                // Figma has light frames only; the nap screen paints its own black.
                .preferredColorScheme(.light)
        }
    }
}
