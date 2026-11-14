package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search, a category, a metric and its two lists, for comparing with the Figma (iOS SearchWalkTests), plus the
 * Search tab shot from iOS ScreenWalkTests (03-search). Stub data: Movement has six rows with data.
 */
@RunWith(AndroidJUnit4::class)
class SearchWalkTest : WalkTestCase() {
    @Test
    fun walkTheSearchTab() {
        Walk.openTab("Search")
        Walk.find("search-field", "Search field", timeoutMs = 20_000)
        waitForTag("category-movement", "Search never listed its categories")
        save("03-search")
    }

    @Test
    fun walkSearchAndMetric() {
        Walk.openTab("Search")
        waitForTag("category-movement", "Search never listed its categories", timeoutMs = 20_000)
        save("40-search")
        swipeUp()
        save("41-search-lower")

        scrollToTop("category-movement")
        Walk.open("category-movement", until = "category-search-field", message = "Movement never opened")
        waitForTag("metric-walkDistanceKm", "Movement never listed its rows")
        save("42-movement")
        swipeUp()
        save("43-movement-lower")

        scrollToTop("metric-walkDistanceKm")
        Walk.open("metric-walkDistanceKm", until = "metric-range-1", message = "The metric page never opened")
        // The chart appears once the series loads (no assert: only the shot needs it).
        Walk.device.wait(Until.hasObject(By.desc("Walk Distance chart")), 10_000)
        save("44-metric")
        Walk.tap("metric-range-1", "1 Month")
        // The white lozenge is still sliding right after the tap.
        Thread.sleep(800)
        save("45-metric-range")
        Walk.tap("metric-range-2", "2 Months")
        Thread.sleep(800)
        save("45-metric-range-2")
        swipeUp()
        save("46-metric-lower")

        Walk.open("metric-row-all-data", until = "recorded-search-field", message = "All Recorded Data never opened")
        Walk.findText("All Recorded Data")
        save("47-recorded")
        Walk.tap("back", "Back")
        waitForTag("metric-row-sources", "The metric page never came back")

        Walk.open("metric-row-sources", until = "source-apple-health", message = "Sources never opened")
        Walk.findText("Sources")
        save("48-sources")
    }

    private fun waitForTag(tag: String, message: String, timeoutMs: Long = 10_000) {
        assertTrue(message, Walk.exists(tag, timeoutMs))
    }

    /** As iOS `open(_, swipingDown: true)`: drags the page down until the row is on screen, up to 6 times. */
    private fun scrollToTop(tag: String) {
        repeat(6) {
            if (Walk.exists(tag)) return
            Walk.dragPage(0.3, 0.75)
        }
    }

    /** As XCUITest's `swipeUp()`: one page-length drag in the right margin, clear of the tab bar. */
    private fun swipeUp() {
        Walk.dragPage(0.7, 0.25)
        Thread.sleep(300)
    }
}
