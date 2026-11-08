import XCTest

/// The base of the screen walks: starts the app on stub data and saves PNGs for comparing with the Figma.
class WalkTestCase: XCTestCase {
    var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["--uitesting"]
        app.launch()
    }

    /// Saves to SCREENSHOT_DIR/<name>.png when CI sets it, and always attaches to the test result.
    func save(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        guard let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"], !dir.isEmpty else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png"))
    }

    func openTab(_ name: String) {
        let button = app.tabBars.buttons[name]
        XCTAssertTrue(button.waitForExistence(timeout: 20), "No tab called \(name)")
        button.tap()
    }

    /// Fails with the name when the element never turns up, then taps it.
    func tap(_ element: XCUIElement, _ name: String) {
        XCTAssertTrue(element.waitForExistence(timeout: 10), "Not found: \(name)")
        element.tap()
    }

    /// Fails with the message when the element is still there after the timeout.
    func waitUntilGone(_ element: XCUIElement, _ message: String, timeout: TimeInterval = 10) {
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: element)
        XCTAssertEqual(XCTWaiter().wait(for: [gone], timeout: timeout), .completed, message)
    }

    /// Moves the page slowly until the element sits clear of the tab bar and the navigation bar.
    func bringIntoView(_ element: XCUIElement) {
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

    /// Drags in the page's right margin. `app.swipeUp()` starts mid-screen, where Home's mood slider thumb can sit
    /// and take the touch, so Home's swipes did nothing.
    func dragPage(fromY: CGFloat, toY: CGFloat, velocity: XCUIGestureVelocity) {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: fromY))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: toY))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: velocity, thenHoldForDuration: 0.1)
    }

    /// Scrolls Home down to the Calendar row and opens it.
    func openCalendarFromHome() {
        openTab("Home")
        XCTAssertTrue(app.staticTexts["Insights"].waitForExistence(timeout: 20), "Home didn't open")
        let calendar = app.buttons["home-work-calendar"]
        XCTAssertTrue(calendar.waitForExistence(timeout: 10), "Not found: Calendar row")
        dragPage(fromY: 0.7, toY: 0.2, velocity: .default)
        dragPage(fromY: 0.7, toY: 0.2, velocity: .default)
        bringIntoView(calendar)
        calendar.tap()
    }
}
