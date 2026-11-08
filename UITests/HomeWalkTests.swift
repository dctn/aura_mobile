import XCTest

/// Home top to bottom, Calendar, the work hours form and Settings. The stub signs in by itself, so sign-in can't be
/// walked here.
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
        XCTAssertTrue(element("calendar-zone-line").waitForExistence(timeout: 10), "No zone line on Calendar")
        save("13-work-calendar")

        dragPage(fromY: 0.7, toY: 0.2, velocity: .default)
        save("13b-calendar-lower")

        let hours = element("calendar-work-hours")
        XCTAssertTrue(hours.waitForExistence(timeout: 10), "Not found: Work hours row")
        bringIntoView(hours)
        hours.tap()
        let saveButton = element("work-hours-save")
        XCTAssertTrue(saveButton.waitForExistence(timeout: 10), "The work hours form didn't open")
        save("15-work-hours")
        tap(element("work-hours-cancel"), "Cancel on the work hours form")
        waitUntilGone(saveButton, "The work hours form didn't close")

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
        // The row's summary appears once the stub has answered.
        _ = element("settings-work-hours").waitForExistence(timeout: 5)
        save("14-settings")
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }
}
