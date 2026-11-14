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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.Rewards
import info.ibtaura.aura.core.SessionDetail
import info.ibtaura.aura.core.SessionFilter
import info.ibtaura.aura.core.SessionHistory
import info.ibtaura.aura.core.WeekSummary
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraLazyPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.ZoneText
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.home.HomeRetryLine
import info.ibtaura.auraclarus.recovery.LogBreakSheet
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.CacheKeys
import info.ibtaura.auraclarus.shell.Tab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/** How many days the history and the export cover. */
private const val historyDays = 30

/**
 * Search > Sessions: this week's focus and level, a filter, then the last 30 days grouped by day, newest first, in one
 * request that the week header shares (it used to take a request per 10 days plus one for the week, one after another). A row opens the session (`onOpenSession(id)`, wired to
 * `nav.push(Route.SessionDetail(id))`). "Log a break" opens Recovery's sheet; "Export CSV" shares the 30 days.
 */
@Composable
fun SessionHistoryScreen(app: AppState, nav: AuraNav, onOpenSession: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf(SessionFilter.all) }
    var retry by remember { mutableIntStateOf(0) }

    // The header and the list start from the screen cache, so a return from a session draws the page at once and
    // refreshes it quietly.
    var rewards by remember { mutableStateOf(app.cached<Rewards>(CacheKeys.REWARDS)) }
    var week by remember { mutableStateOf(cachedWeek(app)) }
    var headerFailed by remember { mutableStateOf(false) }

    // The windows already cached, newest first, without a gap: shown at once, then each refreshed.
    val cachedStart = remember { HistoryWindows(app, LocalDate.now(DayKey.zone), filter, historyDays).cachedPrefix() }
    var sessions by remember { mutableStateOf(cachedStart?.first) }
    var loadedFilter by remember { mutableStateOf(if (cachedStart != null) filter else null) }
    var pager by remember { mutableStateOf<HistoryWindows?>(null) }
    var listFailed by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var moreFailed by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(cachedStart != null && cachedStart.second < HistoryWindows.count(historyDays)) }

    var loggingBreak by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // The header: rewards and this week, each on its own so one failing still shows the other.
    LaunchedEffect(app.dataVersion, retry) {
        val today = LocalDate.now(DayKey.zone)
        var failed = false
        launch {
            try {
                rewards = app.cache.load(CacheKeys.REWARDS) { app.api.rewards() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.note(e)
                failed = true
                headerFailed = rewards == null && week == null
            }
        }
        try {
            // The 30 days hold this week, and the "All" list loads the same request, so the two share one call.
            val inDays = HistoryWindows(app, today, SessionFilter.all, historyDays).loadFirst(1)
            week = SessionHistory.week(inDays, today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = true
        }
        headerFailed = failed && rewards == null && week == null
    }

    // The list: the newest window first. A new filter starts afresh; a reload for new data keeps the old rows showing.
    LaunchedEffect(filter, app.dataVersion, retry) {
        val today = LocalDate.now(DayKey.zone)
        val fresh = HistoryWindows(app, today, filter, totalDays = historyDays)
        // A reload keeps as many windows as are showing (from the cache while fresh), so the list doesn't shrink.
        val keep = if (loadedFilter == filter) pager?.loaded ?: cachedStart?.second ?: 1 else 1
        if (loadedFilter != filter) {
            sessions = null
            listState.scrollToItem(0)
        }
        pager = fresh
        listFailed = false
        moreFailed = false
        loadingMore = true
        try {
            val first = fresh.loadFirst(keep)
            sessions = first
            loadedFilter = filter
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            listFailed = sessions == null || loadedFilter != filter
        } finally {
            hasMore = fresh.hasMore
            loadingMore = false
        }
    }

    fun loadMore() {
        val current = pager ?: return
        if (loadingMore || !current.hasMore || listFailed) return
        loadingMore = true
        moreFailed = false
        scope.launch {
            try {
                val more = current.loadNext()
                if (pager === current) sessions = SessionHistory.merged(sessions.orEmpty(), more)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.note(e)
                if (pager === current) moreFailed = true
            } finally {
                if (pager === current) {
                    hasMore = current.hasMore
                    loadingMore = false
                }
            }
        }
    }

    fun export() {
        if (exporting) return
        exporting = true
        exportText = null
        scope.launch {
            val today = LocalDate.now(DayKey.zone)
            val from = DayKey.string(today.minusDays(historyDays - 1L))
            val to = DayKey.string(today)
            try {
                val csv = app.api.exportSessionsCsv(from, to)
                if (!shareCsv(context, "aura-sessions-$from-to-$to.csv", csv)) {
                    exportText = if (csv.length > maxSharedCsvChars) "That's too much to share at once." else "Nothing on this phone can take the file."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.note(e)
                exportText = when {
                    e is AuraError.Offline -> "You're offline. Try again when you're connected."
                    (e as? AuraError.Server)?.status == 413 -> "That's too much for one file."
                    else -> "Couldn't export. Try again."
                }
            } finally {
                exporting = false
            }
        }
    }

    // Load the next window when the end of the list comes into view (also when the first window is short or empty).
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(nearEnd, loadingMore, hasMore, sessions) {
        if (nearEnd && hasMore && !loadingMore && sessions != null) loadMore()
    }

    val days = remember(sessions) { sessions?.let { SessionHistory.days(it) }.orEmpty() }
    val today = LocalDate.now(DayKey.zone)

    AuraLazyPage(Modifier.testTag("session-history"), title = "Sessions", onBack = { nav.pop() }, state = listState, spacing = 14.dp) {
        item(key = "header") {
            WeekHeader(week, rewards, headerFailed) { retry += 1 }
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Log a break", { loggingBreak = true }, Modifier.weight(1f).testTag("history-log-break"),
                        style = PillStyle.Secondary, fills = true)
                    PillButton(if (exporting) "Exporting..." else "Export CSV", { export() },
                        Modifier.weight(1f).testTag("history-export"), style = PillStyle.Secondary, fills = true,
                        enabled = !exporting)
                }
                exportText?.let { Text(it, style = AuraType.callout, color = AuraColors.red, modifier = Modifier.testTag("history-export-error")) }
            }
        }
        item(key = "filter") {
            SessionFilterPicker(filter, { filter = it }, Modifier.padding(top = 4.dp))
        }

        val list = sessions
        when {
            listFailed -> item(key = "failed") {
                AuraCard(padding = 20.dp) { HomeRetryLine("Couldn't load your sessions.", { retry += 1 }) }
            }
            list == null -> item(key = "loading") { SessionsSpinner(Modifier.padding(top = 24.dp)) }
            list.isEmpty() && !hasMore && !loadingMore -> item(key = "empty") {
                EmptyHistory(filter,
                    onLogBreak = { loggingBreak = true },
                    onBrowse = { nav.selectTab(Tab.Recovery) },
                    onShowAll = { filter = SessionFilter.all })
            }
            else -> days.forEach { day ->
                item(key = "day-${day.date}") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Text(SessionWords.dayTitle(day.date, today), style = AuraType.heading, color = Color.Black,
                            modifier = Modifier.weight(1f).semantics { heading() })
                        Text(ZoneText.hours(day.minutes), style = AuraType.callout, color = AuraColors.gray)
                    }
                }
                item(key = "card-${day.date}") {
                    AuraCard(padding = 14.dp, spacing = 6.dp) {
                        day.sessions.forEachIndexed { index, session ->
                            if (index > 0) CardDivider(Modifier.padding(start = 52.dp))
                            SessionHistoryRow(session) { onOpenSession(session.id) }
                        }
                    }
                }
            }
        }

        if (list != null && !listFailed) {
            item(key = "footer") {
                when {
                    loadingMore -> SessionsSpinner()
                    moreFailed -> HomeRetryLine("Couldn't load earlier days.", { loadMore() }, Modifier.padding(start = 4.dp))
                    !hasMore && list.isNotEmpty() -> Footnote("That's everything from the last $historyDays days. Export CSV shares them as a spreadsheet.")
                    else -> Box(Modifier.height(1.dp))
                }
            }
        }
    }

    if (loggingBreak) {
        LogBreakSheet(app, onDismiss = { loggingBreak = false })
    }
}

/** This week's header from the sessions already in the cache, or null when they aren't there. */
private fun cachedWeek(app: AppState): WeekSummary? {
    val today = LocalDate.now(DayKey.zone)
    return HistoryWindows(app, today, SessionFilter.all, historyDays).cachedPrefix()?.let { SessionHistory.week(it.first, today) }
}

/**
 * Core's SessionHistoryPager with each window kept in the screen cache (`CacheKeys.historyWindow`), one window of
 * all 30 days: a window already loaded comes back at once, so a return from a session redraws the list. The cache
 * holds what the server sent for the filter's `serverKind` and the filter applies after, so "All", "Recovery" and the
 * week header share one request. A failed window is tried again on the
 * next `loadNext`. Call it from one coroutine at a time.
 */
private class HistoryWindows(
    private val app: AppState,
    today: LocalDate,
    private val filter: SessionFilter,
    totalDays: Int,
) {
    private val windows = SessionHistory.windows(today, totalDays, WINDOW_DAYS)

    /** How many windows have loaded. */
    var loaded = 0
        private set

    val hasMore: Boolean get() = loaded < windows.size

    private fun key(index: Int) =
        CacheKeys.historyWindow(filter.serverKind ?: "all", windows[index].first, windows[index].second)

    private fun kept(found: List<SessionDetail>): List<SessionDetail> = found.filter { filter.matches(it.record.kind) }

    /** The windows in the cache from the newest on, without a gap, merged, and how many; null when none is there. */
    fun cachedPrefix(): Pair<List<SessionDetail>, Int>? {
        var list = emptyList<SessionDetail>()
        var count = 0
        while (count < windows.size) {
            val window = app.cached<List<SessionDetail>>(key(count)) ?: break
            list = SessionHistory.merged(list, kept(window))
            count += 1
        }
        return if (count == 0) null else list to count
    }

    suspend fun loadNext(): List<SessionDetail> {
        if (!hasMore) return emptyList()
        val (from, to) = windows[loaded]
        val found = app.cache.load(key(loaded)) { fetch(from, to) }
        loaded += 1
        return kept(found)
    }

    /** The newest `count` windows (at least one), merged. */
    suspend fun loadFirst(count: Int): List<SessionDetail> {
        var all = emptyList<SessionDetail>()
        repeat(count.coerceIn(1, windows.size)) { all = SessionHistory.merged(all, loadNext()) }
        return all
    }

    /** One whole window, every cursor page of it, newest first; the filter applies after (see `kept`). */
    private suspend fun fetch(from: String, to: String): List<SessionDetail> {
        val found = mutableListOf<SessionDetail>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = app.api.sessionPage(from, to, filter.serverKind, cursor, PAGE_LIMIT)
            found.addAll(page.sessions)
            cursor = page.cursor?.takeIf { it.isNotEmpty() }
            pages += 1
        } while (cursor != null && pages < MAX_PAGES)
        return found.sortedByDescending { it.record.startedAt ?: Instant.MIN }
    }

    companion object {
        // One window: a 10-day window that came back short made the list ask for the next, one after another.
        private const val WINDOW_DAYS = 30
        private const val PAGE_LIMIT = 500
        private const val MAX_PAGES = 20

        /** How many windows `totalDays` makes. */
        fun count(totalDays: Int): Int = SessionHistory.windows(LocalDate.now(DayKey.zone), totalDays, WINDOW_DAYS).size
    }
}

/** This week's focus and sessions, then the level with its XP bar and the plant. */
@Composable
private fun WeekHeader(week: WeekSummary?, rewards: Rewards?, failed: Boolean, retry: () -> Unit) {
    AuraCard(Modifier.testTag("history-header"), padding = 20.dp, spacing = 14.dp) {
        Text("This week", style = AuraType.calloutMedium, color = AuraColors.gray)
        if (failed) {
            HomeRetryLine("Couldn't load this week.", retry)
            return@AuraCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderNumber(week?.let { ZoneText.hours(it.focusMinutes) }, "focus", Modifier.weight(1f).testTag("history-week-focus"))
            HeaderNumber(week?.sessionsCompleted?.toString(), if (week?.sessionsCompleted == 1) "session completed" else "sessions completed",
                Modifier.weight(1f).testTag("history-week-sessions"))
        }
        CardDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(AuraColors.green.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(auraIcon("leaf.fill"), contentDescription = null, tint = AuraColors.green, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f).testTag("history-level"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rewards == null) {
                    Text("Level", style = AuraType.bodyMedium, color = AuraColors.footnote)
                    XpBar(0.0)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Level ${rewards.level}", style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.weight(1f))
                        Text(rewards.plantName, style = AuraType.callout, color = AuraColors.green)
                    }
                    XpBar(rewards.levelProgress)
                    Text(
                        "${rewards.xpIntoLevel} of ${rewards.xpPerLevel} XP to level ${rewards.level + 1} · ${ZoneText.hours(rewards.totalFocusMinutes)} focus in all",
                        style = AuraType.caption,
                        color = AuraColors.gray,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderNumber(value: String?, label: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value ?: "–", style = AuraType.system(26, FontWeight.Bold), color = if (value == null) AuraColors.footnote else Color.Black, maxLines = 1)
        Text(label, style = AuraType.callout, color = AuraColors.gray, maxLines = 1)
    }
}

/** A thin blue bar, `fraction` 0 to 1. */
@Composable
private fun XpBar(fraction: Double) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(AuraColors.track)) {
        if (fraction > 0) {
            Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0.03f, 1f)).height(8.dp).clip(CircleShape).background(AuraColors.blue))
        }
    }
}

/** What to say, and what to offer, when a filter has nothing in 30 days. */
@Composable
private fun EmptyHistory(filter: SessionFilter, onLogBreak: () -> Unit, onBrowse: () -> Unit, onShowAll: () -> Unit) {
    when (filter) {
        SessionFilter.all -> SessionsEmptyState("clock.arrow.circlepath", "No sessions in the last $historyDays days.", "Browse modes", onBrowse)
        SessionFilter.focus -> SessionsEmptyState("brain.head.profile", "No focus sessions in the last $historyDays days.", "Browse modes", onBrowse)
        SessionFilter.recovery -> SessionsEmptyState("leaf.fill", "No breaks in the last $historyDays days.", "Log a break", onLogBreak)
        SessionFilter.meetings -> SessionsEmptyState("person.2.fill", "No meetings recorded in the last $historyDays days.", "Show all sessions", onShowAll)
    }
}
