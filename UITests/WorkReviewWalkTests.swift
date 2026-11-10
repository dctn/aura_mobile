import XCTest

/// Home → Work: the sessions list with the stub's Deep work session, rating it Hard, then the Review inbox's two
/// cards and answering the nap as a Nap.
final class WorkReviewWalkTests: WalkTestCase {
    func testRateSessionAndReviewBreak() {
        openHome()

        // The sessions list sits under Calendar and Task, near the bottom of Home.
        let rate = element("session-rate-s-deep")
        XCTAssertTrue(rate.waitForExistence(timeout: 10), "No Rate it on the Deep work row")
        XCTAssertTrue(element("session-row-s-deep").exists, "No Deep work row")
        bringIntoView(rate)
        save("63-sessions-list")

        let sheet = element("session-rating-sheet")
        open(rate, until: sheet, "The rating sheet didn't open")
        tap(element("difficulty-hard"), "Hard")
        waitUntilGone(sheet, "The rating sheet didn't close")

        let rated = element("session-rating-s-deep")
        XCTAssertTrue(rated.waitForExistence(timeout: 10), "The row doesn't show its rating")
        XCTAssertTrue(rated.label.contains("Hard"), "The row shows \(rated.label), not Hard")
        waitUntilGone(rate, "Rate it is still on the row")
        save("64-session-rated")

        // The inbox is under the list: the meeting and the nap.
        let syncYes = element("review-happened-yes-b-sync")
        XCTAssertTrue(syncYes.waitForExistence(timeout: 10), "No Team sync card")
        XCTAssertTrue(element("review-card-e1-review").exists, "No 10 Min nap card")
        bringIntoView(syncYes)
        save("65-review-inbox")

        let napYes = element("review-happened-yes-e1-review")
        bringIntoView(napYes)
        napYes.tap()
        let nap = element("review-type-nap-e1-review")
        XCTAssertTrue(nap.waitForExistence(timeout: 10), "No break types after Yes")
        bringIntoView(nap)
        nap.tap()
        save("66-review-recovery-type")

        let saveButton = element("review-save-e1-review")
        bringIntoView(saveButton)
        saveButton.tap()
        waitUntilGone(element("review-card-e1-review"), "The nap card is still there after Save")
        XCTAssertTrue(element("review-card-b-sync").exists, "The Team sync card went too")
    }

    /// Home, scrolled down past Insights and Today, so the Work section is close.
    private func openHome() {
        openTab("Home")
        XCTAssertTrue(app.staticTexts["Insights"].waitForExistence(timeout: 20), "Home didn't open")
        XCTAssertTrue(app.buttons["home-work-calendar"].waitForExistence(timeout: 10), "Not found: Calendar row")
        dragPage(fromY: 0.7, toY: 0.2, velocity: .default)
        dragPage(fromY: 0.7, toY: 0.2, velocity: .default)
        dragPage(fromY: 0.7, toY: 0.2, velocity: .default)
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }
}
