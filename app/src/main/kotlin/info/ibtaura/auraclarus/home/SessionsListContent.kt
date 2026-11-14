package info.ibtaura.auraclarus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.Difficulty
import info.ibtaura.aura.core.SessionRecord
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraSheet
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.ZoneText
import info.ibtaura.auraclarus.run.DifficultyPicker
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * Home → Work: yesterday's and today's sessions, newest first (iOS SessionsList). A finished focus session asks how
 * hard it was, and the answer sets what it cost the day score. `showsHeader` false leaves out the "Sessions" heading
 * (the Sessions page has it in its back bar).
 */
@Composable
internal fun SessionsListContent(app: AppState, showsHeader: Boolean = true) {
    var sessions by remember { mutableStateOf<List<SessionRecord>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var rating by remember { mutableStateOf<SessionRecord?>(null) }

    LaunchedEffect(app.dataVersion, retry) {
        val today = LocalDate.now(DayKey.zone)
        try {
            sessions = app.api.sessions(from = DayKey.string(today.minusDays(1)), to = DayKey.string(today))
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = sessions == null
        }
    }

    Column(Modifier.fillMaxWidth().testTag("sessions-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showsHeader) {
            Text("Sessions", style = AuraType.heading, color = Color.Black, modifier = Modifier.semantics { heading() })
        }
        AuraCard(padding = 20.dp) {
            val list = sessions
            when {
                failed -> SessionsRetryLine("Couldn't load your sessions.") { retry += 1 }
                list == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
                list.isEmpty() -> Text("No sessions yesterday or today", style = AuraType.callout, color = AuraColors.footnote)
                else -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    list.forEachIndexed { index, session ->
                        if (index > 0) CardDivider()
                        SessionRow(session) { rating = session }
                    }
                }
            }
        }
    }

    rating?.let { session ->
        SessionRatingSheet(app, session, onDismiss = { rating = null }) { updated ->
            sessions = sessions?.let { replaceSession(it, updated) }
        }
    }
}

/** The answer may leave out what the list already knew. */
private fun replaceSession(list: List<SessionRecord>, updated: SessionRecord): List<SessionRecord> {
    val index = list.indexOfFirst { it.id == updated.id }
    if (index < 0) return list
    val old = list[index]
    val merged = updated.copy(title = updated.title ?: old.title, startedAt = updated.startedAt ?: old.startedAt)
    return list.toMutableList().also { it[index] = merged }
}

/** iOS HomeRetryLine: grey words, then a blue "Try again". */
@Composable
private fun SessionsRetryLine(text: String, onRetry: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = AuraType.callout, color = AuraColors.footnote)
        Text(
            "Try again",
            style = AuraType.calloutMedium,
            color = AuraColors.blue,
            modifier = Modifier.clickable(role = Role.Button, onClick = onRetry),
        )
    }
}

/** One session: its time and length, its title, how it ended, then its rating or "Rate it". */
@Composable
private fun SessionRow(session: SessionRecord, onRate: () -> Unit) {
    // A focus session that has ended can be rated; the backend refuses one still running.
    val canRate = session.isFocus && (session.endedAt != null || session.outcome != null)
    Row(
        Modifier.fillMaxWidth().testTag("session-row-${session.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(SessionsText.whenText(session), style = AuraType.system(11), color = AuraColors.gray)
            Text(SessionsText.title(session), style = AuraType.calloutMedium, color = Color.Black)
            SessionsText.outcome(session)?.let { Text(it, style = AuraType.caption, color = AuraColors.gray) }
        }
        Spacer(Modifier.width(0.dp))
        val difficulty = session.difficulty
        if (difficulty != null && session.isFocus) {
            Box(
                Modifier
                    .height(30.dp)
                    .clip(CircleShape)
                    .background(AuraColors.background)
                    .semantics { contentDescription = "Rated ${difficulty.label}" }
                    .testTag("session-rating-${session.id}")
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(difficulty.label, style = AuraType.system(13, androidx.compose.ui.text.font.FontWeight.SemiBold), color = AuraColors.blue)
            }
        } else if (canRate) {
            Box(
                Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(AuraColors.blue)
                    .clickable(role = Role.Button, onClick = onRate)
                    .testTag("session-rate-${session.id}")
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Rate it", style = AuraType.calloutMedium, color = Color.White)
            }
        }
    }
}

/** "How hard was this for you?" and the five chips. Picking one saves it and closes the sheet. */
@Composable
private fun SessionRatingSheet(app: AppState, session: SessionRecord, onDismiss: () -> Unit, onRated: (SessionRecord) -> Unit) {
    var picked by remember { mutableStateOf<Difficulty?>(null) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun rate(difficulty: Difficulty) {
        if (saving) return
        picked = difficulty
        saving = true
        errorText = null
        scope.launch {
            try {
                val updated = app.api.setDifficulty(session.id, difficulty)
                onRated(updated)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.note(e)
                errorText = when ((e as? AuraError)?.serverCode) {
                    "session_not_ended" -> "This session is still running. Rate it once it ends."
                    "not_focus_session" -> "Only focus sessions are rated."
                    else -> "Couldn't save that. Try again."
                }
                saving = false
            }
        }
    }

    AuraSheet(onDismiss = onDismiss, dismissible = !saving) {
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("session-rating-sheet")
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "How hard was this for you?",
                style = AuraType.system(24, androidx.compose.ui.text.font.FontWeight.Bold),
                color = Color.Black,
                modifier = Modifier.semantics { heading() },
            )
            Text("${SessionsText.title(session)}, ${SessionsText.whenText(session)}", style = AuraType.callout, color = AuraColors.gray)
            Box(Modifier.fillMaxWidth()) {
                DifficultyPicker(selected = picked ?: session.difficulty) { if (!saving) rate(it) }
            }
            Text(
                "Your answer sets what this session cost your day score.",
                style = AuraType.caption,
                color = AuraColors.gray,
            )
            errorText?.let { Text(it, style = AuraType.callout, color = AuraColors.red) }
        }
    }
}

/** The words a session row shows (iOS SessionsText). */
private object SessionsText {
    /** Its title, else its break type, else its kind. */
    fun title(session: SessionRecord): String {
        session.title?.takeIf { it.isNotEmpty() }?.let { return it }
        session.recoveryType?.let { return it.label }
        return when (session.kind) {
            "focus" -> "Focus"
            "recovery" -> "Break"
            "meeting" -> "Meeting"
            else -> session.kind.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    /** "09:00 · 50 min", or "Yesterday 15:00 · 20 min". */
    fun whenText(session: SessionRecord): String {
        val length = ZoneText.hours(session.minutes)
        val start: Instant = session.startedAt ?: return length
        val yesterday = start.atZone(DayKey.zone).toLocalDate() == LocalDate.now(DayKey.zone).minusDays(1)
        val day = if (yesterday) "Yesterday " else ""
        return "$day${ZoneText.clock(start)} · $length"
    }

    /** "Completed", or "Rest (no screen) · logged" for a break logged afterwards. */
    fun outcome(session: SessionRecord): String? {
        if (session.outcome == "logged") {
            val type = session.recoveryType ?: return "Logged"
            return "${type.label} · logged"
        }
        val word = when (session.outcome.orEmpty()) {
            "completed" -> "Completed"
            "stopped" -> "Stopped early"
            "pending" -> "Not finished"
            "rescheduled" -> "Rescheduled"
            else -> null
        }
        // A break that ran keeps its type in view when it has its own title.
        val type = session.recoveryType
        if (type != null && !session.title.isNullOrEmpty()) {
            return if (word == null) type.label else "${type.label} · $word"
        }
        return word
    }
}
