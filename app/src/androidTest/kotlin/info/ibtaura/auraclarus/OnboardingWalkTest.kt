package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import info.ibtaura.auraclarus.shell.LaunchFlags
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sleep-source onboarding (Android only): with `--uitesting-onboarding` the stub Health Connect starts without
 * the sleep permission, so the screen asks to connect. Connect grants it; with the stub night the check finds a past
 * night (ready), without it Samsung Health is installed (collecting). Both let the user in, onto Home.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingWalkTest : WalkTestCase() {
    override val flags = listOf(LaunchFlags.UITESTING, LaunchFlags.ONBOARDING, LaunchFlags.HEALTH_NIGHT)

    @Test
    fun connectWithPastNightsOpensHome() {
        connectAndEnter("69-onboarding-connect")
    }

    @Test
    fun connectWithSleepAppOnlyOpensHome() {
        scenario.close()
        scenario = Walk.launch(LaunchFlags.UITESTING, LaunchFlags.ONBOARDING)
        connectAndEnter("70-onboarding-collecting")
    }

    private fun connectAndEnter(shot: String) {
        Walk.find("onboarding", "No onboarding screen", timeoutMs = 20_000)
        Walk.find("onboarding-connect", "No Connect Health Connect button", timeoutMs = 20_000)
        save(shot)
        Walk.tap("onboarding-connect", "Connect Health Connect")
        Walk.find("tab-home", "Didn't reach the tabs after connecting", timeoutMs = 20_000)
        Walk.waitUntilGone("onboarding", "The onboarding screen is still up after connecting")
    }
}
