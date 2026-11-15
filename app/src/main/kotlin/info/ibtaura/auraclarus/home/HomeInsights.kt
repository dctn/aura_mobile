package info.ibtaura.auraclarus.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.ibtaura.aura.core.CalendarEvent
import info.ibtaura.aura.core.MorningOpening
import info.ibtaura.aura.core.ScoresToday
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.CacheKeys
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Insights: three rings and the next two events of today (iOS HomeInsightsSection). Tapping an event calls
 * `onEventClick` (Home opens it in the event editor).
 */
@Composable
internal fun HomeInsightsSection(app: AppState, onEventClick: ((CalendarEvent) -> Unit)? = null) {
    // Both start from the screen cache, so a return to Home draws at once and refreshes quietly.
    var scores by remember { mutableStateOf(app.cached<ScoresToday>(CacheKeys.SCORES)) }
    var scoresFailed by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf(app.cachedCalendarEvents()) }
    var eventsFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(app.dataVersion, retry) {
        try {
            scores = app.cache.load(CacheKeys.SCORES) { app.api.scoresToday() }
            scoresFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            scoresFailed = scores == null
        }
    }
    LaunchedEffect(app.dataVersion, retry) {
        try {
            // Calendar's range (yesterday to 13 days ahead), so opening Calendar next needs no new request; today is
            // picked out below. It also remembers the events, so the editor finds a tapped one by its id.
            events = app.loadCalendarEvents()
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
                Column(
                    Modifier.padding(start = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeRings(scores, app.morningOpening)
                    HomeMorningLine(app.morningOpening)
                }
                HomeUpcomingList(upcoming, onEventClick, Modifier.weight(1f))
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

/**
 * Outer attention, middle memory, inner thinking: `/v1/scores` returns four and the Figma draws three. This morning's
 * live capacity score sits in the centre (gray while it is an estimate; the Today banner says why).
 */
@Composable
private fun HomeRings(scores: ScoresToday?, opening: MorningOpening?, modifier: Modifier = Modifier) {
    Box(modifier.size(127.dp), contentAlignment = Alignment.Center) {
        HomeRing("Attention", scores?.attention?.value, AuraColors.ringOuter, 127.dp)
        HomeRing("Memory", scores?.memory?.value, AuraColors.ringMiddle, 91.dp)
        HomeRing("Thinking", scores?.thinking?.value, AuraColors.ringInner, 53.dp)
        HomeCapacityCentre(opening)
    }
}

/**
 * The live capacity score (the morning score after today's costs and give-backs) in the inner ring's hole; the morning
 * score when Capacity sent no live one; a dash until it has loaded.
 */
@Composable
private fun HomeCapacityCentre(opening: MorningOpening?) {
    // One decimal, so a session's small cost (about 1 point an hour) shows at once.
    val score = (opening?.current ?: opening?.score)?.let(::oneDecimal)
    val estimate = opening?.estimated == true
    val spoken = when {
        score == null -> "Capacity score: not loaded yet"
        estimate -> "Capacity score now: $score, an estimate"
        else -> "Capacity score now: $score"
    }
    Text(
        score ?: "–",
        style = AuraType.calloutSemibold.copy(fontSize = 14.sp),
        maxLines = 1,
        softWrap = false,
        color = if (score == null || estimate) AuraColors.gray else Color.Black,
        modifier = Modifier.testTag("home-capacity").clearAndSetSemantics { contentDescription = spoken },
    )
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

/** "88 this morning" under the rings, so the live number in the centre has its starting point. */
@Composable
private fun HomeMorningLine(opening: MorningOpening?) {
    val morning = opening?.score?.let(::oneDecimal) ?: return
    if (opening.current == null) return
    Text(
        "$morning this morning",
        style = AuraType.caption,
        color = AuraColors.gray,
        modifier = Modifier.testTag("home-capacity-morning"),
    )
}

/** "87.8": the capacity scale with one decimal, the same in every language. */
internal fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/** The blue "Upcoming" header and up to two events, each a button when `onEventClick` is set ("home-upcoming-<id>"). */
@Composable
private fun HomeUpcomingList(events: List<CalendarEvent>?, onEventClick: ((CalendarEvent) -> Unit)?, modifier: Modifier = Modifier) {
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
                events.forEach { event ->
                    val rowModifier = if (onEventClick != null) {
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = "Opens the event to change it") { onEventClick(event) }
                    } else {
                        Modifier
                    }
                    HomeEventRow(event, rowModifier.testTag("home-upcoming-${event.id}"))
                }
            }
        }
    }
}
