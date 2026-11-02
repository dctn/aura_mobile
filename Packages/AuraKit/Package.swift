// swift-tools-version: 6.2
import PackageDescription

// The app's logic with no UI, so `swift test` runs on a Mac without a simulator.
let package = Package(
    name: "AuraKit",
    platforms: [.iOS(.v26), .macOS(.v15)],
    products: [.library(name: "AuraKit", targets: ["AuraKit"])],
    targets: [
        .target(name: "AuraKit", swiftSettings: [.swiftLanguageMode(.v5)]),
        .testTarget(name: "AuraKitTests", dependencies: ["AuraKit"], swiftSettings: [.swiftLanguageMode(.v5)]),
    ]
)
