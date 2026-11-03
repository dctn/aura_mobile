import XCTest

/// Home top to bottom, Calendar and Settings. The stub signs in by itself, so sign-in can't be walked here.
final class HomeWalkTests: WalkTestCase {
    func testWalkHome() {
        openTab("Home")
        XCTAssertTrue(app.staticTexts["Insights"].waitForExistence(timeout: 20), "Home didn't open")
        // Burnout's line appears once its stub answer is in.
        _ = app.staticTexts["Higher possibility estimated"].waitForExistence(timeout: 5)
        save("10-home-top")

        let insights = app.staticTexts["Insights"]
        let top = insights.frame.minY
        dragPage(fromY: 0.7, toY: 0.2, velocity: .default)
        XCTAssertLessThan(insights.frame.minY, top, "Home didn't scroll")
        save("11-home-mid")
        dragPage(fromY: 0.7, toY: 0.2, velocity: .default)
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
                dragPage(fromY: 0.6, toY: 0.35, velocity: .slow)
            } else {
                dragPage(fromY: 0.35, toY: 0.6, velocity: .slow)
            }
        }
    }

    /// Drags in the page's right margin. `app.swipeUp()` starts mid-screen, where the mood slider's thumb can sit and
    /// take the touch, so Home's swipes did nothing.
    private func dragPage(fromY: CGFloat, toY: CGFloat, velocity: XCUIGestureVelocity) {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: fromY))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: toY))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: velocity, thenHoldForDuration: 0.1)
    }
}
