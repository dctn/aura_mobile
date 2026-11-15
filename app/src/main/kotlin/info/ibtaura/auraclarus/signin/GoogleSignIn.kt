package info.ibtaura.auraclarus.signin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import androidx.core.net.toUri
import info.ibtaura.aura.core.HostedLaunch
import info.ibtaura.aura.core.HostedNext
import info.ibtaura.aura.core.HostedPending
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException

/** The Google sign-in of this activity's app, or null (no button) where none is provided. */
val LocalGoogleSignIn = staticCompositionLocalOf<GoogleSignIn?> { null }

/**
 * "Continue with Google": Cognito's hosted sign-in in a Custom Tab (core `HostedSignIn` has the URLs and the rules).
 *
 * 1. A tap makes a fresh PKCE verifier and state, kept here and in the private prefs file `aura_google_signin` (a
 *    process death while the tab is open must not lose them), then opens the authorize URL in a Custom Tab.
 * 2. Cognito redirects to `auraclarus://auth?code=..&state=..`; [GoogleRedirectActivity] catches it and hands it to
 *    MainActivity, which calls [onRedirect]. The state must match; the code and verifier are swapped for tokens
 *    (`AuthService.finishHostedSignIn`) and `app.authState` is set, as the email sign-in does, so onboarding or Home
 *    follow.
 * 3. Coming back with no redirect (the tab was closed) stops the spinner ([onPause], [onResume]).
 * 4. Cognito keeps its own browser session, which would sign the same Google account straight back in after a
 *    sign-out. So after a Google sign-in the next attempt first visits Cognito's /logout (ending that session), whose
 *    redirect to `auraclarus://auth/signout` continues to the authorize URL.
 *
 * UI tests and sample mode (StubAuth) sign in at once, with no browser. Never log the verifier, state or code.
 */
@Stable
class GoogleSignIn(context: Context, private val app: AppState) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        if (app.flags.isUiTesting || app.flags.isSample) "uitesting_google" else FILE,
        Context.MODE_PRIVATE,
    )

    /** True from the tap until the sign-in ends (signed in, failed or cancelled). */
    var busy by mutableStateOf(false)
        private set

    /** A short sentence for the sign-in screen, or null. */
    var errorText by mutableStateOf<String?>(null)
        private set

    private var pending: HostedPending? = loadPending()
    /** The tab is open and no redirect has come back yet. */
    private var awaitingBrowser = false
    /** The activity was paused for the tab, so the next resume without a redirect means it was closed. */
    private var leftForBrowser = false

    /** Shown only when the build has a Cognito domain (aura-dev.json `cognitoDomain`). */
    val available: Boolean get() = app.auth.hostedSignIn != null

    private val instant: Boolean get() = app.flags.isUiTesting || app.flags.isSample

    fun clearError() {
        errorText = null
    }

    /** The button. */
    fun start(activity: Activity) {
        val hosted = app.auth.hostedSignIn ?: return
        if (busy) return
        errorText = null
        if (instant) {
            exchange(code = "stub", verifier = "stub", remembersBrowser = false)
            return
        }
        open(activity, hosted.start(logOutFirst = prefs.getBoolean(BROWSER_SESSION, false)))
    }

    /** A VIEW intent's data from MainActivity. True when it was this sign-in's redirect. */
    fun onRedirect(activity: Activity, uri: Uri): Boolean {
        val hosted = app.auth.hostedSignIn ?: return false
        val next = hosted.handle(uri.toString(), pending)
        if (next == HostedNext.Ignore) return false
        awaitingBrowser = false
        when (next) {
            is HostedNext.Exchange -> {
                keep(null)
                exchange(next.code, next.verifier, remembersBrowser = true)
            }
            is HostedNext.Open -> {
                // Back from /logout: Cognito's browser session is gone.
                if (pending?.loggingOut == true) prefs.edit { remove(BROWSER_SESSION) }
                open(activity, next.launch)
            }
            HostedNext.Cancelled -> stop(null)
            is HostedNext.Fail -> stop(next.message)
            HostedNext.Ignore -> Unit
        }
        return true
    }

    fun onPause() {
        if (awaitingBrowser) leftForBrowser = true
    }

    /** Back in the app with no redirect: the person closed the tab. The attempt stays kept for a late redirect. */
    fun onResume() {
        if (awaitingBrowser && leftForBrowser) {
            awaitingBrowser = false
            leftForBrowser = false
            busy = false
        }
    }

    private fun open(activity: Activity, launch: HostedLaunch) {
        keep(launch.pending)
        busy = true
        awaitingBrowser = true
        leftForBrowser = false
        val uri = launch.url.toUri()
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, uri)
        } catch (_: ActivityNotFoundException) {
            // No browser that does Custom Tabs: a plain VIEW intent, else give up.
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                stop("No browser is available for Google sign-in.")
            }
        }
    }

    private fun exchange(code: String, verifier: String, remembersBrowser: Boolean) {
        busy = true
        errorText = null
        app.launch {
            try {
                val state = app.auth.finishHostedSignIn(code, verifier)
                if (remembersBrowser) prefs.edit { putBoolean(BROWSER_SESSION, true) }
                app.authState = state
            } catch (e: CancellationException) {
                busy = false
                throw e
            } catch (e: Throwable) {
                app.note(e)
                errorText = SignInCodeRules.plainMessage(e)
            }
            busy = false
        }
    }

    private fun stop(message: String?) {
        keep(null)
        awaitingBrowser = false
        busy = false
        errorText = message
    }

    private fun keep(next: HostedPending?) {
        pending = next
        // Committed now: the process can die as soon as the tab is up.
        prefs.edit(commit = true) {
            if (next == null) {
                remove(VERIFIER)
                remove(STATE)
                remove(RETRIED)
                remove(LOGGING_OUT)
            } else {
                putString(VERIFIER, next.verifier)
                putString(STATE, next.state)
                putBoolean(RETRIED, next.retried)
                putBoolean(LOGGING_OUT, next.loggingOut)
            }
        }
    }

    private fun loadPending(): HostedPending? {
        val verifier = prefs.getString(VERIFIER, null) ?: return null
        val state = prefs.getString(STATE, null) ?: return null
        return HostedPending(
            verifier = verifier,
            state = state,
            retried = prefs.getBoolean(RETRIED, false),
            loggingOut = prefs.getBoolean(LOGGING_OUT, false),
        )
    }

    private companion object {
        const val FILE = "aura_google_signin"
        const val VERIFIER = "verifier"
        const val STATE = "state"
        const val RETRIED = "retried"
        const val LOGGING_OUT = "logging_out"
        /** Set by a Google sign-in, cleared by a /logout round trip: Cognito may still hold a browser session. */
        const val BROWSER_SESSION = "browser_session"
    }
}
