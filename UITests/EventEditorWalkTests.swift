import XCTest

/// The event editor on the Calendar page: a new event added, opened again and changed, then deleted.
final class EventEditorWalkTests: WalkTestCase {
    func testAddChangeAndDeleteAnEvent() {
        openCalendarFromHome()
        let newButton = element("calendar-new-event")
        XCTAssertTrue(newButton.waitForExistence(timeout: 10), "No New event button")
        let title = element("event-title")
        open(newButton, until: title, "The editor didn't open")
        XCTAssertFalse(element("event-delete").exists, "A new event shouldn't offer Delete")
        save("69-event-new")

        // An empty title can't be saved.
        XCTAssertTrue(element("event-problem").exists, "No problem line for an empty title")
        title.tap()
        title.typeText("Walk with Ana\n")
        let saveButton = element("event-save")
        XCTAssertTrue(saveButton.waitForExistence(timeout: 5), "No Add button")
        bringIntoView(saveButton)
        saveButton.tap()
        waitUntilGone(saveButton, "The editor stayed open after Add")

        let row = eventRow("Walk with Ana")
        XCTAssertTrue(row.waitForExistence(timeout: 10), "The new event isn't on the Calendar")
        open(row, until: element("event-delete"), "The event didn't open in the editor")
        XCTAssertEqual(element("event-title").value as? String, "Walk with Ana")

        let meeting = app.segmentedControls.buttons["Meeting"]
        tap(meeting, "Meeting kind")
        save("70-event-edit")
        let saveAgain = element("event-save")
        bringIntoView(saveAgain)
        saveAgain.tap()
        waitUntilGone(saveAgain, "The editor stayed open after Save")

        open(eventRow("Walk with Ana"), until: element("event-delete"), "The event didn't open again")
        XCTAssertTrue(app.segmentedControls.buttons["Meeting"].isSelected, "The new kind wasn't kept")
        let delete = element("event-delete")
        bringIntoView(delete)
        delete.tap()
        let confirm = app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@",
                                                       "Delete event", "event-delete")).firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "No delete confirmation")
        save("71-event-delete")
        confirm.tap()
        waitUntilGone(delete, "The editor stayed open after Delete")
        waitUntilGone(eventRow("Walk with Ana"), "The deleted event is still on the Calendar")
        save("72-calendar-after-delete")
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// A Calendar row: its label starts with the title, then the times. The hour grid's summary only contains it.
    private func eventRow(_ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
    }
}
