package info.ibtaura.auraclarus.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuthState
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraConfirmDialog
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.WorkHoursRow
import info.ibtaura.auraclarus.design.ZoneText
import info.ibtaura.auraclarus.health.HealthSettingsSection
import info.ibtaura.auraclarus.goals.rememberUnitSystem
import info.ibtaura.aura.core.UnitSystem
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import info.ibtaura.auraclarus.shell.SampleMode
import kotlinx.coroutines.CancellationException

/** Work hours, the account, the version and Sign out (Settings/SettingsView.swift). The Figma has no frame for it. */
@Composable
fun SettingsScreen(app: AppState, nav: AuraNav) {
    val context = LocalContext.current
    var confirmingSignOut by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var scheduleFailed by remember { mutableStateOf(false) }
    // The switch's new value while its confirm dialog shows.
    var confirmingSample by remember { mutableStateOf<Boolean?>(null) }

    // Runs again after the form saves, since saving bumps `dataVersion`.
    LaunchedEffect(app.dataVersion) {
        try {
            app.loadWorkSchedule()
            scheduleFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            scheduleFailed = !app.workScheduleLoaded
        }
    }

    val email = (app.authState as? AuthState.SignedIn)?.email?.takeIf { it.isNotEmpty() }
    // "09:00–18:00 · Mon–Fri · Lunch 13:00", "Not set", or nothing while it loads.
    val workHoursText: String? = when {
        app.workScheduleLoaded -> app.workSchedule?.let { "${ZoneText.schedule(it)} · Lunch ${it.lunchStart}" }
            ?: ZoneText.schedule(null)
        scheduleFailed -> "Couldn't load"
        else -> null
    }

    AuraPage(title = "Settings", onBack = { nav.pop() }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Work hours", style = AuraType.heading, color = Color.Black, modifier = Modifier.semantics { heading() })
            WorkHoursRow(
                title = "Work and lunch",
                value = workHoursText,
                onClick = { nav.push(Route.WorkHours) },
                modifier = Modifier.testTag("settings-work-hours"),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Preferences", style = AuraType.heading, color = Color.Black, modifier = Modifier.semantics { heading() })
            WorkHoursRow(
                title = "Goals and habits",
                value = null,
                onClick = { nav.push(Route.Goals) },
                modifier = Modifier.testTag("settings-goals"),
                symbol = "target",
                symbolColor = AuraColors.green,
            )
            val units = rememberUnitSystem(app)
            WorkHoursRow(
                title = "Units",
                value = if (units == UnitSystem.imperial) "Imperial (mi, lb)" else "Metric (km, kg)",
                onClick = { nav.push(Route.Units("Distance", "distanceKm", "km")) },
                modifier = Modifier.testTag("settings-units"),
                symbol = "chart.line.uptrend.xyaxis",
                symbolColor = AuraColors.gray,
            )
        }
        if (app.flags.isSample) {
            AuraCard(spacing = 6.dp) {
                Text("Health Connect", style = AuraType.bodyMedium, color = Color.Black)
                Text(
                    "Off while sample data is on. Sleep, steps and heart data are made up.",
                    style = AuraType.caption,
                    color = AuraColors.gray,
                )
            }
        } else {
            HealthSettingsSection(app)
        }
        if (SampleMode.allowed(context)) {
            SampleDataSection(on = app.flags.isSample, onChange = { confirmingSample = it })
        }
        AuraCard(spacing = 14.dp) {
            if (email != null) {
                InfoRow("Account", email)
                CardDivider()
            }
            InfoRow("Version", versionText(context))
        }
        PillButton(
            if (signingOut) "Signing out..." else "Sign out",
            onClick = { confirmingSignOut = true },
            modifier = Modifier.testTag("settings-sign-out"),
            style = PillStyle.Secondary,
            fills = true,
            enabled = !signingOut,
        )
    }

    confirmingSample?.let { turnOn ->
        AuraConfirmDialog(
            title = if (turnOn) "Use sample data?" else "Back to your account?",
            message = if (turnOn) {
                "The app restarts with weeks of made-up data. Changes you make stay on this phone until it restarts. " +
                    "Nothing is sent to your account or read from Health Connect."
            } else {
                "The app restarts on your own account. Sample changes are dropped."
            },
            confirmTitle = "Restart",
            onConfirm = {
                confirmingSample = null
                SampleMode.set(context, turnOn)
            },
            onDismiss = { confirmingSample = null },
            confirmTag = "settings-sample-confirm",
        )
    }

    if (confirmingSignOut) {
        AuraConfirmDialog(
            title = "Sign out of Aura Clarus?",
            message = "You'll need a new email code to sign in again.",
            confirmTitle = "Sign out",
            onConfirm = {
                confirmingSignOut = false
                signingOut = true
                app.launch {
                    app.signOut()
                    signingOut = false
                }
            },
            onDismiss = { confirmingSignOut = false },
            confirmTag = "settings-sign-out-confirm",
        )
    }
}

/** Debug builds: run the whole app on made-up data, to see how it behaves with weeks of use. */
@Composable
private fun SampleDataSection(on: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Sample data", style = AuraType.heading, color = Color.Black, modifier = Modifier.semantics { heading() })
        AuraCard(spacing = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use sample data", style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.weight(1f))
                Switch(
                    checked = on,
                    onCheckedChange = onChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = AuraColors.green, checkedThumbColor = Color.White),
                    modifier = Modifier.testTag("settings-sample"),
                )
            }
            Text(
                "Made-up sleep, steps, heart, sessions, tasks and logs, kept on this phone. For trying the app only.",
                style = AuraType.caption,
                color = AuraColors.gray,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = AuraType.caption, color = AuraColors.gray)
        Text(value, style = AuraType.bodyMedium, color = Color.Black)
    }
}

/** "0.1.0 (1)": versionName (versionCode). */
private fun versionText(context: Context): String {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val name = info?.versionName ?: "-"
    val code = info?.longVersionCode?.toString() ?: "-"
    return "$name ($code)"
}
