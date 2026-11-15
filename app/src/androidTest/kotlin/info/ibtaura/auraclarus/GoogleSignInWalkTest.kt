package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import info.ibtaura.auraclarus.shell.LaunchFlags
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Continue with Google" on stub sign-in: `--uitesting-signed-out` starts on Sign in, and the Google button signs in
 * at once with no browser (StubAuth), going the email sign-in's way onto the tabs.
 */
@RunWith(AndroidJUnit4::class)
class GoogleSignInWalkTest : WalkTestCase() {
    override val flags = listOf(LaunchFlags.UITESTING, LaunchFlags.SIGNED_OUT)

    @Test
    fun continueWithGoogleOpensHome() {
        Walk.find("signin-email", "Sign in email field", timeoutMs = 20_000)
        Walk.find("signin-google", "No Continue with Google button")
        Walk.findText("Continue with Google")
        save("79-signin-google")
        Walk.tap("signin-google", "Continue with Google")
        Walk.find("tab-home", "Didn't reach the tabs after Continue with Google", timeoutMs = 20_000)
        Walk.waitUntilGone("signin-google", "Sign in is still up after Continue with Google")
    }
}
