package info.ibtaura.auraclarus.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraSlider
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.health.HealthAvailability
import info.ibtaura.auraclarus.health.healthAvailability
import info.ibtaura.auraclarus.health.openHealthConnectInstall
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The morning check-in at the top of Today, until it's sent (iOS MorningCheckinCard): how alert you feel and, unless
 * Health Connect already sent last night, when you fell asleep and woke. Sending it shows a thank-you; [onSent] tells
 * Today, which keeps showing it until Home is next opened.
 */
@Composable
internal fun CheckinCard(app: AppState, onSent: () -> Unit) {
    var alertness by remember { mutableFloatStateOf(5f) }
    var fellAsleep by remember { mutableStateOf(LocalTime.of(23, 0)) }
    var wokeUp by remember { mutableStateOf(LocalTime.of(7, 0)) }
    // The profile's times fill the pickers once, so coming back to Home doesn't undo an edit.
    var scheduleLoaded by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // Read again each time the app comes back, so installing Health Connect shows the connect button.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes += 1
        onPauseOrDispose {}
    }
    val availability = remember(resumes, app.healthConnected) { healthAvailability(app) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Health Connect sent last night, so the times aren't asked.
    val asksTimes = !app.sleepSentToday
    val offersHealth = !app.healthConnected && availability == HealthAvailability.Available
    val offersInstall = !app.healthConnected && availability == HealthAvailability.NeedsInstall

    // The profile's usual bedtime and wake time fill the pickers; without them they stay at 23:00 and 07:00.
    LaunchedEffect(Unit) {
        if (scheduleLoaded) return@LaunchedEffect
        try {
            val schedule = app.api.sleepSchedule()
            scheduleLoaded = true
            if (schedule != null) {
                clockTime(schedule.bedtime)?.let { fellAsleep = it }
                clockTime(schedule.wakeTime)?.let { wokeUp = it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
        }
    }

    fun send() {
        if (sending) return
        sending = true
        errorText = null
        val level = alertness.roundToInt()
        val asleepAt = if (asksTimes) clockText(fellAsleep) else null
        val wokeAt = if (asksTimes) clockText(wokeUp) else null
        scope.launch {
            try {
                app.api.morningCheckin(alertness = level, fellAsleepAt = asleepAt, wokeAt = wokeAt, at = Instant.now())
                sent = true
                onSent()
                app.markMorningCheckinSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.note(e)
                errorText = "Couldn't send that. Try again."
            }
            sending = false
        }
    }

    fun connect() {
        if (connecting) return
        connecting = true
        errorText = null
        scope.launch {
            try {
                app.connectHealth()
                // The first connect sends the last 30 days; that outlives the card.
                app.launch { app.syncHealthIfNeeded() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                errorText = "Couldn't open Health Connect. Enter your times instead."
            }
            connecting = false
        }
    }

    AuraCard(Modifier.testTag("morning-checkin")) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Icon(auraIcon("sun.max.fill"), contentDescription = null, tint = AuraColors.orange, modifier = Modifier.size(19.dp))
            Text("Morning", style = AuraType.calloutMedium, color = AuraColors.orange)
        }
        if (sent) {
            Text(
                "Thanks — morning check-in sent.",
                style = AuraType.calloutMedium,
                color = Color.Black,
                modifier = Modifier.testTag("checkin-done"),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("How alert do you feel?", style = AuraType.calloutMedium, color = Color.Black, modifier = Modifier.weight(1f))
                    Text("${alertness.roundToInt()} of 9", style = AuraType.calloutSemibold, color = AuraColors.blue)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AuraSlider(
                        value = alertness,
                        onValueChange = { alertness = it.roundToInt().toFloat() },
                        valueRange = 1f..9f,
                        steps = 7,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Alertness" }
                            .testTag("checkin-alertness"),
                    )
                }
            }

            if (asksTimes) {
                Column {
                    CheckinTimeRow("When did you fall asleep?", fellAsleep, "checkin-fell-asleep") { fellAsleep = it }
                    CardDivider()
                    CheckinTimeRow("When did you wake up?", wokeUp, "checkin-woke") { wokeUp = it }
                }
            }

            if (offersHealth) {
                Row(
                    Modifier
                        .clickable(enabled = !connecting, role = Role.Button) { connect() }
                        .testTag("checkin-apple-health"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(auraIcon("heart.text.square.fill"), contentDescription = null, tint = AuraColors.red, modifier = Modifier.size(20.dp))
                    Text(
                        if (connecting) "Opening Health Connect..." else "Use Health Connect for sleep",
                        style = AuraType.calloutMedium,
                        color = AuraColors.blue,
                    )
                }
            } else if (offersInstall) {
                Text(
                    "Install Health Connect",
                    style = AuraType.calloutMedium,
                    color = AuraColors.blue,
                    modifier = Modifier
                        .clickable(role = Role.Button) { openHealthConnectInstall(context) }
                        .testTag("checkin-install-health-connect"),
                )
            }

            errorText?.let { Text(it, style = AuraType.callout, color = AuraColors.red) }

            PillButton(
                title = if (sending) "Sending..." else "Send",
                onClick = { send() },
                fills = true,
                enabled = !sending,
                modifier = Modifier.testTag("checkin-send"),
            )
        }
    }
}

@Composable
private fun CheckinTimeRow(title: String, time: LocalTime, tag: String, onChange: (LocalTime) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = AuraType.calloutMedium, color = Color.Black, maxLines = 2, modifier = Modifier.weight(1f))
        BetaTimeField(title, time, tag, onChange)
    }
}

private fun clockText(time: LocalTime): String = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)

/** "23:00" as a time, or null when it isn't a clock time. */
private fun clockTime(clock: String): LocalTime? {
    val parts = clock.split(":").mapNotNull { it.trim().toIntOrNull() }
    if (parts.size < 2 || parts[0] !in 0..23 || parts[1] !in 0..59) return null
    return LocalTime.of(parts[0], parts[1])
}
