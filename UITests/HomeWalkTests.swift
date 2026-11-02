import XCTest

/// Home top to bottom, Calendar and Settings. The stub signs in by itself, so sign-in can't be walked here.
final class HomeWalkTests: WalkTestCase {
    func testWalkHome() {
        openTab("Home")
        XCTAssertTrue(app.staticTexts["Insights"].waitForExistence(timeout: 20), "Home didn't open")
        // Burnout's line appears once its stub answer is in.
        _ = app.staticTexts["Higher possibility estimated"].waitForExistence(timeout: 5)
        save("10-home-top")

        app.swipeUp()
        save("11-home-mid")
        app.swipeUp()
        save("12-home-bottom")

        let calendar = app.buttons["home-work-calendar"]
        XCTAssertTrue(calendar.waitForExistence(timeout: 10), "Not found: Calendar row")
        bringIntoView(calendar)
        calendar.tap()
        _ = app.navigationBars["Calendar"].waitForExistence(timeout: 5)
        save("13-work-calendar")

        tap(app.navigationBars.buttons.element(boundBy: 0), "Back from Calendar")

        openTab("Search")
        let settings = app.buttons["search-place-Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10), "Not found: Settings row")
        var swipes = 0
        while swipes < 8, !(settings.isHittable && settings.frame.maxY < app.tabBars.firstMatch.frame.minY) {
            app.swipeUp()
            swipes += 1
        }
        settings.tap()
        _ = app.buttons["settings-sign-out"].waitForExistence(timeout: 5)
        save("14-settings")
    }

    /// Moves the page slowly until the element sits clear of the tab bar and the navigation bar.
    private func bringIntoView(_ element: XCUIElement) {
        let height = app.windows.firstMatch.frame.height
        let lowest = height * 0.7
        let highest = height * 0.15
        for _ in 0..<8 {
            let middle = element.frame.midY
            if middle >= highest && middle <= lowest && element.isHittable { return }
            if middle > lowest {
                app.swipeUp(velocity: .slow)
            } else {
                app.swipeDown(velocity: .slow)
            }
        }
    }
}
