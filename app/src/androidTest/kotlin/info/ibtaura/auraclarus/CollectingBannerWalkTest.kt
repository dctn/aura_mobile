package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import info.ibtaura.auraclarus.shell.LaunchFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The estimate banner at the top of Today: with `--uitesting-collecting` the stub morning score is an estimate with
 * no night from last night, so Home says so; on plain stub data the score is a full one and there is no banner. The
 * rings' centre shows the live capacity score either way, with the morning one under the rings.
 */
@RunWith(AndroidJUnit4::class)
class CollectingBannerWalkTest : WalkTestCase() {
    override val flags = listOf(LaunchFlags.UITESTING, LaunchFlags.COLLECTING)

    @Test
    fun bannerWhileEstimatedThenGoneWhenFull() {
        openHome()
        Walk.find("home-collecting", "No estimate banner on Home")
        // Title and detail are merged for TalkBack, so the text sits on one node: match a part of it.
        val title = Walk.poll(10_000) { Walk.device.findObject(By.textContains("No sleep from last night")) }
        assertNotNull("The banner doesn't say there was no sleep from last night", title)
        assertCapacity("Capacity score now: 64.0, an estimate")
        save("78-home-collecting")

        scenario.close()
        scenario = Walk.launch(LaunchFlags.UITESTING)

        openHome()
        assertFalse("Estimate banner shows with a full score", Walk.exists("home-collecting", timeoutMs = 3_000))
        assertCapacity("Capacity score now: 76.0")
        Walk.findText("82.0 this morning")
    }

    /** The capacity number in the rings' centre, read by its spoken label. */
    private fun assertCapacity(spoken: String) {
        val centre = Walk.poll(10_000) { Walk.device.findObject(By.desc(spoken)) }
        assertNotNull("The rings' centre doesn't read \"$spoken\"", centre)
    }

    private fun openHome() {
        Walk.openTab("Home")
        Walk.findText("Insights", timeoutMs = 20_000)
    }
}
