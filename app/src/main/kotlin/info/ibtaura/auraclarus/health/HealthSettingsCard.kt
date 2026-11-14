package info.ibtaura.auraclarus.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings > Health Connect: whether it's connected, how many reads are allowed, when days last went to the server
 * and why the next read waits. Connect, Sync now and Permissions. Never shows a health value.
 */
@Composable
fun HealthSettingsSection(app: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf<Int?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(HealthSyncStatus()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val availability = healthAvailability(app)
    val port = app.health as? HealthConnectDayPort

    LaunchedEffect(app.healthConnected, refresh, app.dataVersion) {
        status = port?.status() ?: HealthSyncStatus()
        val reader = healthReader(context)
        if (reader == null || !reader.isAvailable) {
            granted = null
            return@LaunchedEffect
        }
        try {
            total = reader.requestedPermissions().size
            granted = reader.grantedPermissions().count { it in reader.requestedPermissions() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            granted = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Health Connect", style = AuraType.heading, color = Color.Black, modifier = Modifier.semantics { heading() })
        AuraCard(spacing = 14.dp, modifier = Modifier.testTag("settings-health")) {
            val connected = app.healthConnected && availability == HealthAvailability.Available
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    auraIcon(if (connected) "checkmark.circle.fill" else "exclamationmark.circle.fill"),
                    contentDescription = null,
                    tint = if (connected) AuraColors.green else AuraColors.gray,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            availability == HealthAvailability.NeedsInstall -> "Health Connect isn't installed"
                            availability == HealthAvailability.Unavailable -> "Not available on this phone"
                            connected -> "Connected"
                            else -> "Not connected"
                        },
                        style = AuraType.bodyMedium,
                        color = Color.Black,
                    )
                    val grantedText = granted?.let { g -> if (total > 0) "$g of $total kinds of data allowed" else null }
                    Text(
                        grantedText ?: "Sleep, steps, workouts and heart data from your phone",
                        style = AuraType.caption,
                        color = AuraColors.gray,
                    )
                }
            }
            if (connected) {
                CardDivider()
                StatusLine(
                    "Last sent",
                    status.lastSync?.let { at ->
                        // A read that worked but found nothing: Health Connect itself has no data for those days.
                        if (status.lastSent == 0) "${ago(at)} · no data found"
                        else "${ago(at)} · ${status.lastSent} day${if (status.lastSent == 1) "" else "s"}"
                    } ?: "Not yet",
                )
                StatusLine("History", "${status.coveredDays.coerceAtMost(30)} of the last 30 days")
                waitText(status)?.let { StatusLine("Next read", it) }
            }
            message?.let { Text(it, style = AuraType.caption, color = AuraColors.gray) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    availability == HealthAvailability.NeedsInstall -> PillButton(
                        "Install", { openHealthConnectInstall(context) }, Modifier.weight(1f), fills = true,
                    )
                    availability == HealthAvailability.Available && !app.healthConnected -> PillButton(
                        if (busy) "Connecting..." else "Connect",
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                try {
                                    app.connectHealth()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    message = "Health Connect didn't allow sleep. You can allow it under Permissions."
                                } finally {
                                    busy = false
                                    refresh += 1
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("settings-health-connect"),
                        fills = true,
                        enabled = !busy,
                    )
                    availability == HealthAvailability.Available -> PillButton(
                        if (busy) "Syncing..." else "Sync now",
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                try {
                                    val ran = port?.syncNow() ?: false
                                    message = if (ran) null else "Health Connect asked Aura to slow down. It reads again on its own after the wait."
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    app.note(e)
                                    message = "Couldn't send the days just now. It tries again on its own."
                                } finally {
                                    busy = false
                                    refresh += 1
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("settings-health-sync"),
                        fills = true,
                        enabled = !busy,
                    )
                }
                if (availability == HealthAvailability.Available) {
                    PillButton(
                        "Permissions",
                        { openHealthConnectSettings(context) },
                        Modifier.weight(1f).testTag("settings-health-permissions"),
                        style = PillStyle.Secondary,
                        fills = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AuraType.callout, color = AuraColors.gray, modifier = Modifier.weight(1f))
        Text(value, style = AuraType.calloutMedium, color = Color.Black)
    }
}

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun ago(at: Instant): String {
    val minutes = Duration.between(at, Instant.now()).toMinutes()
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "Today ${clock.format(at.atZone(ZoneId.systemDefault()))}"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

private fun waitText(status: HealthSyncStatus): String? {
    val next = status.nextTry ?: return null
    val at = clock.format(next.atZone(ZoneId.systemDefault()))
    return when (status.waitReason) {
        HealthConnectDayPort.WAIT_RATE_LIMIT -> "$at (Health Connect limit)"
        HealthConnectDayPort.WAIT_NO_ROUTE -> "$at (server not ready)"
        HealthConnectDayPort.WAIT_FAILURE -> "$at (after an error)"
        else -> at
    }
}

/** Health Connect's own permission screen: the system settings page on 14+, the app before that. */
private fun openHealthConnectSettings(context: Context) {
    val action = if (Build.VERSION.SDK_INT >= 34) {
        "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"
    } else {
        HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS
    }
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) intent.putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
