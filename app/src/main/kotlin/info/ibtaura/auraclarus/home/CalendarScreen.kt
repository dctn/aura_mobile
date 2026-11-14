package info.ibtaura.auraclarus.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.CalendarEvent
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.WorkSchedule
import info.ibtaura.aura.core.ZoneDay
import info.ibtaura.aura.core.ZoneStatus
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.WorkHoursRow
import info.ibtaura.auraclarus.design.ZoneText
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Home > Work > Calendar (iOS HomeCalendarList): today's zones, Find a time and New event, the day chips (yesterday to
 * 13 days ahead), the chosen day's hour grid, the work hours, then the day's events. On today, events that already
 * ended sit dimmed under "Earlier today" and the next 6 days follow by day. Tapping an event, on the grid or in a
 * list, opens it in the editor.
 */
@Composable
fun CalendarScreen(app: AppState, nav: AuraNav) {
    // Every event from yesterday to 13 days ahead (15 days, inside the route's 62), loaded at once; the chips pick one.
    var events by remember { mutableStateOf<List<CalendarEvent>?>(null) }
    var failed by remember { mutableStateOf(false) }
    // The day shown, as days from today (-1 is yesterday). Kept when the editor is pushed and popped.
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var zoneDay by remember { mutableStateOf<ZoneDay?>(null) }
    var zonesFailed by remember { mutableStateOf(false) }
    var schedule by remember { mutableStateOf<WorkSchedule?>(null) }
    var scheduleLoaded by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(Instant.now()) }

    val zone = ZoneId.systemDefault()
    val todayDate = now.atZone(zone).toLocalDate()
    val selectedDate = todayDate.plusDays(dayOffset.toLong())
    val selectedKey = DayKey.string(selectedDate)
    val isToday = dayOffset == 0

    // Events, the chosen day's zones and the work hours load on their own; each can fail on its own.
    LaunchedEffect(app.dataVersion, retry) {
        val today = Instant.now().atZone(zone).toLocalDate()
        val from = today.plusDays(EventDays.FIRST.toLong()).atStartOfDay(zone).toInstant()
        val to = today.plusDays(EventDays.LAST + 1L).atStartOfDay(zone).toInstant()
        try {
            val loaded = app.api.events(from, to)
            app.rememberEvents(loaded)
            events = loaded
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = events == null
        }
    }
    LaunchedEffect(app.dataVersion, retry, selectedKey) {
        zonesFailed = false
        try {
            val loaded = app.api.dayZones(selectedKey, selectedKey)
            zoneDay = loaded.firstOrNull { it.date == selectedKey } ?: loaded.firstOrNull()?.copy(date = selectedKey)
                ?: ZoneDay(date = selectedKey, status = ZoneStatus.unknown)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            zonesFailed = zoneDay?.date != selectedKey
        }
    }
    LaunchedEffect(app.dataVersion, retry) {
        try {
            // Through AppState, so the Work hours page opens with what's shown here.
            schedule = app.loadWorkSchedule()
            scheduleLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
        }
    }
    // The hour grid's red line and the "Earlier today" split move every minute.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = Instant.now()
        }
    }

    // The zones of the day shown; a load for another day still on its way doesn't count.
    val shownZone = zoneDay?.takeIf { it.date == selectedKey }
    val readyDay = shownZone?.takeIf { it.status == ZoneStatus.ready }
    val zonesLoading = shownZone == null && !zonesFailed
    val dayStart = selectedDate.atStartOfDay(zone).toInstant()
    val dayEnd = selectedDate.plusDays(1).atStartOfDay(zone).toInstant()
    val sorted = (events ?: emptyList()).sortedBy { it.start }
    val dayEvents = sorted.filter { it.end > dayStart && it.start < dayEnd }
    val earlier = if (isToday) dayEvents.filter { it.end <= now } else emptyList()
    val dayList = if (isToday) dayEvents.filter { it.end > now } else dayEvents
    // On today, the next 6 days follow, each under its own title.
    val laterDays: List<Pair<LocalDate, List<CalendarEvent>>> = if (!isToday) emptyList() else {
        val weekEnd = todayDate.plusDays(7).atStartOfDay(zone).toInstant()
        sorted.filter { it.start >= dayEnd && it.start < weekEnd }
            .groupBy { it.start.atZone(zone).toLocalDate() }
            .toSortedMap()
            .toList()
    }
    val showsGrid = readyDay != null || dayEvents.isNotEmpty()
    // A new event starts on the day being shown (today: the next half hour; another day: 09:00).
    val openEditor: (CalendarEvent?) -> Unit = { event ->
        nav.push(Route.EventEditor(event?.id, day = if (event == null && !isToday) selectedKey else null))
    }
    val editHours = { nav.push(Route.WorkHours) }
    val dayTitle = EventDays.title(selectedDate, todayDate)

    AuraPage(Modifier.testTag("calendar-page"), title = "Calendar", onBack = { nav.pop() }) {
        // A failed zones load only shows here, so the events still show.
        if (zonesFailed) {
            AuraCard(padding = 20.dp) { HomeRetryLine("Couldn't load your zones.", retry = { retry += 1 }) }
        } else if (isToday && shownZone != null && shownZone.status != ZoneStatus.unknown) {
            ZoneLineCard(shownZone, setHours = editHours)
        }
        if (events != null || failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    "Find a time",
                    // Find a time plans today or later, so yesterday plans today.
                    { nav.push(Route.FindATime(DayKey.string(if (dayOffset < 0) todayDate else selectedDate))) },
                    Modifier.weight(1f).testTag("calendar-find-time"),
                    fills = true,
                )
                PillButton(
                    "New event",
                    { openEditor(null) },
                    Modifier.weight(1f).testTag("calendar-new-event"),
                    style = PillStyle.Secondary,
                    fills = true,
                )
            }
        }
        EventDayPicker(dayOffset, todayDate, onSelect = { dayOffset = it })

        if (showsGrid) {
            // A confirmed plan reloads every screen through `dataVersion`, so its blocks show on the hour grid.
            ZoneCardSection(dayTitle, padding = 14.dp) {
                ZoneDayGrid(readyDay?.zones ?: emptyList(), dayEvents, schedule, now, day = selectedDate, onEventClick = openEditor)
            }
        }
        if (readyDay != null) {
            ZoneDayList(readyDay.zones)
            ZoneLegend()
        }
        if (scheduleLoaded) {
            WorkHoursRow("Work hours", ZoneText.schedule(schedule), editHours, Modifier.testTag("calendar-work-hours"))
        }

        when {
            failed -> HomeRetryLine("Couldn't load your calendar.", retry = { retry += 1 })
            // Until the day's zones arrive, an empty day may still get a zone grid: keep the spinner, not "Nothing".
            events == null || (zonesLoading && dayEvents.isEmpty()) -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }
            isToday && dayEvents.isEmpty() && laterDays.isEmpty() && readyDay == null ->
                Text("Nothing in the next 7 days", style = AuraType.callout, color = AuraColors.footnote)
            !isToday && dayEvents.isEmpty() && readyDay == null ->
                Text(
                    when (dayOffset) {
                        -1 -> "Nothing yesterday"
                        1 -> "Nothing tomorrow"
                        else -> "Nothing on $dayTitle"
                    },
                    style = AuraType.callout,
                    color = AuraColors.footnote,
                    modifier = Modifier.testTag("calendar-day-empty"),
                )
        }
        if (earlier.isNotEmpty()) {
            CalendarDayGroup("Earlier today", earlier, openEditor, Modifier.testTag("calendar-earlier"), dimmed = { true })
        }
        if (dayList.isNotEmpty()) {
            // Yesterday's events, and any other that already ended, are dimmed too.
            CalendarDayGroup(dayTitle, dayList, openEditor, dimmed = { it.end <= now })
        }
        laterDays.forEach { (date, list) -> CalendarDayGroup(EventDays.title(date, todayDate), list, openEditor) }
    }
}

/** One day's title and a card of its events; each row opens the editor. A dimmed row has already ended. */
@Composable
private fun CalendarDayGroup(
    title: String,
    events: List<CalendarEvent>,
    open: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
    dimmed: (CalendarEvent) -> Boolean = { false },
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = AuraType.system(20, FontWeight.Bold),
            color = Color.Black,
            modifier = Modifier.semantics { heading() },
        )
        AuraCard(padding = 20.dp, spacing = 14.dp) {
            events.forEach { event ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (dimmed(event)) 0.5f else 1f)
                        .clickable(role = Role.Button, onClickLabel = "Opens the event to change it") { open(event) }
                        .testTag("calendar-event-${event.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomeEventRow(event, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Icon(auraIcon("chevron.right"), contentDescription = null, tint = AuraColors.footnote, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
