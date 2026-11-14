package info.ibtaura.auraclarus.sessions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.SessionDetail
import info.ibtaura.aura.core.SessionFilter
import info.ibtaura.aura.core.SessionRecord
import info.ibtaura.aura.core.SessionTrace
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Chevron
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.ZoneText
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.hex
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The look shared by Session history and the session page: words, the kind badge, the row, the filter, the trace
// chart, the empty state and the CSV share.

/** The words a session shows (the same as Home's SessionsText, plus days and points). */
internal object SessionWords {
    /** Its title, else its break type, else its kind. */
    fun title(record: SessionRecord): String {
        record.title?.takeIf { it.isNotBlank() }?.let { return it }
        record.recoveryType?.let { return it.label }
        return kindName(record.kind)
    }

    fun kindName(kind: String): String = when (kind) {
        "focus" -> "Focus"
        "recovery" -> "Break"
        "meeting" -> "Meeting"
        "walk" -> "Walk"
        "brainstorm" -> "Brainstorm"
        else -> kind.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    /** "09:00–09:50", or "09:00" while it has no end. */
    fun timeRange(record: SessionRecord): String? {
        val start = record.startedAt ?: return null
        val end = record.endedAt ?: return ZoneText.clock(start)
        return ZoneText.range(start, end)
    }

    /** "09:00–09:50 · 50 min". */
    fun whenText(session: SessionDetail): String {
        val length = ZoneText.hours(session.activeMinutes)
        val range = timeRange(session.record) ?: return length
        return "$range · $length"
    }

    /** "Completed", "Stopped early", "Walk · logged"... */
    fun outcome(record: SessionRecord): String? {
        if (record.outcome == "logged") return record.recoveryType?.let { "${it.label} · logged" } ?: "Logged"
        return when (record.outcome.orEmpty()) {
            "completed" -> "Completed"
            "stopped" -> "Stopped early"
            "pending" -> "Not finished"
            "rescheduled" -> "Rescheduled"
            else -> if (record.endedAt == null && record.startedAt != null) "Running" else null
        }
    }

    /** The row's grey line: how it ended, its rating, its interruptions. */
    fun caption(session: SessionDetail): String? {
        val parts = mutableListOf<String>()
        outcome(session.record)?.let { parts.add(it) }
        session.record.difficulty?.let { if (session.record.isFocus) parts.add("Rated ${it.label}") }
        if (session.record.isFocus && session.record.difficulty == null && session.hasEnded) parts.add("Not rated")
        if (session.interruptions > 0) {
            parts.add(if (session.interruptions == 1) "1 interruption" else "${session.interruptions} interruptions")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault())
    private val longDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())

    /** "Today", "Yesterday", "Wednesday 12 Nov". */
    fun dayTitle(day: LocalDate, today: LocalDate = LocalDate.now(DayKey.zone)): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dayFormat.format(day)
    }

    /** "Wednesday 12 November 2030". */
    fun longDay(instant: Instant): String = longDayFormat.format(instant.atZone(DayKey.zone))

    /** "72.5". */
    fun points(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    /** "−0.8", "+1.2", "0.0". */
    fun signed(value: Double): String {
        val text = points(abs(value))
        return when {
            text == "0.0" -> text
            value < 0 -> "−$text"
            else -> "+$text"
        }
    }
}

/** The kind's colour: focus blue, meeting sky, breaks and walks green, brainstorm purple. */
internal fun sessionKindColor(kind: String): Color = when (kind) {
    "focus" -> AuraColors.runFocus
    "meeting" -> AuraColors.runMeeting
    "recovery", "walk" -> AuraColors.green
    "brainstorm" -> AuraColors.runFlow
    else -> AuraColors.gray
}

internal fun sessionKindSymbol(kind: String): String = when (kind) {
    "focus" -> "brain.head.profile"
    "meeting" -> "person.2.fill"
    "recovery" -> "leaf.fill"
    "walk" -> "figure.walk"
    "brainstorm" -> "sparkles"
    else -> "timer"
}

/** The kind's icon in a softly tinted circle. */
@Composable
internal fun SessionKindBadge(kind: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = sessionKindColor(kind)
    Box(
        modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(auraIcon(sessionKindSymbol(kind)), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.5f))
    }
}

/** One session in a day card: badge, title, time and length, how it went, chevron. Tagged `history-row-<id>`. */
@Composable
internal fun SessionHistoryRow(session: SessionDetail, onClick: () -> Unit) {
    val record = session.record
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("history-row-${record.id}")
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SessionKindBadge(record.kind)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(SessionWords.title(record), style = AuraType.calloutSemibold, color = Color.Black, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(SessionWords.whenText(session), style = AuraType.caption, color = AuraColors.gray, maxLines = 1)
            SessionWords.caption(session)?.let {
                Text(it, style = AuraType.caption, color = AuraColors.gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Chevron()
    }
}

/** iOS's segmented picker: All, Focus, Recovery, Meetings. Each segment is tagged `history-filter-<name>`. */
@Composable
internal fun SessionFilterPicker(selected: SessionFilter, onSelect: (SessionFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(hex(0x767680, 0.12f))
            .padding(2.dp)
            .selectableGroup()
            .testTag("history-filter"),
    ) {
        SessionFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .then(
                        if (isSelected) {
                            Modifier
                                .shadow(2.dp, RoundedCornerShape(8.dp), ambientColor = Color.Black.copy(alpha = 0.1f),
                                    spotColor = Color.Black.copy(alpha = 0.15f))
                                .background(Color.White, RoundedCornerShape(8.dp))
                        } else Modifier,
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(filter) }
                    .testTag("history-filter-${filter.name}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    filter.title,
                    style = AuraType.system(14, if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                    color = Color.Black,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A calm empty state: icon, one line, one action. */
@Composable
internal fun SessionsEmptyState(
    symbol: String,
    text: String,
    actionTitle: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(AuraColors.card), contentAlignment = Alignment.Center) {
            Icon(auraIcon(symbol), contentDescription = null, tint = AuraColors.indigo, modifier = Modifier.size(30.dp))
        }
        Text(text, style = AuraType.bodyMedium, color = AuraColors.gray, textAlign = TextAlign.Center)
        if (actionTitle != null) PillButton(actionTitle, onAction, Modifier.testTag("history-empty-action"), style = PillStyle.Secondary)
    }
}

/** The small grey spinner. */
@Composable
internal fun SessionsSpinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}

/**
 * The heart rate through the session as a line (gaps where the band had no reading), on a soft band between the
 * lowest and highest minute. `trace.heartRate` must not be empty.
 */
@Composable
internal fun HeartRateChart(trace: SessionTrace, modifier: Modifier = Modifier) {
    val points = trace.heartRate
    val values = points.mapNotNull { it.bpm }
    val low = values.minOrNull() ?: 0.0
    val high = values.maxOrNull() ?: 0.0
    val line = AuraColors.red
    val band = AuraColors.red.copy(alpha = 0.07f)
    val grid = AuraColors.divider
    val first = points.first().at.toEpochMilli()
    val last = points.last().at.toEpochMilli()
    val described = "Heart rate from ${low.toInt()} to ${high.toInt()} beats a minute"
    Canvas(modifier.fillMaxWidth().height(130.dp).clearAndSetSemantics { contentDescription = described }) {
        val padY = 8.dp.toPx()
        val span = max(high - low, 6.0)
        val mid = (high + low) / 2
        val bottomValue = mid - span / 2 - 2
        val topValue = mid + span / 2 + 2
        fun y(bpm: Double): Float = (padY + (topValue - bpm) / (topValue - bottomValue) * (size.height - 2 * padY)).toFloat()
        fun x(at: Instant): Float =
            if (last == first) size.width / 2 else ((at.toEpochMilli() - first).toFloat() / (last - first)) * size.width

        drawRoundRect(band, topLeft = Offset(0f, y(high)), size = Size(size.width, max(2f, y(low) - y(high))),
            cornerRadius = CornerRadius(6.dp.toPx()))
        listOf(high, low).forEach { value ->
            drawLine(grid, Offset(0f, y(value)), Offset(size.width, y(value)), strokeWidth = 1.dp.toPx())
        }
        val path = Path()
        var drawing = false
        points.forEach { point ->
            val bpm = point.bpm
            if (bpm == null) {
                drawing = false
            } else if (!drawing) {
                path.moveTo(x(point.at), y(bpm))
                drawing = true
            } else {
                path.lineTo(x(point.at), y(bpm))
            }
        }
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // A lone reading still shows.
        if (values.size == 1) {
            val only = points.first { it.bpm != null }
            drawCircle(line, radius = 3.dp.toPx(), center = Offset(x(only.at), y(only.bpm!!)))
        }
    }
}

/** Stress for each hour the session touched, as small bars 0-100, with the hour under each. */
@Composable
internal fun StressBars(trace: SessionTrace, modifier: Modifier = Modifier) {
    val hours = trace.stress
    val track = AuraColors.track
    val fill = AuraColors.burnout
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        hours.forEach { point ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val value = point.value
                val spoken = if (value == null) "no reading" else value.toInt().toString()
                Canvas(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = "Stress at ${point.hour}:00, $spoken" }) {
                    val radius = CornerRadius(6.dp.toPx())
                    drawRoundRect(track, size = size, cornerRadius = radius)
                    if (value != null) {
                        val fraction = (min(max(value, 0.0), 100.0) / 100.0).toFloat()
                        val height = max(4.dp.toPx(), fraction * size.height)
                        drawRoundRect(fill, topLeft = Offset(0f, size.height - height), size = Size(size.width, height),
                            cornerRadius = radius)
                    }
                }
                Text(String.format(Locale.ROOT, "%02d:00", point.hour), style = AuraType.system(11), color = AuraColors.gray)
            }
        }
    }
}

/** Longest CSV sent as text: a share intent's extras must stay well under the 1 MB binder limit. */
internal const val maxSharedCsvChars = 400_000

/**
 * Opens the share sheet with the CSV as text (EXTRA_TEXT), so no file provider is needed. False when it is too long
 * to send as text or nothing on the phone can take it.
 */
internal fun shareCsv(context: Context, fileName: String, csv: String): Boolean {
    if (csv.length > maxSharedCsvChars) return false
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        putExtra(Intent.EXTRA_TITLE, fileName)
        putExtra(Intent.EXTRA_TEXT, csv)
    }
    val chooser = Intent.createChooser(send, "Share your sessions")
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
