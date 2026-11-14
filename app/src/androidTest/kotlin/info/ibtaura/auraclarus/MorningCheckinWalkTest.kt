package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import info.ibtaura.auraclarus.shell.LaunchFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The morning check-in at the top of Today (iOS MorningCheckinWalkTests): with no Health Connect night it asks for
 * sleep times; with `--uitesting-health-night` launch sends the stub night, so it asks for alertness only. Other walks
 * start with the check-in already sent (no `--uitesting-checkin`).
 */
@RunWith(AndroidJUnit4::class)
class MorningCheckinWalkTest : WalkTestCase() {
    override val flags = listOf(LaunchFlags.UITESTING, LaunchFlags.CHECKIN)

    @Test
    fun checkinWithTimesThenHealthConnect() {
        openHome()
        Walk.find("morning-checkin", "No morning check-in on Home")
        Walk.find("checkin-fell-asleep", "No fell asleep time")
        assertTrue("No woke up time", Walk.exists("checkin-woke"))
        assertTrue("No alertness slider", Walk.exists("checkin-alertness"))
        assertTrue("No Use Health Connect button", Walk.exists("checkin-apple-health"))
        Walk.bringIntoView("checkin-send")
        save("67-checkin-sleep-times")

        Walk.tap("checkin-send", "Send")
        Walk.find("checkin-done", "The check-in didn't say it was sent")

        scenario.close()
        scenario = Walk.launch(LaunchFlags.UITESTING, LaunchFlags.HEALTH_NIGHT)

        openHome()
        Walk.find("morning-checkin", "No morning check-in after relaunch")
        Walk.waitUntilGone("checkin-fell-asleep", "Still asks when you fell asleep after the Health Connect night")
        assertFalse("Still asks when you woke up", Walk.exists("checkin-woke"))
        assertFalse("Still offers Health Connect when it's connected", Walk.exists("checkin-apple-health"))
        Walk.find("checkin-send", "No Send after relaunch")
        Walk.bringIntoView("checkin-send")
        save("68-checkin-apple-health")
    }

    private fun openHome() {
        Walk.openTab("Home")
        Walk.findText("Insights", timeoutMs = 20_000)
    }
}
