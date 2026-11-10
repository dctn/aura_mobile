import XCTest

/// The Recovery tab's two break pills: taking a break as a timed run, and adding one taken earlier.
final class RecoveryBreakWalkTests: WalkTestCase {
    func testTakeBreakStartsRun() {
        openTab("Recovery")
        tap(element("recovery-take-break"), "Take a recovery break")
        XCTAssertTrue(element("break-sheet").waitForExistence(timeout: 10), "The break sheet didn't open")
        for id in ["nap", "walk", "rest", "outside", "social"] {
            XCTAssertTrue(element("break-type-\(id)").waitForExistence(timeout: 10), "No \(id) in the break sheet")
        }
        save("59-take-break")

        tap(element("break-type-walk"), "Walk")
        tap(element("break-length-20"), "20 min")
        tap(element("break-start"), "Start")
        XCTAssertTrue(element("run-pause").waitForExistence(timeout: 15), "The break run didn't open")
        save("60-recovery-break-run")
    }

    /// Under UI tests the sheet starts at 09:15 today, which overlaps the stub's 09:00 to 09:50 session.
    func testLogBreakOverlap() {
        openTab("Recovery")
        tap(element("recovery-log-break"), "Add a break I took")
        XCTAssertTrue(element("log-break-sheet").waitForExistence(timeout: 10), "The log sheet didn't open")
        tap(element("log-type-walk"), "Walk")
        tap(element("log-day-today"), "Today")
        XCTAssertTrue(element("log-start-time").exists, "No start time")
        XCTAssertTrue(element("log-minutes").exists, "No length")
        save("61-log-break")

        tap(element("log-save"), "Save")
        let error = element("log-error")
        XCTAssertTrue(error.waitForExistence(timeout: 10), "The overlap wasn't shown")
        XCTAssertTrue(app.staticTexts["That overlaps a session you already recorded."].exists, "Wrong overlap message")
        save("62-log-overlap")

        // Yesterday at the same time overlaps nothing, so it goes in.
        tap(element("log-day-yesterday"), "Yesterday")
        tap(element("log-save"), "Save")
        XCTAssertTrue(element("log-saved").waitForExistence(timeout: 10), "The break wasn't added")
    }

    private func element(_ identifier: String) -> XCUIElement {
        return app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }
}
