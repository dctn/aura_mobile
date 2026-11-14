package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home → Work (iOS WorkReviewWalkTests): the sessions list with the stub's Deep work session, rating it Hard, then the
 * Review inbox's two cards and answering the nap as a Nap.
 */
@RunWith(AndroidJUnit4::class)
class WorkReviewWalkTest : WalkTestCase() {
    @Test
    fun rateSessionAndReviewBreak() {
        openHome()

        // The sessions list sits under Calendar and Task, near the bottom of Home.
        Walk.bringIntoView("session-rate-s-deep")
        Walk.find("session-rate-s-deep", "No Rate it on the Deep work row")
        assertTrue("No Deep work row", Walk.exists("session-row-s-deep"))
        save("63-sessions-list")

        Walk.open("session-rate-s-deep", until = "session-rating-sheet", message = "The rating sheet didn't open")
        Walk.tap("difficulty-hard", "Hard")
        Walk.waitUntilGone("session-rating-sheet", "The rating sheet didn't close")

        val rated = Walk.find("session-rating-s-deep", "The row doesn't show its rating")
        val label = labelOf(rated)
        assertTrue("The row shows $label, not Hard", label.contains("Hard"))
        Walk.waitUntilGone("session-rate-s-deep", "Rate it is still on the row")
        save("64-session-rated")

        // The inbox is under the list: the meeting and the nap.
        Walk.bringIntoView("review-happened-yes-b-sync")
        Walk.find("review-happened-yes-b-sync", "No Team sync card")
        Walk.bringIntoView("review-card-e1-review")
        assertTrue("No 10 Min nap card", Walk.exists("review-card-e1-review"))
        Walk.bringIntoView("review-happened-yes-b-sync")
        save("65-review-inbox")

        Walk.bringIntoView("review-happened-yes-e1-review")
        Walk.tap("review-happened-yes-e1-review", "Yes on the nap")
        Walk.find("review-type-nap-e1-review", "No break types after Yes")
        Walk.bringIntoView("review-type-nap-e1-review")
        Walk.tap("review-type-nap-e1-review", "Nap")
        save("66-review-recovery-type")

        Walk.bringIntoView("review-save-e1-review")
        Walk.tap("review-save-e1-review", "Save")
        Walk.waitUntilGone("review-card-e1-review", "The nap card is still there after Save")
        Walk.bringIntoView("review-card-b-sync")
        assertTrue("The Team sync card went too", Walk.exists("review-card-b-sync"))
    }

    /** Home, scrolled down past Insights and Today, so the Work section is close. */
    private fun openHome() {
        Walk.openTab("Home")
        Walk.findText("Insights", timeoutMs = 20_000)
        Walk.find("home-work-calendar", "Not found: Calendar row")
        repeat(3) { Walk.dragPage(0.7, 0.2) }
    }

    /** The chip's words: its description ("Rated Hard") or its text. */
    private fun labelOf(element: UiObject2): String =
        (listOfNotNull(element.contentDescription, element.text) + element.children.mapNotNull { it.text }).joinToString(" ")
}
