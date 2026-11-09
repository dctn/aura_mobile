import XCTest

/// Find a time on the Calendar page: the preview sheet from the stub plan, adding it to the calendar, and the
/// Quick check panel that comes first when `--uitesting-needs-check` is set.
final class FindTimeWalkTests: WalkTestCase {
    /// Opens Find a time, checks the stub plan's blocks, warning and unplaced task, then adds it to the calendar.
    func testFindATimePreviewAndConfirm() {
        openCalendarFromHome()
        let button = element("calendar-find-time")
        XCTAssertTrue(button.waitForExistence(timeout: 10), "No Find a time button")
        open(button, until: element("find-time-confirm"), "Find a time didn't open")

        let block = element("find-time-block")
        XCTAssertTrue(block.waitForExistence(timeout: 10), "No placed blocks in the preview")
        let blocks = app.descendants(matching: .any).matching(identifier: "find-time-block").count
        XCTAssertGreaterThanOrEqual(blocks, 4, "Expected 4 placed blocks, found \(blocks)")
        XCTAssertTrue(element("find-time-warning").waitForExistence(timeout: 10), "No recovery warning")

        // The unplaced list sits at the bottom of the sheet; it only has to exist, not be on screen.
        let dentist = labelled("Book the dentist")
        if !dentist.waitForExistence(timeout: 10) {
            dragPage(fromY: 0.6, toY: 0.4, velocity: .slow)
        }
        XCTAssertTrue(dentist.waitForExistence(timeout: 5), "No unplaced Book the dentist")
        save("50-find-time-preview")

        let confirm = element("find-time-confirm")
        tap(confirm, "Add to calendar")
        waitUntilGone(confirm, "The Find a time sheet didn't close")

        // The hour grid may start above 9:00, so look a little lower before giving up.
        let planned = labelled("Write the review (1/2)")
        var found = planned.waitForExistence(timeout: 10)
        var drags = 0
        while !found && drags < 3 {
            dragPage(fromY: 0.6, toY: 0.4, velocity: .slow)
            drags += 1
            found = planned.waitForExistence(timeout: 2)
        }
        XCTAssertTrue(found, "The plan didn't reach the calendar")
        save("51-calendar-planned")
    }

    /// With `--uitesting-needs-check` the Quick check panel shows first; Plan anyway goes on to the preview, Cancel closes it.
    func testQuickCheckFirst() {
        app.terminate()
        app.launchArguments = ["--uitesting", "--uitesting-needs-check"]
        app.launch()

        openCalendarFromHome()
        let button = element("calendar-find-time")
        XCTAssertTrue(button.waitForExistence(timeout: 10), "No Find a time button")
        open(button, until: element("find-time-plan-anyway"), "No Quick check panel")
        XCTAssertTrue(element("find-time-do-check").exists, "No Do the Quick check button")
        save("52-find-time-check")

        tap(element("find-time-plan-anyway"), "Plan anyway")
        let confirm = element("find-time-confirm")
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), "Plan anyway didn't show the preview")

        tap(element("find-time-cancel"), "Cancel")
        waitUntilGone(confirm, "The sheet didn't close")
    }

    /// Today wants the Quick check first, but tomorrow doesn't: picking Tomorrow from the day row shows its plan, with
    /// Book the dentist placed, and Add to calendar confirms that day.
    func testPlanTomorrow() {
        app.terminate()
        app.launchArguments = ["--uitesting", "--uitesting-needs-check"]
        app.launch()

        openCalendarFromHome()
        let button = element("calendar-find-time")
        XCTAssertTrue(button.waitForExistence(timeout: 10), "No Find a time button")
        let tomorrow = element("find-time-day-1")
        open(button, until: tomorrow, "Find a time didn't open")
        XCTAssertTrue(element("find-time-plan-anyway").waitForExistence(timeout: 10), "No Quick check panel for today")

        tap(tomorrow, "Tomorrow")
        let confirm = element("find-time-confirm")
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), "Tomorrow's preview didn't show")
        waitUntilGone(element("find-time-plan-anyway"), "The Quick check panel is still there for tomorrow")
        let dentist = app.descendants(matching: .any).matching(identifier: "find-time-block")
            .matching(NSPredicate(format: "label CONTAINS %@", "Book the dentist")).firstMatch
        XCTAssertTrue(dentist.waitForExistence(timeout: 10), "Book the dentist isn't placed tomorrow")
        save("57-find-time-tomorrow")

        tap(confirm, "Add to calendar")
        waitUntilGone(confirm, "The Find a time sheet didn't close")
        save("58-calendar-after-tomorrow")
    }

    private func element(_ identifier: String) -> XCUIElement {
        return app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// Matches a plain text or a row whose combined accessibility label holds the text.
    private func labelled(_ text: String) -> XCUIElement {
        return app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }
}
