package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Recovery tab's two break pills (iOS RecoveryBreakWalkTests): taking a break as a timed run, and adding one taken earlier. */
@RunWith(AndroidJUnit4::class)
class RecoveryBreakWalkTest : WalkTestCase() {
    @Test
    fun takeBreakStartsRun() {
        Walk.openTab("Recovery")
        Walk.tap("recovery-take-break", "Take a recovery break")
        Walk.find("break-sheet", "The break sheet didn't open")
        for (id in listOf("nap", "walk", "rest", "outside", "social")) {
            Walk.find("break-type-$id", "No $id in the break sheet")
        }
        save("59-take-break")

        Walk.tap("break-type-walk", "Walk")
        Walk.tap("break-length-20", "20 min")
        Walk.tap("break-start", "Start")
        Walk.find("run-pause", "The break run didn't open", timeoutMs = 15_000)
        save("60-recovery-break-run")
    }

    /** Under UI tests the sheet starts at 09:15 today, which overlaps the stub's 09:00 to 09:50 session. */
    @Test
    fun logBreakOverlap() {
        Walk.openTab("Recovery")
        Walk.tap("recovery-log-break", "Add a break I took")
        Walk.find("log-break-sheet", "The log sheet didn't open")
        Walk.tap("log-type-walk", "Walk")
        Walk.tap("log-day-today", "Today")
        assertTrue("No start time", Walk.exists("log-start-time"))
        assertTrue("No length", Walk.exists("log-minutes"))
        save("61-log-break")

        Walk.bringIntoView("log-save")
        Walk.tap("log-save", "Save")
        Walk.find("log-error", "The overlap wasn't shown")
        Walk.findText("That overlaps a session you already recorded.")
        save("62-log-overlap")

        // Yesterday at the same time overlaps nothing, so it goes in.
        Walk.bringIntoView("log-day-yesterday")
        Walk.tap("log-day-yesterday", "Yesterday")
        Walk.bringIntoView("log-save")
        Walk.tap("log-save", "Save")
        Walk.find("log-saved", "The break wasn't added")
    }
}
