import XCTest

/// The Quick check from Find a time, on the stub with `--uitesting-short-check`: an 8-second reaction test and one
/// 3-digit memory row. A check that counts lets Find a time show its preview.
final class QuickCheckWalkTests: WalkTestCase {
    /// Runs the whole check, then Find a time plans with it.
    func testQuickCheckFromFindATime() {
        relaunchNeedingCheck()
        openQuickCheck()
        save("53-quick-check-intro")
        tap(element("quick-check-start"), "Start")

        // A tap before green is a false start and moves the next dot back, so the test taps only on "Tap!".
        let card = element("quick-check-reaction")
        let digits = element("quick-check-digits")
        XCTAssertTrue(card.waitForExistence(timeout: 10), "The reaction card didn't show")
        let started = Date()
        var shot = false
        while Date().timeIntervalSince(started) < 25 && !digits.exists {
            if !shot && Date().timeIntervalSince(started) > 2 {
                save("54-quick-check-reaction")
                shot = true
            }
            if card.exists, (card.value as? String) == "Tap!" {
                card.tap()
            } else {
                Thread.sleep(forTimeInterval: 0.15)
            }
        }

        XCTAssertTrue(digits.waitForExistence(timeout: 15), "The memory digits didn't show")
        let row = digits.label.split(separator: " ").map(String.init)
        XCTAssertFalse(row.isEmpty, "The digits have no label")
        save("55-quick-check-memory")

        let firstKey = element("quick-check-key-0")
        let hittable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: firstKey)
        XCTAssertEqual(XCTWaiter().wait(for: [hittable], timeout: 15), .completed, "The keypad didn't show")
        for digit in row {
            tap(element("quick-check-key-\(digit)"), "Key \(digit)")
        }

        let result = element("quick-check-result")
        XCTAssertTrue(result.waitForExistence(timeout: 15), "No Quick check result")
        save("56-quick-check-result")
        XCTAssertTrue(labelled("This check counts").waitForExistence(timeout: 5), "The check didn't count")

        tap(element("quick-check-done"), "Done")
        XCTAssertTrue(element("find-time-confirm").waitForExistence(timeout: 15),
                      "Find a time didn't show its preview after the check")
        waitUntilGone(element("find-time-plan-anyway"), "The Quick check panel is still there")
    }

    /// Hide, then Stop, sends nothing and goes back to the Quick check panel.
    func testStopQuickCheck() {
        relaunchNeedingCheck()
        openQuickCheck()
        tap(element("quick-check-start"), "Start")
        XCTAssertTrue(element("quick-check-reaction").waitForExistence(timeout: 10), "The reaction card didn't show")

        tap(element("quick-check-hide"), "Hide")
        let stop = app.alerts.buttons["Stop"]
        XCTAssertTrue(stop.waitForExistence(timeout: 5), "No Stop the check? alert")
        stop.tap()

        waitUntilGone(element("quick-check-reaction"), "The Quick check didn't close")
        XCTAssertTrue(element("find-time-plan-anyway").waitForExistence(timeout: 10), "The Quick check panel isn't back")
    }

    private func relaunchNeedingCheck() {
        app.terminate()
        app.launchArguments = ["--uitesting", "--uitesting-needs-check", "--uitesting-short-check"]
        app.launch()
    }

    /// Opens Find a time from the Calendar, then the Quick check from its panel.
    private func openQuickCheck() {
        openCalendarFromHome()
        let button = element("calendar-find-time")
        XCTAssertTrue(button.waitForExistence(timeout: 10), "No Find a time button")
        open(button, until: element("find-time-do-check"), "No Quick check panel")
        tap(element("find-time-do-check"), "Do the Quick check")
        XCTAssertTrue(element("quick-check-intro").waitForExistence(timeout: 10), "The Quick check intro didn't show")
    }

    private func element(_ identifier: String) -> XCUIElement {
        return app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// Matches a plain text or a row whose combined accessibility label holds the text.
    private func labelled(_ text: String) -> XCUIElement {
        return app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }
}
