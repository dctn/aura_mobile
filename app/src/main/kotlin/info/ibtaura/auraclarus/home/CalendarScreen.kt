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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.time.format.DateTimeFormatter

/**
 * Home > Work > Calendar (iOS HomeCalendarList): today's zones, Find a time and New event, today's events, the work
 * hours, then the events of the next 6 days by day. Tapping an event opens it in the editor.
 */
@Composable
fun CalendarScreen(app: AppState, nav: AuraNav) {
    // Events still to come in the next 7 days, for the lists.
    var events by remember { mutableStateOf<List<CalendarEvent>?>(null) }
    // All of today's events, past ones too, for the hour grid.
    var todayEvents by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var zoneDay by remember { mutableStateOf<ZoneDay?>(null) }
    var zonesFailed by remember { mutableStateOf(false) }
    var schedule by remember { mutableStateOf<WorkSchedule?>(null) }
    var scheduleLoaded by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(Instant.now()) }

    // Events, today's zones and the work hours load together; each can fail on its own.
    LaunchedEffect(app.dataVersion, retry) {
        val start = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = start.atZone(zone).toLocalDate()
        val dayStart = today.atStartOfDay(zone).toInstant()
        val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant()
        val end = today.plusDays(7).atStartOfDay(zone).toInstant()
        try {
            val loaded = app.api.events(dayStart, end)
            app.rememberEvents(loaded)
            events = loaded.filter { it.end > start && it.start < end }
            todayEvents = loaded.filter { it.end > dayStart && it.start < tomorrow }
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = events == null
        }
    }
    LaunchedEffect(app.dataVersion, retry) {
        val todayKey = DayKey.string(Instant.now())
        try {
            val loaded = app.api.dayZones(todayKey, todayKey)
            zoneDay = loaded.firstOrNull { it.date == todayKey } ?: loaded.firstOrNull()
                ?: ZoneDay(date = todayKey, status = ZoneStatus.unknown)
            zonesFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            zonesFailed = zoneDay == null
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
    // The hour grid's red line moves every minute.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = Instant.now()
        }
    }

    val readyDay = zoneDay?.takeIf { it.status == ZoneStatus.ready }
    val zone = ZoneId.systemDefault()
    val todayDate = now.atZone(zone).toLocalDate()
    val days: List<Pair<LocalDate, List<CalendarEvent>>> = (events ?: emptyList())
        .sortedBy { it.start }
        .groupBy { it.start.atZone(zone).toLocalDate() }
        .toSortedMap()
        .toList()
    val todayGroup = days.firstOrNull { it.first == todayDate }
    val laterDays = days.filter { it.first != todayDate }
    val openEditor: (CalendarEvent?) -> Unit = { event -> nav.push(Route.EventEditor(event?.id)) }
    val editHours = { nav.push(Route.WorkHours) }

    @Composable
    fun eventsState() {
        when {
            failed -> HomeRetryLine("Couldn't load your calendar.", retry = { retry += 1 })
            events == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }
            days.isEmpty() && readyDay == null ->
                Text("Nothing in the next 7 days", style = AuraType.callout, color = AuraColors.footnote)
        }
    }

    AuraPage(Modifier.testTag("calendar-page"), title = "Calendar", onBack = { nav.pop() }) {
        // A failed zones load only shows here, so the events still show.
        val loadedZone = zoneDay
        if (zonesFailed) {
            AuraCard(padding = 20.dp) { HomeRetryLine("Couldn't load your zones.", retry = { retry += 1 }) }
        } else if (loadedZone != null && loadedZone.status != ZoneStatus.unknown) {
            ZoneLineCard(loadedZone, setHours = editHours)
        }
        if (events != null || failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    "Find a time",
                    { nav.push(Route.FindATime(DayKey.string(Instant.now()))) },
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
        if (readyDay != null) {
            // A confirmed plan reloads every screen through `dataVersion`, so its blocks show on the hour grid.
            ZoneCardSection("Today", padding = 14.dp) {
                ZoneDayGrid(readyDay.zones, todayEvents, schedule, now)
            }
            ZoneDayList(readyDay.zones)
            ZoneLegend()
        } else {
            eventsState()
            todayGroup?.let { CalendarDayGroup(it.first, it.second, todayDate, openEditor) }
        }
        if (scheduleLoaded) {
            WorkHoursRow("Work hours", ZoneText.schedule(schedule), editHours, Modifier.testTag("calendar-work-hours"))
        }
        if (readyDay != null) {
            eventsState()
            // The hour grid can't be tapped, so today's events are listed too, to open them in the editor.
            todayGroup?.let { CalendarDayGroup(it.first, it.second, todayDate, openEditor) }
        }
        laterDays.forEach { (date, list) -> CalendarDayGroup(date, list, todayDate, openEditor) }
    }
}

private val dayTitleFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")

private fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> dayTitleFormat.format(date)
}

/** One day's title and a card of its events; each row opens the editor. */
@Composable
private fun CalendarDayGroup(date: LocalDate, events: List<CalendarEvent>, today: LocalDate, open: (CalendarEvent) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            dayTitle(date, today),
            style = AuraType.system(20, FontWeight.Bold),
            color = Color.Black,
            modifier = Modifier.semantics { heading() },
        )
        AuraCard(padding = 20.dp, spacing = 14.dp) {
            events.forEach { event ->
                Row(
                    Modifier
                        .fillMaxWidth()
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
