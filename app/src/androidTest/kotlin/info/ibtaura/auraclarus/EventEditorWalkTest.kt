package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * The event editor on the Calendar page (iOS EventEditorWalkTests): a new event added, opened from its block on the
 * hour grid, renamed and saved, then opened from the grid again and deleted.
 */
@RunWith(AndroidJUnit4::class)
class EventEditorWalkTest : WalkTestCase() {
    private val title = "Walk with Ana"
    private val renamed = "Walk with Ana and Sam"

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

        // The hour grid's block opens it.
        openBlock(title, "The event's block on the hour grid didn't open the editor")
        assertEquals(title, Walk.find("event-title").text)
        assertTrue("No back arrow on the editor", Walk.exists("back"))

        Walk.type("event-title", renamed)
        Walk.bringIntoView("event-kind-Meeting")
        Walk.tap("event-kind-Meeting", "Meeting kind")
        save("70-event-edit")
        Walk.bringIntoView("event-save")
        Walk.tap("event-save", "Save button")
        Walk.waitUntilGone("event-save", "The editor stayed open after Save")

        openBlock(renamed, "The renamed event's block didn't open the editor")
        assertEquals(renamed, Walk.find("event-title").text)
        Walk.bringIntoView("event-kind-Meeting")
        assertTrue("The new kind wasn't kept", Walk.find("event-kind-Meeting").isSelected)
        Walk.bringIntoView("event-delete")
        Walk.tap("event-delete", "Delete event")
        Walk.find("event-delete-confirm", "Delete confirmation", timeoutMs = 5_000)
        save("71-event-delete")
        Walk.tap("event-delete-confirm", "Delete confirmation")
        Walk.waitUntilGone("event-delete", "The editor stayed open after Delete")
        assertTrue(
            "The deleted event is still on the hour grid",
            Walk.device.wait(Until.gone(blockSelector(renamed)), 10_000) == true,
        )
        assertTrue(
            "The deleted event is still listed",
            Walk.device.wait(Until.gone(By.descStartsWith("$renamed, ")), 10_000) == true,
        )
        save("72-calendar-after-delete")
    }

    /** An hour-grid block ("zone-block-<id>"), read as "<title>, <start> to <end>". */
    private fun blockSelector(name: String): BySelector =
        By.res(Pattern.compile("zone-block-.*")).descStartsWith("$name, ")

    /**
     * Scrolls the Calendar until the event's block is in view, then taps it. A new event made late in the evening
     * starts tomorrow, so Tomorrow's chip is tried when today's grid hasn't got it.
     */
    private fun openBlock(name: String, message: String) {
        repeat(2) { attempt ->
            if (attempt == 1) {
                // The day chips sit at the top of the page; scroll back up to them.
                repeat(4) { Walk.dragPage(0.3, 0.8) }
                Walk.tap("calendar-day-1", "Tomorrow chip")
            }
            repeat(2) {
                // A slow emulator can open the editor after the wait below; don't go looking for the block again.
                if (Walk.exists("event-editor")) return
                val block = scrollToBlock(name) ?: return@repeat
                block.click()
                if (Walk.exists("event-editor", timeoutMs = 10_000)) return
            }
        }
        if (Walk.exists("event-editor", timeoutMs = 5_000)) return
        fail(message)
    }

    private fun scrollToBlock(name: String): UiObject2? {
        val selector = blockSelector(name)
        Walk.device.wait(Until.hasObject(selector), 5_000)
        val lowest = Walk.device.displayHeight * 0.7
        val highest = Walk.device.displayHeight * 0.15
        repeat(12) {
            val block = Walk.device.findObject(selector)
            when {
                block == null -> Walk.dragPage(0.6, 0.35)
                block.visibleBounds.centerY() > lowest -> Walk.dragPage(0.6, 0.35)
                block.visibleBounds.centerY() < highest -> Walk.dragPage(0.35, 0.6)
                else -> return block
            }
        }
        return Walk.device.findObject(selector)
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
