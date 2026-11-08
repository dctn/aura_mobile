import XCTest

/// A new account's Calendar: no work hours yet, the form opened from the zone card, then the page after saving.
/// It doesn't expect a ready day after saving: the suggested hours are Mon-Fri and CI can run on a weekend.
final class ZonesWalkTests: WalkTestCase {
    func testSetWorkHoursFromCalendar() {
        app.terminate()
        app.launchArguments = ["--uitesting", "--uitesting-no-schedule"]
        app.launch()

        openCalendarFromHome()
        let setHours = element("calendar-set-hours")
        XCTAssertTrue(setHours.waitForExistence(timeout: 10), "No Set work hours button")
        save("16-calendar-no-schedule")

        setHours.tap()
        let saveButton = element("work-hours-save")
        XCTAssertTrue(saveButton.waitForExistence(timeout: 10), "The work hours form didn't open")
        save("17-work-hours-new")

        saveButton.tap()
        waitUntilGone(saveButton, "The form stayed open after Save")
        waitUntilGone(setHours, "Set work hours stayed after saving")
        XCTAssertTrue(element("calendar-zone-line").waitForExistence(timeout: 10), "No zone line after saving")
        save("18-calendar-after-save")
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }
}
