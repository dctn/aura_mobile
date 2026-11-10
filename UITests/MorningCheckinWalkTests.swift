import XCTest

/// The morning check-in at the top of Today: with no Apple Health night it asks for sleep times; with
/// `--uitesting-health-night` launch sends the stub night, so it asks for alertness only. Other walks start with
/// the check-in already sent (no `--uitesting-checkin`).
final class MorningCheckinWalkTests: WalkTestCase {
    func testCheckinWithTimesThenAppleHealth() {
        app.terminate()
        app.launchArguments = ["--uitesting", "--uitesting-checkin"]
        app.launch()
        openHome()
        XCTAssertTrue(element("morning-checkin").waitForExistence(timeout: 10), "No morning check-in on Home")
        XCTAssertTrue(element("checkin-fell-asleep").waitForExistence(timeout: 10), "No fell asleep time")
        XCTAssertTrue(element("checkin-woke").exists, "No woke up time")
        XCTAssertTrue(element("checkin-alertness").exists, "No alertness slider")
        XCTAssertTrue(element("checkin-apple-health").exists, "No Use Apple Health button")
        let send = element("checkin-send")
        bringIntoView(send)
        save("67-checkin-sleep-times")

        send.tap()
        XCTAssertTrue(element("checkin-done").waitForExistence(timeout: 10), "The check-in didn't say it was sent")

        app.terminate()
        app.launchArguments = ["--uitesting", "--uitesting-health-night"]
        app.launch()

        openHome()
        XCTAssertTrue(element("morning-checkin").waitForExistence(timeout: 10), "No morning check-in after relaunch")
        waitUntilGone(element("checkin-fell-asleep"), "Still asks when you fell asleep after the Apple Health night")
        XCTAssertFalse(element("checkin-woke").exists, "Still asks when you woke up")
        XCTAssertFalse(element("checkin-apple-health").exists, "Still offers Apple Health when it's connected")
        let sendAgain = element("checkin-send")
        XCTAssertTrue(sendAgain.waitForExistence(timeout: 10), "No Send after relaunch")
        bringIntoView(sendAgain)
        save("68-checkin-apple-health")
    }

    private func openHome() {
        openTab("Home")
        XCTAssertTrue(app.staticTexts["Insights"].waitForExistence(timeout: 20), "Home didn't open")
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }
}
