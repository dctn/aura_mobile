package info.ibtaura.auraclarus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import info.ibtaura.aura.core.CalendarEvent
import info.ibtaura.aura.core.WorkSchedule
import info.ibtaura.aura.core.Zone
import info.ibtaura.aura.core.ZoneDay
import info.ibtaura.aura.core.ZoneKind
import info.ibtaura.aura.core.ZoneStatus
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.ZoneText
import info.ibtaura.auraclarus.design.hex
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor

// Port of Home/CalendarZones.swift (the zone views; ZoneText and WorkHoursRow live in design/).

/** A zone's shade, name and meaning. Names and meanings are the website's; the shades are the Figma's blue and green. */
internal data class ZoneStyle(val color: Color, val name: String, val meaning: String) {
    companion object {
        fun of(kind: ZoneKind): ZoneStyle = when (kind) {
            ZoneKind.heavyWork -> ZoneStyle(AuraColors.blue.copy(alpha = 0.28f), "Heavy work", "Your hardest task")
            ZoneKind.lightWork -> ZoneStyle(AuraColors.blue.copy(alpha = 0.10f), "Light work", "Email, admin, routine")
            ZoneKind.shortRecovery -> ZoneStyle(AuraColors.green.copy(alpha = 0.12f), "Short recovery", "Step away for a moment")
            ZoneKind.heavyRecovery -> ZoneStyle(AuraColors.green.copy(alpha = 0.30f), "Heavy recovery", "Lunch or a proper rest")
        }
    }
}

/** The line at the top of Calendar: how much heavy work today holds, or why nothing is shaded. */
@Composable
internal fun ZoneLineCard(day: ZoneDay, setHours: () -> Unit) {
    val title = when (day.status) {
        ZoneStatus.ready -> when {
            day.heavyMinutes <= 0 -> "Light work today"
            day.score != null -> "Score ${day.score} · ${ZoneText.hours(day.heavyMinutes)} heavy work"
            else -> "${ZoneText.hours(day.heavyMinutes)} heavy work"
        }
        ZoneStatus.dayOff -> "Day off"
        ZoneStatus.noScore -> "No score yet"
        ZoneStatus.needsSchedule -> "Set your work hours to see your zones."
        ZoneStatus.unknown -> ""
    }
    val detail = when (day.status) {
        ZoneStatus.ready -> day.reason?.takeIf { it.isNotEmpty() }
        ZoneStatus.dayOff -> "Today isn't one of your work days, so nothing is shaded."
        ZoneStatus.noScore -> "Zones start once your morning score is ready."
        ZoneStatus.needsSchedule, ZoneStatus.unknown -> null
    }
    AuraCard(padding = 20.dp, spacing = 12.dp) {
        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("calendar-zone-line"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = AuraType.system(17, FontWeight.SemiBold), color = Color.Black)
            if (detail != null) Text(detail, style = AuraType.callout, color = AuraColors.gray)
        }
        if (day.status == ZoneStatus.needsSchedule) {
            PillButton("Set work hours", setHours, Modifier.testTag("calendar-set-hours"), fills = true)
        }
    }
}

/** The small square of a zone's shade. */
@Composable
internal fun ZoneSwatch(kind: ZoneKind) {
    val style = ZoneStyle.of(kind)
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(22.dp)
            .clip(shape)
            .background(style.color)
            .border(1.dp, Color.Black.copy(alpha = 0.08f), shape),
    )
}

/** One zone kind: swatch, name and meaning, with an optional time on the right. */
@Composable
private fun ZoneRow(kind: ZoneKind, time: String? = null) {
    val style = ZoneStyle.of(kind)
    val spoken = if (time != null) "${style.name}, $time, ${style.meaning}" else "${style.name}, ${style.meaning}"
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZoneSwatch(kind)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(style.name, style = AuraType.system(16, FontWeight.Medium), color = Color.Black, maxLines = 1)
            Text(style.meaning, style = AuraType.caption, color = AuraColors.gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (time != null) {
            Text(time, style = AuraType.callout.copy(fontFeatureSettings = "tnum"), color = AuraColors.gray, maxLines = 1)
        }
    }
}

/** "Your day": today's zones in order. */
@Composable
internal fun ZoneDayList(zones: List<Zone>) {
    ZoneCardSection("Your day") {
        zones.forEachIndexed { index, zone ->
            if (index > 0) CardDivider()
            ZoneRow(zone.kind, ZoneText.range(zone.start, zone.end))
        }
    }
}

/** "What each shade means". */
@Composable
internal fun ZoneLegend() {
    ZoneCardSection("What each shade means") {
        ZoneKind.entries.forEachIndexed { index, kind ->
            if (index > 0) CardDivider()
            ZoneRow(kind)
        }
    }
}

private val hourHeight = 56.dp
private val gutter = 46.dp
private val gridPad = 10.dp
private val minBlock = 26.dp
/** Only a zone this long has room for its name. */
private const val LABEL_SECONDS = 30 * 60L
private val lineColor = hex(0xE3E3E3)

/** An event placed in its lane. Events that overlap sit side by side. */
private data class GridBlock(val event: CalendarEvent, val start: Double, val end: Double, var lane: Int, var lanes: Int)

/** Today's hours: the zone shades behind, the events on top and a red line at the current time. */
@Composable
internal fun ZoneDayGrid(zones: List<Zone>, events: List<CalendarEvent>, schedule: WorkSchedule?, now: Instant) {
    val zoneId = ZoneId.systemDefault()
    val dayStart = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()

    // Minutes after today's midnight, kept inside the day.
    fun minutes(date: Instant): Double = ((date.toEpochMilli() - dayStart.toEpochMilli()) / 60_000.0).coerceIn(0.0, 24.0 * 60)

    // From the earlier of work start and the first event to the later of work end and the last event, on the hour.
    val starts = mutableListOf<Double>()
    val ends = mutableListOf<Double>()
    if (schedule != null) {
        val s = WorkSchedule.minutes(schedule.workdayStart)
        val e = WorkSchedule.minutes(schedule.workdayEnd)
        if (s != null && e != null) {
            starts.add(s.toDouble())
            ends.add(e.toDouble())
        }
    }
    zones.forEach { starts.add(minutes(it.start)); ends.add(minutes(it.end)) }
    events.forEach { starts.add(minutes(it.start)); ends.add(minutes(it.end)) }
    val from: Int
    val to: Int
    if (starts.isEmpty() || ends.isEmpty()) {
        from = 8
        to = 18
    } else {
        from = floor(starts.min() / 60).toInt().coerceIn(0, 23)
        to = maxOf(from + 1, ceil(ends.max() / 60).toInt()).coerceAtMost(24)
    }

    fun y(minute: Double): Dp = gridPad + hourHeight * ((minute - from * 60) / 60).toFloat()

    // Lanes for overlapping events. A short event counts as tall as its block, so blocks never cover each other.
    val minMinutes = (minBlock / hourHeight) * 60.0
    val blocks = mutableListOf<GridBlock>()
    run {
        var group = mutableListOf<Int>()
        var laneEnds = mutableListOf<Double>()
        var groupEnd = Double.NEGATIVE_INFINITY
        for (event in events.sortedBy { it.start }) {
            val start = minutes(event.start)
            val end = minutes(event.end)
            val shownEnd = maxOf(end, start + minMinutes)
            if (start >= groupEnd) {
                group.forEach { blocks[it].lanes = laneEnds.size }
                group = mutableListOf()
                laneEnds = mutableListOf()
            }
            val free = laneEnds.indexOfFirst { it <= start }
            val lane = if (free >= 0) {
                laneEnds[free] = shownEnd
                free
            } else {
                laneEnds.add(shownEnd)
                laneEnds.size - 1
            }
            blocks.add(GridBlock(event, start, end, lane, 1))
            group.add(blocks.size - 1)
            groupEnd = maxOf(groupEnd, shownEnd)
        }
        group.forEach { blocks[it].lanes = laneEnds.size }
    }

    val spoken = buildList {
        add("Today's hours")
        zones.forEach { add("${ZoneStyle.of(it.kind).name}, ${ZoneText.clock(it.start)} to ${ZoneText.clock(it.end)}") }
        events.sortedBy { it.start }.forEach { add("${it.title}, ${ZoneText.clock(it.start)} to ${ZoneText.clock(it.end)}") }
    }.joinToString(". ")

    val height = hourHeight * (to - from).toFloat() + gridPad * 2
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = spoken }
            .testTag("calendar-day-grid"),
    ) {
        val width = maxWidth
        val area = max(0.dp, width - gutter)

        // Shades.
        zones.forEach { zone ->
            val top = y(minutes(zone.start))
            val bottom = y(minutes(zone.end))
            val style = ZoneStyle.of(zone.kind)
            Box(
                Modifier
                    .offset(x = gutter, y = top)
                    .width(area)
                    .height(max(0.dp, bottom - top))
                    .background(style.color),
            ) {
                if (zone.end.epochSecond - zone.start.epochSecond >= LABEL_SECONDS) {
                    Text(
                        style.name,
                        style = AuraType.system(10, FontWeight.SemiBold),
                        color = Color.Black.copy(alpha = 0.45f),
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 6.dp),
                    )
                }
            }
        }

        // Hour lines and labels.
        for (hour in from..to) {
            val top = y(hour * 60.0)
            if (hour < 24) {
                Text(
                    WorkSchedule.clock(hour * 60),
                    style = AuraType.system(11).copy(fontFeatureSettings = "tnum"),
                    color = AuraColors.gray,
                    maxLines = 1,
                    modifier = Modifier.offset(x = 0.dp, y = top - 8.dp).width(gutter - 6.dp),
                )
            }
            Box(Modifier.offset(x = gutter, y = top).width(area).height(0.5.dp).background(lineColor))
        }

        // Events.
        val laneArea = max(0.dp, width - gutter - 4.dp)
        blocks.forEach { block ->
            val laneWidth = laneArea / maxOf(1, block.lanes).toFloat()
            val top = y(block.start)
            val blockHeight = max(minBlock, y(block.end) - top)
            ZoneEventBlock(
                block.event,
                blockHeight,
                Modifier
                    .offset(x = gutter + 4.dp + laneWidth * block.lane.toFloat(), y = top)
                    .width(max(0.dp, laneWidth - 3.dp))
                    .height(blockHeight),
            )
        }

        // Now.
        val nowMinute = (now.toEpochMilli() - dayStart.toEpochMilli()) / 60_000.0
        if (nowMinute >= from * 60 && nowMinute <= to * 60) {
            val top = y(nowMinute)
            Box(Modifier.offset(x = gutter, y = top - 0.75.dp).width(area).height(1.5.dp).background(AuraColors.red))
            Box(Modifier.offset(x = gutter - 4.dp, y = top - 4.dp).size(8.dp).background(AuraColors.red, CircleShape))
        }
    }
}

/** An event on the hour grid: its colour bar, then the title and time, or the title alone when it's short. */
@Composable
private fun ZoneEventBlock(event: CalendarEvent, height: Dp, modifier: Modifier) {
    val color = homeEventColor(event.kind)
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            // A white base under the tint, so a shade's label behind the block doesn't show through the text.
            .background(AuraColors.card)
            .background(color.copy(alpha = 0.2f)),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Column(
            Modifier.fillMaxSize().padding(start = 6.dp, end = 4.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (height >= 40.dp) {
                Text(event.title, style = AuraType.system(13, FontWeight.SemiBold), color = Color.Black, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(ZoneText.range(event.start, event.end), style = AuraType.system(11), color = AuraColors.gray, maxLines = 1)
            } else {
                Text("${event.title} · ${ZoneText.clock(event.start)}", style = AuraType.system(13, FontWeight.SemiBold),
                    color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
