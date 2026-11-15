package info.ibtaura.auraclarus.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import info.ibtaura.aura.core.HealthMetric
import info.ibtaura.aura.core.HealthSources
import info.ibtaura.aura.core.SleepSourceBlock
import info.ibtaura.aura.core.SleepSourceState
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.health.SetupStep
import info.ibtaura.auraclarus.health.installedBandApps
import info.ibtaura.auraclarus.health.openApp
import info.ibtaura.auraclarus.health.openHealthConnectInstall
import info.ibtaura.auraclarus.health.openHealthConnectSettings
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.ConsentSheet

/**
 * Android onboarding: a signed-in user needs a sleep source (an app writing nightly sleep into Health Connect) before
 * the tabs. The check runs when the screen opens and on every resume, since the way past is usually in another app
 * (Play, Health Connect, the band's app). Ready or collecting lifts the gate in AppState, so RootScreen moves on to
 * the tabs by itself; those states only show for a moment here.
 */
@Composable
fun SleepSourceScreen(app: AppState) {
    val context = LocalContext.current
    var connecting by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    // Back from Play, Health Connect or the band's app: look again (not while the permission sheet is being answered;
    // Connect checks again itself when it returns).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes += 1 }
    LaunchedEffect(Unit) { app.runSleepSourceCheck() }
    LaunchedEffect(resumes) {
        if (resumes > 1 && !connecting) app.runSleepSourceCheck()
    }
    val bandApps = remember(resumes) { installedBandApps(context) }

    fun recheck() {
        app.launch { app.runSleepSourceCheck() }
    }

    fun connect() {
        connecting = true
        app.launch {
            try {
                app.connectSleepSource()
            } finally {
                connecting = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AuraPage(Modifier.testTag("onboarding")) {
            SectionTitle("Connect your sleep data")
            Text(OnboardingCopy.WHY, style = AuraType.callout, color = AuraColors.gray)

            val state = app.sleepSource
            val error = app.sleepSourceError
            when (state) {
                SleepSourceState.Checking -> CheckingCard()
                is SleepSourceState.Blocked -> when (state.reason) {
                    SleepSourceBlock.noHealthConnect -> NoHealthConnectCard()
                    SleepSourceBlock.installHealthConnect -> StepsCard(
                        installed = false,
                        bandApps = bandApps,
                        onOpen = { openApp(context, it) },
                    ) {
                        PillButton(
                            "Install Health Connect",
                            { openHealthConnectInstall(context) },
                            Modifier.testTag("onboarding-install"),
                            fills = true,
                        )
                    }
                    SleepSourceBlock.notConnected -> StepsCard(
                        installed = true,
                        bandApps = bandApps,
                        onOpen = { openApp(context, it) },
                    ) {
                        PillButton(
                            if (connecting) "Connecting..." else "Connect Health Connect",
                            ::connect,
                            Modifier.testTag("onboarding-connect"),
                            fills = true,
                            enabled = !connecting,
                        )
                    }
                    SleepSourceBlock.noSleepApp -> NoSleepAppCard(
                        bandApps = bandApps,
                        onOpen = { openApp(context, it) },
                        onCheckAgain = ::recheck,
                        onSettings = { openHealthConnectSettings(context) },
                    )
                }
                is SleepSourceState.Ready -> FoundCard(OnboardingCopy.ready(state.nights, state.source), "onboarding-ready")
                is SleepSourceState.Collecting -> FoundCard(OnboardingCopy.collecting(state.app), "onboarding-collecting")
            }

            if (error != null) {
                Text(error, style = AuraType.callout, color = AuraColors.red)
                PillButton(
                    "Try again",
                    ::recheck,
                    Modifier.testTag("onboarding-retry"),
                    style = PillStyle.Secondary,
                    fills = true,
                )
            }

            Text(
                "Sign out",
                style = AuraType.callout,
                color = AuraColors.blue,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { app.launch { app.signOut() } }
                    .padding(vertical = 12.dp)
                    .testTag("onboarding-sign-out"),
            )
        }
        if (app.needsConsent) ConsentSheet(app)
    }
}

@Composable
private fun CheckingCard() {
    AuraCard(modifier = Modifier.testTag("onboarding-checking")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
            Text("Checking your sleep data…", style = AuraType.bodyMedium, color = Color.Black)
        }
    }
}

@Composable
private fun NoHealthConnectCard() {
    AuraCard(modifier = Modifier.testTag("onboarding-unavailable")) {
        StatusRow("exclamationmark.circle.fill", AuraColors.gray, "Health Connect isn't available")
        Text(
            "This phone can't run Health Connect, so Aura can't read your sleep here.",
            style = AuraType.callout,
            color = Color.Black,
        )
    }
}

/** Install, sharing in the band's app, Connect: the button under the steps is the one for the step the user is on. */
@Composable
private fun StepsCard(
    installed: Boolean,
    bandApps: List<String>,
    onOpen: (String) -> Unit,
    button: @Composable () -> Unit,
) {
    AuraCard(spacing = 14.dp, modifier = Modifier.testTag("onboarding-steps")) {
        SetupStep(
            1,
            installed,
            "Install Health Connect",
            if (installed) "On this phone" else "Free from Google Play. Built in from Android 14.",
        )
        SharingStep(2, bandApps, onOpen)
        SetupStep(3, false, "Connect Aura", "Allow Aura to read your sleep in Health Connect.")
        CardDivider()
        button()
    }
}

@Composable
private fun SharingStep(number: Int, bandApps: List<String>, onOpen: (String) -> Unit) {
    val band = bandApps.firstOrNull()
    SetupStep(
        number,
        false,
        "Turn on sharing in your watch or band's app",
        if (band != null) {
            "In ${HealthSources.name(band)}, open Settings > Health Connect and allow sleep."
        } else {
            "Install the app your watch, band or phone saves sleep to, then turn on Health Connect in its settings."
        },
    ) {
        if (band != null) {
            PillButton(
                "Open ${HealthSources.name(band)}",
                { onOpen(band) },
                Modifier.fillMaxWidth().testTag("onboarding-open-app"),
                style = PillStyle.Secondary,
                fills = true,
            )
        }
    }
}

@Composable
private fun NoSleepAppCard(
    bandApps: List<String>,
    onOpen: (String) -> Unit,
    onCheckAgain: () -> Unit,
    onSettings: () -> Unit,
) {
    AuraCard(spacing = 14.dp, modifier = Modifier.testTag("onboarding-no-sleep-app")) {
        StatusRow("exclamationmark.circle.fill", AuraColors.orange, "No app is saving your sleep yet.")
        Text("Apps that work:", style = AuraType.callout, color = AuraColors.gray)
        Text(OnboardingCopy.exampleApps(), style = AuraType.callout, color = Color.Black)
        CardDivider()
        SharingStep(1, bandApps, onOpen)
        PillButton("Check again", onCheckAgain, Modifier.testTag("onboarding-check-again"), fills = true)
    }
    Footnote(
        "Already sharing?",
        linkTitle = "Health Connect settings",
        onLink = onSettings,
        modifier = Modifier.testTag("onboarding-hc-settings"),
    )
}

@Composable
private fun FoundCard(line: String, tag: String) {
    AuraCard(modifier = Modifier.testTag(tag)) {
        StatusRow("checkmark.circle.fill", AuraColors.green, line)
    }
}

@Composable
private fun StatusRow(symbol: String, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(auraIcon(symbol), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = AuraType.bodyMedium, color = Color.Black)
        }
    }
}

/** The onboarding screen's words that depend on what the check found. */
object OnboardingCopy {
    const val WHY = "Aura's capacity score is built from your sleep. Connect the app your watch, band or phone saves sleep to."

    /** "Found 12 nights from Samsung Health"; without a known app, "Found 1 night in Health Connect". */
    fun ready(nights: Int, source: String?): String {
        val count = if (nights == 1) "1 night" else "$nights nights"
        return if (source != null) "Found $count from ${HealthSources.name(source)}" else "Found $count in Health Connect"
    }

    /** "Samsung Health is connected. Your sleep is collected from tonight." */
    fun collecting(app: String?): String {
        val who = app?.let { HealthSources.name(it) } ?: "Your sleep app"
        return "$who is connected. Your sleep is collected from tonight."
    }

    /** Every app that can write sleep, watches and bands first: "Oura, Whoop, Garmin Connect, ...". */
    fun exampleApps(): String =
        HealthSources.sleepCapable.sortedBy { HealthSources.rank(HealthMetric.sleep, it) }
            .joinToString(", ") { HealthSources.name(it) }
}
