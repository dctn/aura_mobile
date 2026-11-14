package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the app with no flags (live wiring, empty token store): it starts signed out, on Sign in, with no network call.
 */
@RunWith(AndroidJUnit4::class)
class LaunchWalkTest {
    @Test
    fun opens() {
        Walk.launch().use {
            Walk.find("signin-email", "Sign in email field", timeoutMs = 20_000)
            Walk.findText("Sign in")
            Shots.save("00-launch")
        }
    }
}
