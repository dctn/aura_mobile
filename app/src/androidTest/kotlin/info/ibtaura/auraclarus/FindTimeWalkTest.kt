package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import info.ibtaura.auraclarus.shell.LaunchFlags
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Find a time on the Calendar page (iOS FindTimeWalkTests): the preview from the stub plan, adding it to the
 * calendar, and the Quick check panel that comes first with `--uitesting-needs-check`.
 */
@RunWith(AndroidJUnit4::class)
class FindTimeWalkTest : WalkTestCase() {
    /** Opens Find a time, checks the stub plan's blocks, warning and unplaced task, then adds it to the calendar. */
    @Test
    fun findATimePreviewAndConfirm() {
        openCalendarFromHome()
        Walk.find("calendar-find-time", "Find a time button")
        Walk.open("calendar-find-time", until = "find-time-confirm", message = "Find a time didn't open")

        Walk.find("find-time-block", "placed blocks in the preview")
        val blocks = FindTimeWalk.countBlocks()
        assertTrue("Expected 4 placed blocks, found $blocks", blocks >= 4)
        assertTrue("No recovery warning", FindTimeWalk.scrollUntil { Walk.exists("find-time-warning") })

        // The unplaced list sits at the bottom of the page; it only has to exist, not be fully on screen.
        assertTrue("No unplaced Book the dentist", FindTimeWalk.scrollUntil { FindTimeWalk.hasText("Book the dentist") })
        save("50-find-time-preview")

        Walk.tap("find-time-confirm", "Add to calendar")
        Walk.waitUntilGone("find-time-confirm", "The Find a time page didn't close")

        // The hour grid may start above 9:00, so look a little lower before giving up.
        var found = FindTimeWalk.hasText("Write the review (1/2)", timeoutMs = 10_000)
        var drags = 0
        while (!found && drags < 3) {
            Walk.dragPage(0.6, 0.4)
            drags += 1
            found = FindTimeWalk.hasText("Write the review (1/2)", timeoutMs = 2_000)
        }
        assertTrue("The plan didn't reach the calendar", found)
        save("51-calendar-planned")
    }

    /** With `--uitesting-needs-check` the Quick check panel shows first; Plan anyway shows the preview, Cancel closes it. */
    @Test
    fun quickCheckFirst() {
        relaunch(LaunchFlags.UITESTING, LaunchFlags.NEEDS_CHECK)

        openCalendarFromHome()
        Walk.find("calendar-find-time", "Find a time button")
        Walk.open("calendar-find-time", until = "find-time-plan-anyway", message = "No Quick check panel")
        assertTrue("No Do the Quick check button", Walk.exists("find-time-do-check", timeoutMs = 5_000))
        save("52-find-time-check")

        Walk.tap("find-time-plan-anyway", "Plan anyway")
        Walk.find("find-time-confirm", "Plan anyway didn't show the preview")

        Walk.tap("find-time-cancel", "Cancel")
        Walk.waitUntilGone("find-time-confirm", "The page didn't close")
    }

    /**
     * Today wants the Quick check first, but tomorrow doesn't: picking Tomorrow from the day row shows its plan, with
     * Book the dentist placed, and Add to calendar confirms that day.
     */
    @Test
    fun planTomorrow() {
        relaunch(LaunchFlags.UITESTING, LaunchFlags.NEEDS_CHECK)

        openCalendarFromHome()
        Walk.find("calendar-find-time", "Find a time button")
        Walk.open("calendar-find-time", until = "find-time-day-1", message = "Find a time didn't open")
        assertTrue("No Quick check panel for today", Walk.exists("find-time-plan-anyway", timeoutMs = 10_000))

        Walk.tap("find-time-day-1", "Tomorrow")
        Walk.find("find-time-confirm", "Tomorrow's preview didn't show")
        Walk.waitUntilGone("find-time-plan-anyway", "The Quick check panel is still there for tomorrow")
        Walk.find("find-time-block", "Tomorrow's blocks")
        assertTrue(
            "Book the dentist isn't placed tomorrow",
            FindTimeWalk.scrollUntil { FindTimeWalk.blockLabels().any { it.contains("Book the dentist") } },
        )
        save("57-find-time-tomorrow")

        Walk.tap("find-time-confirm", "Add to calendar")
        Walk.waitUntilGone("find-time-confirm", "The Find a time page didn't close")
        save("58-calendar-after-tomorrow")
    }

    private fun relaunch(vararg flags: String) {
        scenario.close()
        scenario = Walk.launch(*flags)
    }

    /** Home > Work > Calendar, as iOS `openCalendarFromHome`. */
    private fun openCalendarFromHome() {
        Walk.openTab("Home")
        Walk.findText("Insights", timeoutMs = 20_000)
        Walk.find("home-work-calendar", "Calendar row")
        Walk.dragPage(0.7, 0.2)
        Walk.dragPage(0.7, 0.2)
        Walk.open("home-work-calendar", until = "calendar-zone-line", message = "Calendar didn't open")
    }
}

/** Helpers shared by the Find a time and Quick check walks. */
internal object FindTimeWalk {
    /** True when some element's text (or merged text, or description) holds `text`. */
    fun hasText(text: String, timeoutMs: Long = 0): Boolean {
        val textHit = By.textContains(text)
        val descHit = By.descContains(text)
        if (timeoutMs > 0) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (Walk.device.hasObject(textHit) || Walk.device.hasObject(descHit)) return true
                Walk.device.wait(Until.hasObject(textHit), 500)
            }
        }
        return Walk.device.hasObject(textHit) || Walk.device.hasObject(descHit)
    }

    /** Checks `condition`, dragging the page up a little between tries (at most `drags` times). */
    fun scrollUntil(drags: Int = 4, condition: () -> Boolean): Boolean {
        if (condition()) return true
        repeat(drags) {
            Walk.dragPage(0.6, 0.4)
            if (condition()) return true
        }
        return false
    }

    /** What each `find-time-block` on screen reads: its merged text, or its children's texts joined. */
    fun blockLabels(): List<String> = try {
        Walk.device.findObjects(Walk.byTag("find-time-block")).map { label(it) }
    } catch (e: StaleObjectException) {
        emptyList()
    }

    /** The distinct placed blocks seen, scrolling down the preview once. */
    fun countBlocks(): Int {
        val seen = mutableSetOf<String>()
        seen += blockLabels()
        repeat(3) {
            if (seen.size >= 4) return seen.size
            Walk.dragPage(0.6, 0.4)
            seen += blockLabels()
        }
        // Back to the top, so the page reads as on iOS.
        repeat(3) { Walk.dragPage(0.35, 0.7) }
        return seen.size
    }

    private fun label(element: UiObject2): String {
        val own = listOfNotNull(element.text, element.contentDescription).joinToString(" ")
        val children = element.children.joinToString(" ") { label(it) }
        return "$own $children".trim()
    }
}
