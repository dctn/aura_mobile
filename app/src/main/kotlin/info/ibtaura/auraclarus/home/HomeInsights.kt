package info.ibtaura.auraclarus.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.CalendarEvent
import info.ibtaura.aura.core.ScoresToday
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Insights: three rings and the next two events of today (iOS HomeInsightsSection). */
@Composable
internal fun HomeInsightsSection(app: AppState) {
    var scores by remember { mutableStateOf<ScoresToday?>(null) }
    var scoresFailed by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf<List<CalendarEvent>?>(null) }
    var eventsFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(app.dataVersion, retry) {
        try {
            scores = app.api.scoresToday()
            scoresFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            scoresFailed = scores == null
        }
    }
    LaunchedEffect(app.dataVersion, retry) {
        val now = Instant.now()
        try {
            events = app.api.events(now, endOfToday(now))
            eventsFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            eventsFailed = events == null
        }
    }

    // Null until the events have loaded, so "Nothing else today" doesn't flash first.
    val upcoming = events?.let { loaded ->
        val now = Instant.now()
        val dayEnd = endOfToday(now)
        loaded.filter { it.end > now && it.start < dayEnd }.sortedBy { it.start }.take(2)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionTitle("Insights")
        AuraCard(padding = 20.dp, spacing = 12.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                HomeRings(scores, Modifier.padding(start = 9.dp))
                HomeUpcomingList(upcoming, Modifier.weight(1f))
            }
            if (scoresFailed || eventsFailed) {
                HomeRetryLine("Couldn't load all of this.", retry = { retry += 1 })
            }
        }
        Footnote(HomeCopy.footnote, linkTitle = HomeCopy.privacyLink)
    }
}

private fun endOfToday(now: Instant): Instant {
    val zone = ZoneId.systemDefault()
    return now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
}

/** Outer attention, middle memory, inner thinking: `/v1/scores` returns four and the Figma draws three. */
@Composable
private fun HomeRings(scores: ScoresToday?, modifier: Modifier = Modifier) {
    Box(modifier.size(127.dp), contentAlignment = Alignment.Center) {
        HomeRing("Attention", scores?.attention?.value, AuraColors.ringOuter, 127.dp)
        HomeRing("Memory", scores?.memory?.value, AuraColors.ringMiddle, 91.dp)
        HomeRing("Thinking", scores?.thinking?.value, AuraColors.ringInner, 53.dp)
    }
}

/** One ring on a pale track of its own colour. The fill starts at nine o'clock and runs clockwise. */
@Composable
private fun HomeRing(title: String, value: Double?, color: Color, diameter: Dp) {
    val lineWidth = 10.dp
    // 100 is the person's normal, so a full ring means at or above it.
    val fill = if (value == null) 0f else (value / 100).coerceIn(0.0, 1.0).toFloat()
    val spoken = if (value == null) "$title: not enough data yet" else "$title: ${value.roundToInt()}, where 100 is your normal"
    Canvas(Modifier.size(diameter - lineWidth).clearAndSetSemantics { contentDescription = spoken }) {
        val stroke = lineWidth.toPx()
        drawCircle(color.copy(alpha = 0.2f), radius = size.minDimension / 2, style = Stroke(stroke))
        if (fill > 0f) {
            drawArc(
                color,
                startAngle = 180f,
                sweepAngle = 360f * fill,
                useCenter = false,
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** The blue "Upcoming" header and up to two events. */
@Composable
private fun HomeUpcomingList(events: List<CalendarEvent>?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(auraIcon("checklist"), contentDescription = null, tint = AuraColors.blue, modifier = Modifier.size(18.dp))
            Text("Upcoming", style = AuraType.calloutMedium, color = AuraColors.blue)
        }
        if (events != null) {
            if (events.isEmpty()) {
                Text("Nothing else today", style = AuraType.callout, color = AuraColors.footnote)
            } else {
                events.forEach { HomeEventRow(it) }
            }
        }
    }
}
