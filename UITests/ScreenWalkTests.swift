import XCTest

/// The three tabs open. Each area's own walk is in its own file.
final class ScreenWalkTests: WalkTestCase {
    func testWalkTheTabs() {
        openTab("Home")
        save("01-home")
        openTab("Recovery")
        save("02-recovery")
        openTab("Search")
        save("03-search")
    }
}
