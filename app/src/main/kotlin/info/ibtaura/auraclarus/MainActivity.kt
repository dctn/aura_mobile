package info.ibtaura.auraclarus

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import info.ibtaura.auraclarus.design.AuraTheme
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.LaunchFlags
import info.ibtaura.auraclarus.shell.RootScreen
import info.ibtaura.auraclarus.shell.SampleMode
import info.ibtaura.auraclarus.shell.SharedPrefsKeyValues
import info.ibtaura.auraclarus.shell.installPorts
import info.ibtaura.auraclarus.shell.makeServices
import info.ibtaura.auraclarus.perf.PerfMarks
import info.ibtaura.auraclarus.signin.GoogleSignIn
import info.ibtaura.auraclarus.signin.LocalGoogleSignIn

/** Holds the one AppState across activity re-creation; its scope lives as long as the app's task. */
class AppHolder(application: Application, flags: LaunchFlags) : AndroidViewModel(application) {
    val app: AppState = run {
        val (api, auth) = makeServices(application, flags)
        AppState(
            api = api,
            auth = auth,
            flags = flags,
            scope = viewModelScope,
            prefs = SharedPrefsKeyValues.forApp(application, flags),
        ).also { installPorts(it, application) }
    }

    /** "Continue with Google" (signin/GoogleSignIn.kt): lives as long as the AppState, so a redirect finds it. */
    val google = GoogleSignIn(application, app)
}

class MainActivity : ComponentActivity() {
    private val holder: AppHolder by viewModels {
        viewModelFactory { initializer { AppHolder(application, SampleMode.applyTo(LaunchFlags.from(intent), application)) } }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PerfMarks.install(this)
        // Light only, as the iOS app: dark bar icons even when the phone is in dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val app = holder.app
        // Started by GoogleRedirectActivity after a process death: the hosted sign-in's redirect is this intent.
        if (savedInstanceState == null) handleRedirect(intent)
        setContent {
            AuraTheme {
                // Test tags double as resource ids, so UiAutomator finds `By.res("signin-email")`.
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    CompositionLocalProvider(LocalGoogleSignIn provides holder.google) {
                        // Restores the session (RootScreen's first effect), then shows Sign in or the tabs.
                        RootScreen(app)
                    }
                }
            }
        }
    }

    /** The hosted sign-in's redirect, forwarded by GoogleRedirectActivity (CLEAR_TOP + SINGLE_TOP). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    override fun onPause() {
        super.onPause()
        holder.google.onPause()
    }

    override fun onResume() {
        super.onResume()
        // After onNewIntent, so a redirect is already handled and only a closed tab stops the spinner here.
        holder.google.onResume()
    }

    private fun handleRedirect(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        holder.google.onRedirect(this, data)
    }
}
