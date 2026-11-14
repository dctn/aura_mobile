package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.StaleObjectException
import info.ibtaura.auraclarus.shell.LaunchFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Quick check from Find a time (iOS QuickCheckWalkTests), on the stub with `--uitesting-short-check`: an 8-second
 * reaction test and one 3-digit memory row. A check that counts lets Find a time show its preview.
 */
@RunWith(AndroidJUnit4::class)
class QuickCheckWalkTest : WalkTestCase() {
    override val flags = listOf(LaunchFlags.UITESTING, LaunchFlags.NEEDS_CHECK, LaunchFlags.SHORT_CHECK)

    /** Runs the whole check, then Find a time plans with it. */
    @Test
    fun quickCheckFromFindATime() {
        openQuickCheck()
        save("53-quick-check-intro")
        Walk.tap("quick-check-start", "Start")

        // A tap before green is a false start and moves the next dot back, so the walk taps only on "Tap!".
        Walk.find("quick-check-reaction", "The reaction card")
        val started = System.currentTimeMillis()
        var taps = 0
        var shot = false
        while (System.currentTimeMillis() - started < 25_000 && !Walk.exists("quick-check-digits")) {
            val card = try {
                Walk.device.findObject(Walk.byTag("quick-check-reaction"))
            } catch (e: StaleObjectException) {
                null
            }
            val green = try {
                card?.contentDescription?.endsWith("Tap!") == true
            } catch (e: StaleObjectException) {
                false
            }
            if (card != null && green) {
                // The second green card is the screenshot (as iOS, "Tap!" on green); its answer is just slower.
                if (taps >= 1 && !shot) {
                    save("54-quick-check-reaction")
                    shot = true
                }
                try {
                    card.click()
                    taps += 1
                } catch (_: StaleObjectException) {
                }
            } else {
                Thread.sleep(50)
            }
        }
        if (!shot) save("54-quick-check-reaction")

        val digits = Walk.find("quick-check-digits", "The memory digits", timeoutMs = 15_000)
        val row = (digits.contentDescription ?: digits.text ?: "").split(" ").filter { it.isNotBlank() }
        assertFalse("The digits have no label", row.isEmpty())
        save("55-quick-check-memory")

        assertTrue("The keypad didn't show", Walk.exists("quick-check-key-0", timeoutMs = 15_000))
        for (digit in row) Walk.tap("quick-check-key-$digit", "Key $digit")

        Walk.find("quick-check-result", "Quick check result", timeoutMs = 15_000)
        save("56-quick-check-result")
        val verdict = Walk.find("quick-check-verdict", "Verdict", timeoutMs = 5_000).text.orEmpty()
        assertTrue("The check didn't count: $verdict", verdict.contains("This check counts"))

        Walk.tap("quick-check-done", "Done")
        Walk.find("find-time-confirm", "Find a time didn't show its preview after the check", timeoutMs = 15_000)
        Walk.waitUntilGone("find-time-plan-anyway", "The Quick check panel is still there")
    }

    /** Hide, then Stop, sends nothing and goes back to the Quick check panel. */
    @Test
    fun stopQuickCheck() {
        openQuickCheck()
        Walk.tap("quick-check-start", "Start")
        Walk.find("quick-check-reaction", "The reaction card")

        Walk.tap("quick-check-hide", "Hide")
        Walk.tap("quick-check-stop", "Stop the check? alert's Stop", timeoutMs = 5_000)

        Walk.waitUntilGone("quick-check-reaction", "The Quick check didn't close")
        Walk.find("find-time-plan-anyway", "The Quick check panel isn't back")
    }

    /** Opens Find a time from the Calendar, then the Quick check from its panel. */
    private fun openQuickCheck() {
        Walk.openTab("Home")
        Walk.findText("Insights", timeoutMs = 20_000)
        Walk.find("home-work-calendar", "Calendar row")
        Walk.dragPage(0.7, 0.2)
        Walk.dragPage(0.7, 0.2)
        Walk.open("home-work-calendar", until = "calendar-zone-line", message = "Calendar didn't open")

        Walk.find("calendar-find-time", "Find a time button")
        Walk.open("calendar-find-time", until = "find-time-do-check", message = "No Quick check panel")
        Walk.tap("find-time-do-check", "Do the Quick check")
        Walk.find("quick-check-intro", "The Quick check intro")
    }
}
