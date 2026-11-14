package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** The event editor on the Calendar page (iOS EventEditorWalkTests): a new event added, changed, then deleted. */
@RunWith(AndroidJUnit4::class)
class EventEditorWalkTest : WalkTestCase() {
    private val title = "Walk with Ana"

    @Test
    fun addChangeAndDeleteAnEvent() {
        openCalendarFromHome()
        Walk.find("calendar-new-event", "New event button")
        Walk.open("calendar-new-event", until = "event-title", message = "The editor didn't open")
        assertFalse("A new event shouldn't offer Delete", Walk.exists("event-delete"))
        save("69-event-new")

        // An empty title can't be saved.
        assertTrue("No problem line for an empty title", Walk.exists("event-problem", timeoutMs = 2_000))
        Walk.type("event-title", title)
        Walk.waitUntilGone("event-problem", "The problem line stayed after typing a title")
        Walk.bringIntoView("event-save")
        Walk.tap("event-save", "Add button")
        Walk.waitUntilGone("event-save", "The editor stayed open after Add")

        openRow("The event didn't open in the editor")
        assertEquals(title, Walk.find("event-title").text)

        Walk.tap("event-kind-Meeting", "Meeting kind")
        save("70-event-edit")
        Walk.bringIntoView("event-save")
        Walk.tap("event-save", "Save button")
        Walk.waitUntilGone("event-save", "The editor stayed open after Save")

        openRow("The event didn't open again")
        assertTrue("The new kind wasn't kept", Walk.find("event-kind-Meeting").isSelected)
        Walk.bringIntoView("event-delete")
        Walk.tap("event-delete", "Delete event")
        Walk.find("event-delete-confirm", "Delete confirmation", timeoutMs = 5_000)
        save("71-event-delete")
        Walk.tap("event-delete-confirm", "Delete confirmation")
        Walk.waitUntilGone("event-delete", "The editor stayed open after Delete")
        assertTrue(
            "The deleted event is still on the Calendar",
            Walk.device.wait(Until.gone(rowSelector()), 10_000) == true,
        )
        save("72-calendar-after-delete")
    }

    /** A Calendar row: its description starts with the title, then the times. The hour grid's summary only contains it. */
    private fun rowSelector() = By.descStartsWith("$title, ")

    /** Scrolls the Calendar until the event's row is in view (it can sit below the zones), then opens it. */
    private fun openRow(message: String) {
        repeat(3) {
            val row = scrollToRow() ?: return@repeat
            row.click()
            if (Walk.exists("event-delete", timeoutMs = 4_000)) return
        }
        fail(message)
    }

    private fun scrollToRow(): UiObject2? {
        Walk.device.wait(Until.hasObject(rowSelector()), 5_000)
        val lowest = Walk.device.displayHeight * 0.7
        val highest = Walk.device.displayHeight * 0.15
        repeat(12) {
            val row = Walk.device.findObject(rowSelector())
            when {
                row == null -> Walk.dragPage(0.6, 0.35)
                row.visibleBounds.centerY() > lowest -> Walk.dragPage(0.6, 0.35)
                row.visibleBounds.centerY() < highest -> Walk.dragPage(0.35, 0.6)
                else -> return row
            }
        }
        return Walk.device.findObject(rowSelector())
    }

    /** Scrolls Home down to the Calendar row and opens it. */
    private fun openCalendarFromHome() {
        Walk.openTab("Home")
        Walk.findText("Insights", timeoutMs = 20_000)
        Walk.dragPage(0.7, 0.2)
        Walk.dragPage(0.7, 0.2)
        Walk.open("home-work-calendar", until = "calendar-zone-line", message = "Calendar didn't open")
    }
}
