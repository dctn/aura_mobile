import XCTest

/// Recovery, the mode pages and every run screen. Each run starts from a fresh launch, so a run left open by one test
/// can't get in the way of the next. Modes are reached by search: tiles past the edge of a shelf aren't on screen.
final class RunWalkTests: WalkTestCase {
    func testRecoveryAndDetailPages() {
        openTab("Recovery")
        let card = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'now-card-'")).firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20), "No Now cards")
        save("20-recovery-top")

        app.swipeUp()
        save("21-recovery-mid")
        app.swipeDown()

        tap(card, "first Now card")
        XCTAssertTrue(element("mode-start").waitForExistence(timeout: 10), "No Start on the Now card's page")
        save("22-detail-now")
        goBack()

        openMode("nap-10", search: "ten-minute")
        save("23-detail-tile")
    }

    func testBreatheHideReopenAndEndEarly() {
        startMode("box", search: "box")
        XCTAssertTrue(element("run-end").waitForExistence(timeout: 10), "The breathing run didn't open")
        save("24-run-breathe")

        tap(element("run-hide"), "Hide")
        XCTAssertTrue(element("mini-run-bar").waitForExistence(timeout: 10), "No bar above the tabs")
        save("25-mini-bar")

        tap(element("mini-run-bar"), "the bar")
        tap(element("run-end"), "End")
        let dialog = app.alerts.firstMatch
        XCTAssertTrue(dialog.waitForExistence(timeout: 10), "No end-early dialog")
        save("26-end-early")

        tap(dialog.buttons["End session"], "End session")
        XCTAssertTrue(element("mode-start").waitForExistence(timeout: 10), "Ending didn't go back to the page")
    }

    func testFocusCountdownThenTimer() {
        startMode("timer", search: "timer")
        // The 3-2-1 only lasts three seconds, so this saves whatever shows once it has had two to come up.
        _ = element("run-countdown-cancel").waitForExistence(timeout: 2)
        save("27-countdown")
        XCTAssertTrue(element("run-pause").waitForExistence(timeout: 15), "The timer never started")
        save("28-timer")
    }

    func testNap() {
        startMode("nap-10", search: "ten-minute")
        XCTAssertTrue(element("run-pause").waitForExistence(timeout: 10), "The nap didn't open")
        save("29-nap")
    }

    func testFlow() {
        startMode("flow", search: "flow")
        XCTAssertTrue(element("run-pause").waitForExistence(timeout: 15), "Flow never started")
        save("30-flow")
    }

    func testGuided() {
        startMode("breath-awareness", search: "awareness")
        XCTAssertTrue(element("run-pause").waitForExistence(timeout: 10), "The meditation didn't open")
        save("31-guided")
    }

    func testWindDown() {
        startMode("wind-down", search: "wind")
        XCTAssertTrue(element("run-pause").waitForExistence(timeout: 10), "Wind-down didn't open")
        save("32-winddown")
    }

    /// The only run that reaches its end: `--uitesting-short-run` makes it last 3 seconds. A focus timer, so the Done
    /// sheet asks how hard it was and shows the capacity line for the rating.
    func testDoneSheet() {
        app.terminate()
        app.launchArguments = ["--uitesting", "--uitesting-short-run"]
        app.launch()
        startMode("timer", search: "timer")
        let done = element("run-done")
        XCTAssertTrue(done.waitForExistence(timeout: 25), "No Done sheet")
        // The summary adds its saved line once the backend has answered.
        let saved = app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Saved'")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 10), "The Done sheet never said Saved")
        XCTAssertTrue(element("run-difficulty").waitForExistence(timeout: 10), "No difficulty question")
        tap(element("difficulty-hard"), "Hard")
        let line = app.staticTexts.matching(identifier: "run-capacity-line").firstMatch
        let rated = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS 'You rated it Hard.'"),
                                              object: line)
        XCTAssertEqual(XCTWaiter().wait(for: [rated], timeout: 10), .completed, "The capacity line never showed the rating")
        save("33-run-done")
        done.tap()
        XCTAssertTrue(element("mode-start").waitForExistence(timeout: 10), "Done didn't go back to the page")
    }

    /// A session already running elsewhere: the start joins it, the alert asks, and stopping it starts this run's own.
    func testOtherSessionAlert() {
        app.terminate()
        app.launchArguments = ["--uitesting", "--uitesting-joined-session"]
        app.launch()
        startMode("nap-10", search: "ten-minute")
        let alert = app.alerts["Another session is running"]
        XCTAssertTrue(alert.waitForExistence(timeout: 15), "No alert for the session already running")
        save("34-other-session")
        tap(alert.buttons["Stop it and start mine"], "Stop it and start mine")
        XCTAssertTrue(element("run-pause").waitForExistence(timeout: 10), "The run didn't carry on")
        // The second start isn't joined, so the alert mustn't come back.
        XCTAssertFalse(alert.waitForExistence(timeout: 3), "The alert came back after stopping the other session")
    }

    private func element(_ identifier: String) -> XCUIElement {
        return app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    private func goBack() {
        tap(app.navigationBars.buttons.firstMatch, "the back button")
    }

    /// Searches the Recovery tab and opens the mode's page.
    private func openMode(_ id: String, search: String) {
        openTab("Recovery")
        let field = app.textFields.firstMatch
        tap(field, "the search field")
        field.typeText(search)
        tap(element("mode-\(id)"), "the \(id) tile")
        XCTAssertTrue(element("mode-start").waitForExistence(timeout: 10), "No Start on \(id)'s page")
    }

    private func startMode(_ id: String, search: String) {
        openMode(id, search: search)
        element("mode-start").tap()
    }
}
