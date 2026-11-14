package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import info.ibtaura.auraclarus.shell.LaunchFlags
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A new account's Calendar (iOS ZonesWalkTests): no work hours yet, the form opened from the zone card, then the page
 * after saving. It doesn't expect a ready day after saving: the suggested hours are Mon-Fri and CI can run on a weekend.
 */
@RunWith(AndroidJUnit4::class)
class ZonesWalkTest : WalkTestCase() {
    override val flags = listOf(LaunchFlags.UITESTING, LaunchFlags.NO_SCHEDULE)

    @Test
    fun setWorkHoursFromCalendar() {
        openCalendarFromHome(until = "calendar-set-hours")
        Walk.find("calendar-set-hours", "Set work hours button")
        save("16-calendar-no-schedule")

        Walk.tap("calendar-set-hours", "Set work hours button")
        Walk.find("work-hours-save", "The work hours form")
        save("17-work-hours-new")

        Walk.bringIntoView("work-hours-save")
        Walk.tap("work-hours-save", "Save on the work hours form")
        Walk.waitUntilGone("work-hours-save", "The form stayed open after Save")
        Walk.waitUntilGone("calendar-set-hours", "Set work hours stayed after saving")
        Walk.find("calendar-zone-line", "Zone line after saving")
        save("18-calendar-after-save")
    }

    /** Scrolls Home down to the Calendar row and opens it, until the element tagged `until` shows. */
    private fun openCalendarFromHome(until: String) {
        Walk.openTab("Home")
        Walk.findText("Insights", timeoutMs = 20_000)
        Walk.dragPage(0.7, 0.2)
        Walk.dragPage(0.7, 0.2)
        Walk.open("home-work-calendar", until = until, message = "Calendar didn't open")
    }
}
