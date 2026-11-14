package info.ibtaura.auraclarus.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.Difficulty
import info.ibtaura.aura.core.SessionCapacity
import info.ibtaura.aura.core.SessionDetail
import info.ibtaura.aura.core.SessionTrace
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.ZoneText
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.home.HomeRetryLine
import info.ibtaura.auraclarus.run.DifficultyPicker
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One session in full: what it was and when, the minutes it held, what it did to capacity, the heart rate and stress
 * through it, its notes, and (for an ended focus session) its rating. A session the server no longer has shows a
 * calm "gone" page.
 */
@Composable
fun SessionDetailScreen(app: AppState, nav: AuraNav, sessionId: String) {
    var detail by remember(sessionId) { mutableStateOf<SessionDetail?>(null) }
    var gone by remember(sessionId) { mutableStateOf(false) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    var retry by remember(sessionId) { mutableIntStateOf(0) }
    var reload by remember(sessionId) { mutableIntStateOf(0) }

    var trace by remember(sessionId) { mutableStateOf<SessionTrace?>(null) }
    var traceFailed by remember(sessionId) { mutableStateOf(false) }
    var traceRetry by remember(sessionId) { mutableIntStateOf(0) }

    var saving by remember(sessionId) { mutableStateOf(false) }
    var ratingText by remember(sessionId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId, retry, reload) {
        failed = false
        try {
            detail = app.api.session(sessionId)
            gone = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if ((e as? AuraError.Server)?.status == 404) {
                gone = true
            } else {
                app.note(e)
                // A quiet reload after rating keeps what's showing.
                failed = detail == null
            }
        }
    }

    LaunchedEffect(sessionId, traceRetry) {
        traceFailed = false
        try {
            trace = app.api.sessionTrace(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            traceFailed = true
        }
    }

    fun rate(difficulty: Difficulty) {
        val current = detail ?: return
        if (saving || current.record.difficulty == difficulty) return
        val before = current
        saving = true
        ratingText = null
        detail = current.copy(record = current.record.copy(difficulty = difficulty))
        scope.launch {
            try {
                val updated = app.api.setDifficulty(sessionId, difficulty)
                detail = detail?.let { it.copy(record = it.record.copy(difficulty = updated.difficulty ?: difficulty)) }
                app.dataVersion += 1
                // The rating can change the capacity charge: read the session again, quietly.
                reload += 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.note(e)
                detail = before
                ratingText = when ((e as? AuraError)?.serverCode) {
                    "session_not_ended" -> "This session is still running. Rate it once it ends."
                    "not_focus_session" -> "Only focus sessions are rated."
                    else -> "Couldn't save that. Try again."
                }
            } finally {
                saving = false
            }
        }
    }

    val shown = detail
    AuraPage(Modifier.testTag("session-detail"), title = shown?.let { SessionWords.kindName(it.record.kind) } ?: "Session",
        onBack = { nav.pop() }, spacing = 14.dp) {
        when {
            gone -> GoneState { nav.pop() }
            shown == null && failed -> AuraCard(padding = 20.dp) {
                HomeRetryLine("Couldn't load this session.", { retry += 1 }, Modifier.testTag("session-detail-retry"))
            }
            shown == null -> SessionsSpinner(Modifier.padding(top = 40.dp).testTag("session-detail-loading"))
            else -> {
                HeroCard(shown)
                StatTiles(shown)
                CapacityCard(shown.capacity, shown.record.kind)
                TraceCard(trace, traceFailed) { traceRetry += 1 }
                NotesCard(shown)
                if (shown.record.isFocus && shown.hasEnded) {
                    AuraCard(Modifier.testTag("session-detail-rating"), padding = 20.dp, spacing = 10.dp) {
                        Text("How hard was it?", style = AuraType.bodyMedium, color = Color.Black)
                        DifficultyPicker(shown.record.difficulty) { rate(it) }
                        ratingText?.let {
                            Text(it, style = AuraType.callout, color = AuraColors.red, modifier = Modifier.testTag("session-detail-rating-error"))
                        }
                        Text("Your rating tunes how much a session like this costs your capacity.",
                            style = AuraType.caption, color = AuraColors.gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun GoneState(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp, start = 12.dp, end = 12.dp).testTag("session-gone"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(AuraColors.card), contentAlignment = Alignment.Center) {
            Icon(auraIcon("clock.arrow.circlepath"), contentDescription = null, tint = AuraColors.gray, modifier = Modifier.size(30.dp))
        }
        Text("This session isn't there any more.", style = AuraType.bodyMedium, color = Color.Black, textAlign = TextAlign.Center)
        Text("It may have been removed on another device.", style = AuraType.callout, color = AuraColors.gray, textAlign = TextAlign.Center)
        PillButton("Back", onBack, Modifier.testTag("session-gone-back"), style = PillStyle.Secondary)
    }
}

/** Badge, title, day, time and how it ended, then the XP it earned. */
@Composable
private fun HeroCard(session: SessionDetail) {
    val record = session.record
    AuraCard(Modifier.testTag("session-detail-hero"), padding = 20.dp, spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SessionKindBadge(record.kind, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(SessionWords.title(record), style = AuraType.heading, color = Color.Black)
                record.startedAt?.let { Text(SessionWords.longDay(it), style = AuraType.callout, color = AuraColors.gray) }
            }
        }
        val line = listOfNotNull(SessionWords.timeRange(record), SessionWords.outcome(record)).joinToString(" · ")
        if (line.isNotEmpty()) Text(line, style = AuraType.body, color = Color.Black)
        val xp = session.xpEarned
        if (xp != null && xp > 0) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(AuraColors.blue.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("session-detail-xp"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(auraIcon("star.fill"), contentDescription = null, tint = AuraColors.blue, modifier = Modifier.size(16.dp))
                Text(if (session.levelUp) "+$xp XP · Level up!" else "+$xp XP", style = AuraType.calloutMedium, color = AuraColors.blue)
            }
        }
    }
}

/** Active, protected, interruptions, planned against wall time. */
@Composable
private fun StatTiles(session: SessionDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("session-detail-stats")) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(ZoneText.hours(session.activeMinutes), "active", Modifier.weight(1f))
            StatTile(ZoneText.hours(session.protectedMinutes), "uninterrupted", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(session.interruptions.toString(), if (session.interruptions == 1) "interruption" else "interruptions", Modifier.weight(1f))
            val planned = session.plannedMinutes
            StatTile(
                if (planned != null && planned > 0) "${ZoneText.hours(session.wallMinutes)} / ${ZoneText.hours(planned)}" else ZoneText.hours(session.wallMinutes),
                if (planned != null && planned > 0) "of planned, pauses in" else "in all, pauses in",
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.background(AuraColors.card, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = AuraType.system(22, FontWeight.Bold), color = Color.Black, maxLines = 1)
        Text(label, style = AuraType.callout, color = AuraColors.gray, maxLines = 1)
    }
}

/** Capacity before and after, with the change and what it means. */
@Composable
private fun CapacityCard(capacity: SessionCapacity?, kind: String) {
    AuraCard(Modifier.testTag("session-detail-capacity"), padding = 20.dp, spacing = 10.dp) {
        Text("Capacity", style = AuraType.calloutMedium, color = AuraColors.gray)
        if (capacity == null) {
            Text("No capacity reading for this session.", style = AuraType.callout, color = AuraColors.footnote)
            return@AuraCard
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(SessionWords.points(capacity.before), style = AuraType.system(26, FontWeight.Bold), color = AuraColors.gray)
            Text("→", style = AuraType.heading, color = AuraColors.gray)
            Text(SessionWords.points(capacity.after), style = AuraType.system(26, FontWeight.Bold), color = Color.Black)
            Box(Modifier.weight(1f))
            val change = capacity.change
            Text(
                SessionWords.signed(change),
                style = AuraType.calloutSemibold,
                color = when {
                    change < -0.05 -> AuraColors.orange
                    change > 0.05 -> AuraColors.green
                    else -> AuraColors.gray
                },
                modifier = Modifier.testTag("session-detail-capacity-change"),
            )
        }
        val words = when {
            !capacity.charged -> "This session wasn't charged to your capacity."
            capacity.change > 0.05 -> "This break gave back ${SessionWords.points(capacity.change)} points."
            kind == "meeting" -> "Meetings draw on your capacity too: this one cost ${SessionWords.points(capacity.cost)} points."
            else -> "It cost ${SessionWords.points(capacity.cost)} points, by its active minutes and how hard it was."
        }
        Text(words, style = AuraType.caption, color = AuraColors.gray)
    }
}

/** Heart rate and stress through the session, from the band. */
@Composable
private fun TraceCard(trace: SessionTrace?, failed: Boolean, retry: () -> Unit) {
    AuraCard(Modifier.testTag("session-detail-trace"), padding = 20.dp, spacing = 12.dp) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Heart rate", style = AuraType.calloutMedium, color = AuraColors.gray, modifier = Modifier.weight(1f))
            trace?.avgHr?.let { Text("${it.roundToInt()} bpm average", style = AuraType.callout, color = AuraColors.red) }
        }
        when {
            failed -> HomeRetryLine("Couldn't load the heart rate.", retry)
            trace == null -> SessionsSpinner()
            trace.status == "pending" -> Text("Still being worked out. Check back later today.", style = AuraType.callout, color = AuraColors.footnote)
            trace.status != "ready" || (trace.heartRate.isEmpty() && trace.stress.isEmpty()) ->
                Text("No band data for this session.", style = AuraType.callout, color = AuraColors.footnote)
            else -> {
                if (trace.heartRate.isNotEmpty()) {
                    HeartRateChart(trace, Modifier.fillMaxWidth().height(120.dp))
                } else {
                    Text("No heart rate readings.", style = AuraType.callout, color = AuraColors.footnote)
                }
                if (trace.stress.isNotEmpty()) {
                    CardDivider()
                    Text("Stress", style = AuraType.calloutMedium, color = AuraColors.gray)
                    StressBars(trace, Modifier.fillMaxWidth().height(70.dp))
                }
                trace.stressDrop?.let { drop ->
                    val text = when {
                        drop > 0.5 -> "Stress fell ${drop.roundToInt()} points over the session."
                        drop < -0.5 -> "Stress rose ${(-drop).roundToInt()} points over the session."
                        else -> "Stress held steady over the session."
                    }
                    Text(text, style = AuraType.caption, color = AuraColors.gray)
                }
            }
        }
    }
}

@Composable
private fun NotesCard(session: SessionDetail) {
    AuraCard(Modifier.testTag("session-detail-notes"), padding = 20.dp, spacing = 8.dp) {
        Text("Notes", style = AuraType.calloutMedium, color = AuraColors.gray)
        if (session.notes.isEmpty()) {
            Text("No notes for this session.", style = AuraType.callout, color = AuraColors.footnote)
        } else {
            session.notes.forEachIndexed { index, note ->
                if (index > 0) CardDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    note.at?.let { Text(ZoneText.clock(it), style = AuraType.callout, color = AuraColors.gray) }
                    Text(note.text, style = AuraType.body, color = Color.Black, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
