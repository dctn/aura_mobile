import XCTest

/// Search, a category, a metric and its two lists, for comparing with the Figma.
final class SearchWalkTests: WalkTestCase {
    func testWalkSearchAndMetric() {
        openTab("Search")
        XCTAssertTrue(element("category-movement").waitForExistence(timeout: 10), "Search never listed its categories")
        save("40-search")
        app.swipeUp()
        save("41-search-lower")

        open("category-movement", swipingDown: true)
        XCTAssertTrue(element("metric-walkDistanceKm").waitForExistence(timeout: 10), "Movement never listed its rows")
        save("42-movement")
        app.swipeUp()
        save("43-movement-lower")

        open("metric-walkDistanceKm", swipingDown: true)
        XCTAssertTrue(element("metric-range-1").waitForExistence(timeout: 10), "The metric page never opened")
        save("44-metric")
        tap(element("metric-range-1"), "1 Month")
        // The white lozenge is still sliding right after the tap.
        Thread.sleep(forTimeInterval: 0.8)
        save("45-metric-range")
        tap(element("metric-range-2"), "2 Months")
        Thread.sleep(forTimeInterval: 0.8)
        save("45-metric-range-2")
        app.swipeUp()
        save("46-metric-lower")

        open("metric-row-all-data", swipingDown: false)
        XCTAssertTrue(app.staticTexts["All Recorded Data"].waitForExistence(timeout: 10), "All Recorded Data never opened")
        save("47-recorded")
        goBack()

        open("metric-row-sources", swipingDown: false)
        XCTAssertTrue(app.staticTexts["Sources"].waitForExistence(timeout: 10), "Sources never opened")
        save("48-sources")
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// Scrolls until the row is clear of the floating tab bar, then taps it.
    private func open(_ identifier: String, swipingDown: Bool) {
        let row = element(identifier)
        XCTAssertTrue(row.waitForExistence(timeout: 10), "Not found: \(identifier)")
        let bar = app.tabBars.firstMatch
        let lowest = bar.exists ? bar.frame.minY : app.frame.maxY
        var tries = 0
        while tries < 8, !(row.isHittable && row.frame.maxY < lowest) {
            if swipingDown { app.swipeDown() } else { app.swipeUp() }
            tries += 1
        }
        row.tap()
    }

    private func goBack() {
        tap(app.navigationBars.buttons.element(boundBy: 0), "Back")
    }
}
