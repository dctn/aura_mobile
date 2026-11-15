package info.ibtaura.auraclarus

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import info.ibtaura.aura.core.StubApi
import info.ibtaura.auraclarus.shell.LaunchFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One account, many devices: with `--uitesting-remote-session` the stub says a "Deep work" session runs on the Mac.
 * With no Start tap, the phone takes it on (mini bar, "Started on your Mac"), pauses it on the server from here, follows
 * a resume done on the Mac, and lets it go when the Mac ends it. The sync loop checks every 15 s while a run shows.
 */
@RunWith(AndroidJUnit4::class)
class RemoteSessionWalkTest : WalkTestCase() {
    override val flags = listOf(LaunchFlags.UITESTING, LaunchFlags.REMOTE_SESSION)

    @Test
    fun adoptPauseResumeEndElsewhere() {
        Walk.openTab("Home")
        Walk.find("mini-run-bar", "The session running on the Mac never showed", timeoutMs = 20_000)
        assertTrue(
            "The mini bar doesn't say where the session started",
            Walk.device.wait(Until.hasObject(By.res("run-started-elsewhere").textContains("Started on your Mac")), 5_000) == true,
        )
        Walk.findText("Deep work")
        save("80-run-started-elsewhere")

        // Paused from this phone: the server's session pauses.
        Walk.tap("mini-run-pause", "Pause")
        Walk.findText("Paused", timeoutMs = 5_000)
        val paused = Walk.poll(10_000) { if (runBlocking { stub().activeSession() }?.isPaused == true) true else null }
        assertEquals("The pause didn't reach the server", true, paused)

        // Resumed on the Mac: the phone follows within a sync round.
        runBlocking { stub().resumeElsewhere() }
        assertTrue(
            "The phone never followed the resume on the Mac",
            Walk.device.wait(Until.gone(By.text("Paused")), SYNC_WAIT_MS) == true,
        )

        // Ended on the Mac: the phone closes it without sending anything.
        runBlocking { stub().endElsewhere() }
        assertTrue(
            "The run stayed after the Mac ended it",
            Walk.device.wait(Until.gone(By.res("mini-run-bar")), SYNC_WAIT_MS) == true,
        )
        assertFalse("The ended session came back", Walk.exists("mini-run-bar", timeoutMs = 3_000))
    }

    /** The app's StubApi, from the activity's AppHolder (created already, so no factory is needed). */
    private fun stub(): StubApi {
        var api: StubApi? = null
        scenario.onActivity { activity -> api = ViewModelProvider(activity)[AppHolder::class.java].app.api as StubApi }
        return api!!
    }

    private companion object {
        /** One 15 s sync round plus slack for a slow emulator. */
        const val SYNC_WAIT_MS = 25_000L
    }
}
