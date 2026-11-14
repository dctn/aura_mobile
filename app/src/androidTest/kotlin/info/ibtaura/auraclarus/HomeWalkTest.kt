package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home top to bottom, Calendar, the work hours form and Settings (iOS HomeWalkTests), plus the Home part of iOS
 * ScreenWalkTests. The stub signs in by itself, so sign-in can't be walked here.
 */
@RunWith(AndroidJUnit4::class)
class HomeWalkTest : WalkTestCase() {
    @Test
    fun opensHome() {
        Walk.openTab("Home")
        Walk.findText("Insights", timeoutMs = 20_000)
        save("01-home")
    }

    @Test
    fun walkHome() {
        Walk.openTab("Home")
        val insights = Walk.findText("Insights", timeoutMs = 20_000)
        // Burnout's line appears once its stub answer is in.
        Walk.device.wait(Until.hasObject(By.text("Higher possibility estimated")), 5_000)
        save("10-home-top")

        val top = insights.visibleBounds.top
        Walk.dragPage(0.7, 0.2)
        val moved = Walk.device.findObject(By.text("Insights"))
        assertTrue("Home didn't scroll", moved == null || moved.visibleBounds.top < top)
        save("11-home-mid")
        Walk.dragPage(0.7, 0.2)
        save("12-home-bottom")

        Walk.open("home-work-calendar", until = "calendar-zone-line", message = "No zone line on Calendar")
        save("13-work-calendar")

        Walk.dragPage(0.7, 0.2)
        save("13b-calendar-lower")

        Walk.open("calendar-work-hours", until = "work-hours-save", message = "The work hours form didn't open")
        save("15-work-hours")
        Walk.tap("work-hours-cancel", "Cancel on the work hours form")
        Walk.waitUntilGone("work-hours-save", "The work hours form didn't close")

        Walk.tap("back", "Back from Calendar")
        Walk.waitUntilGone("calendar-page", "Back didn't leave Calendar")

        Walk.openTab("Search")
        Walk.open("search-place-Settings", until = "settings-sign-out", message = "Settings didn't open")
        // The row's summary appears once the stub has answered.
        Walk.exists("settings-work-hours", timeoutMs = 5_000)
        save("14-settings")
    }
}
