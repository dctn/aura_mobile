package info.ibtaura.auraclarus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AlertItem
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.Mode
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraSlider
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.color
import info.ibtaura.auraclarus.design.symbol
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Today: the morning check-in until it's sent, the mood check-in and today's open alerts (iOS HomeTodaySection).
 * `onStart` opens the alert's mode with the alert's title as its headline.
 */
@Composable
internal fun HomeTodaySection(app: AppState, onStart: (modeId: String, headline: String) -> Unit) {
    // The morning check-in was sent from this card, so it stays to say thanks.
    var checkinSentHere by rememberSaveable { mutableStateOf(false) }
    var alerts by remember { mutableStateOf<List<AlertItem>>(emptyList()) }
    // Skipped or started here. A reload can't bring them back while the backend catches up.
    val handled = remember { mutableStateListOf<String>() }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(app.dataVersion, retry) {
        val today = DayKey.string(Instant.now())
        try {
            alerts = app.api.alerts(today, today)
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = alerts.isEmpty()
        }
    }

    val today = DayKey.string(Instant.now())
    val openAlerts = alerts.filter { !it.done && it.id !in handled && it.date.startsWith(today) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionTitle("Today")
        if (checkinSentHere || !app.morningCheckinSent) {
            MorningCheckinCard(app, onSent = { checkinSentHere = true })
        }
        HomeMoodCard(app)
        openAlerts.forEach { alert ->
            HomeAlertCard(
                alert = alert,
                mode = alert.actionRef?.let { app.modeForAlert(it) },
                onSkip = {
                    // Hides at once and comes back if the backend refuses.
                    handled.add(alert.id)
                    scope.launch {
                        try {
                            app.api.markAlertDone(alert.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            app.note(e)
                            handled.remove(alert.id)
                        }
                    }
                },
                onStart = { mode ->
                    handled.add(alert.id)
                    onStart(mode.id, alert.title)
                    app.launch { runCatching { app.api.markAlertDone(alert.id) }.onFailure { app.note(it) } }
                },
            )
        }
        if (failed) {
            HomeRetryLine("Couldn't load today's alerts.", retry = { retry += 1 })
        }
    }
}

/** One alert. Without a known mode there's nothing to start, so only Skip shows. */
@Composable
private fun HomeAlertCard(alert: AlertItem, mode: Mode?, onSkip: () -> Unit, onStart: (Mode) -> Unit) {
    val word = mode?.shelf?.raw ?: "Reminder"
    val symbol = mode?.shelf?.symbol ?: "bell.fill"
    val color = mode?.shelf?.color ?: AuraColors.blue
    AuraCard(padding = 18.dp, spacing = 14.dp) {
        Row(
            Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(auraIcon(symbol), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(word, style = AuraType.calloutMedium, color = color)
        }
        Text(alert.title, style = AuraType.calloutMedium, color = Color.Black, modifier = Modifier.padding(horizontal = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Skip", onSkip, Modifier.weight(1f).testTag("home-alert-skip"), style = PillStyle.Secondary, fills = true)
            if (mode != null) {
                PillButton("Start", { onStart(mode) }, Modifier.weight(1f).testTag("home-alert-start"), fills = true)
            }
        }
    }
}

private enum class MoodStatus { Idle, Saving, Logged, Failed }

/** The mood slider. Releasing it logs once, in the background, and the page stays usable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeMoodCard(app: AppState) {
    var value by remember { mutableFloatStateOf(3f) }
    // What was last logged; the start position counts, so nothing logs until the slider moves.
    var lastLogged by remember { mutableStateOf<Float?>(3f) }
    var status by remember { mutableStateOf(MoodStatus.Idle) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun logMood() {
        if (value == lastLogged) return
        lastLogged = value
        status = MoodStatus.Saving
        attempt += 1
        val thisAttempt = attempt
        val mood = value.roundToInt()
        val at = Instant.now()
        scope.launch {
            try {
                app.api.logMood(mood, at)
                if (thisAttempt == attempt) status = MoodStatus.Logged
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.note(e)
                if (thisAttempt == attempt) {
                    lastLogged = null
                    status = MoodStatus.Failed
                }
            }
        }
    }

    val statusText = when (status) {
        MoodStatus.Idle -> null
        MoodStatus.Saving -> "Saving..."
        MoodStatus.Logged -> "Logged. Move it again if that changes."
        MoodStatus.Failed -> "Couldn't log that. Move the slider to try again."
    }

    AuraCard {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(auraIcon("brain.head.profile"), contentDescription = null, tint = AuraColors.orange, modifier = Modifier.size(20.dp))
            Text("Mood", style = AuraType.calloutMedium, color = AuraColors.orange)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("How are you feeling right now?", style = AuraType.calloutMedium, color = Color.Black, modifier = Modifier.weight(1f))
            Text(
                when (value.roundToInt()) { 1 -> "Very low"; 2 -> "Low"; 3 -> "Okay"; 4 -> "Good"; else -> "Great" },
                style = AuraType.calloutSemibold,
                color = AuraColors.blue,
            )
        }
        // Top-aligned so the icons sit level with the track, not with the track plus the scale under it.
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(auraIcon("tortoise.fill"), contentDescription = null, tint = AuraColors.gray, modifier = Modifier.padding(top = 9.dp).size(26.dp))
            AuraSlider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { logMood() },
                valueRange = 1f..5f,
                steps = 3,
                modifier = Modifier.weight(1f).testTag("home-mood-slider").semantics { contentDescription = "Mood" },
            )
            Icon(auraIcon("hare.fill"), contentDescription = null, tint = AuraColors.gray, modifier = Modifier.padding(top = 9.dp).size(26.dp))
        }
        if (statusText != null) {
            Text(statusText, style = AuraType.caption, color = AuraColors.gray)
        }
    }
}
