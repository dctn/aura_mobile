package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import info.ibtaura.auraclarus.shell.LaunchFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

// A port of UITests/RunWalkTests.swift: Recovery, the mode pages and every run screen. Each test starts from a fresh
// launch, so a run left open by one can't get in the way of the next. Modes are reached by search: tiles past the
// edge of a shelf aren't composed.

@RunWith(AndroidJUnit4::class)
class RunWalkTest : WalkTestCase() {
    @Test
    fun recoveryAndDetailPages() {
        Walk.openTab("Recovery")
        val card = Walk.device.wait(Until.findObject(By.res(Pattern.compile("now-card-.*"))), 20_000)
        assertNotNull("No Now cards", card)
        val cardTag = card!!.resourceName
        save("20-recovery-top")

        Walk.dragPage(0.7, 0.3)
        save("21-recovery-mid")
        Walk.dragPage(0.3, 0.8)

        // The page may still be gliding back after the drag, and a tap then only stops it, so tap until it opens.
        Walk.open(cardTag, until = "mode-start", message = "No Start on the Now card's page")
        save("22-detail-now")
        Walk.tap("back", "the back button")

        RunWalks.openMode("nap-10", search = "ten-minute")
        save("23-detail-tile")
    }

    @Test
    fun breatheHideReopenAndEndEarly() {
        RunWalks.startMode("box", search = "box")
        Walk.find("run-end", "The breathing run didn't open")
        save("24-run-breathe")

        Walk.tap("run-hide", "Hide")
        Walk.find("mini-run-bar", "No bar above the tabs")
        save("25-mini-bar")

        Walk.tap("mini-run-bar", "the bar")
        Walk.tap("run-end", "End")
        Walk.find("run-end-session", "No end-early dialog")
        save("26-end-early")

        Walk.tap("run-end-session", "End session")
        Walk.find("mode-start", "Ending didn't go back to the page")
    }

    @Test
    fun focusCountdownThenTimer() {
        RunWalks.startMode("timer", search = "timer")
        // The 3-2-1 only lasts three seconds, so this saves whatever shows once it has had two to come up.
        Walk.exists("run-countdown-cancel", timeoutMs = 2_000)
        save("27-countdown")
        Walk.find("run-pause", "The timer never started", timeoutMs = 15_000)
        save("28-timer")
    }

    @Test
    fun nap() {
        RunWalks.startMode("nap-10", search = "ten-minute")
        Walk.find("run-pause", "The nap didn't open")
        save("29-nap")
    }

    @Test
    fun flow() {
        RunWalks.startMode("flow", search = "flow")
        Walk.find("run-pause", "Flow never started", timeoutMs = 15_000)
        save("30-flow")
    }

    @Test
    fun guided() {
        RunWalks.startMode("breath-awareness", search = "awareness")
        Walk.find("run-pause", "The meditation didn't open")
        save("31-guided")
    }

    @Test
    fun windDown() {
        RunWalks.startMode("wind-down", search = "wind")
        Walk.find("run-pause", "Wind-down didn't open")
        save("32-winddown")
    }
}

/**
 * The only run that reaches its end: `--uitesting-short-run` makes it last 3 seconds. A focus timer, so the Done sheet
 * asks how hard it was and shows the capacity line for the rating.
 */
@RunWith(AndroidJUnit4::class)
class RunDoneWalkTest : WalkTestCase() {
    override val flags = listOf(LaunchFlags.UITESTING, LaunchFlags.SHORT_RUN)

    @Test
    fun doneSheet() {
        RunWalks.startMode("timer", search = "timer")
        Walk.find("run-done", "No Done sheet", timeoutMs = 25_000)
        // The summary adds its saved line once the backend has answered.
        assertTrue(
            "The Done sheet never said Saved",
            Walk.device.wait(Until.hasObject(By.textContains("Saved")), 10_000) == true,
        )
        Walk.find("run-difficulty", "No difficulty question")
        Walk.tap("difficulty-hard", "Hard")
        assertTrue(
            "The capacity line never showed the rating",
            Walk.device.wait(Until.hasObject(By.res("run-capacity-line").textContains("You rated it Hard.")), 10_000) == true,
        )
        save("33-run-done")
        Walk.tap("run-done", "Done")
        Walk.find("mode-start", "Done didn't go back to the page")
    }
}

/** A session already running elsewhere: the start joins it, the alert asks, and stopping it starts this run's own. */
@RunWith(AndroidJUnit4::class)
class RunOtherSessionWalkTest : WalkTestCase() {
    override val flags = listOf(LaunchFlags.UITESTING, LaunchFlags.JOINED_SESSION)

    @Test
    fun otherSessionAlert() {
        RunWalks.startMode("nap-10", search = "ten-minute")
        Walk.find("other-session-stop", "No alert for the session already running", timeoutMs = 15_000)
        save("34-other-session")
        Walk.tap("other-session-stop", "Stop it and start mine")
        Walk.find("run-pause", "The run didn't carry on")
        // The second start isn't joined, so the alert mustn't come back.
        assertFalse("The alert came back after stopping the other session", Walk.exists("other-session-stop", timeoutMs = 3_000))
    }
}

/** The run walks' own helpers. */
internal object RunWalks {
    /** Searches the Recovery tab and opens the mode's page. */
    fun openMode(id: String, search: String) {
        Walk.openTab("Recovery")
        Walk.tap("recovery-search", "the search field", timeoutMs = 20_000)
        Walk.type("recovery-search", search)
        Walk.tap("mode-$id", "the $id tile")
        Walk.find("mode-start", "No Start on $id's page")
    }

    fun startMode(id: String, search: String) {
        openMode(id, search)
        Walk.tap("mode-start", "Start")
    }
}
