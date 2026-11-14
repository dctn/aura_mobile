package info.ibtaura.aura.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

// Session history (Search > Sessions): the whole sessionView, rewards and the paging over a month. Routes and shapes
// follow packages/contracts/src/session.ts in aws_docs. Pure Kotlin, so the grouping and paging are tested in core.

/** One heart-rate minute of a session's trace. `bpm` is null for a minute without a reading. */
data class HeartRatePoint(val at: Instant, val bpm: Double?)

/** One hour (0-23, the session day's local hour) of a session's stress trace. */
data class StressPoint(val hour: Int, val value: Double?)

/** A text note kept on a session (a walking brainstorm's notes). */
data class SessionNote(val at: Instant?, val text: String)

/** What the session did to the day's capacity: the score before and after it, and the points it cost. */
data class SessionCapacity(val before: Double, val after: Double, val cost: Double, val charged: Boolean) {
    /** After minus before: negative when the session cost points, positive when a break gave some back. */
    val change: Double get() = after - before
}

/**
 * The whole session, as GET /v1/sessions/{id} (and each item of GET /v1/sessions) sends it. `record` is the same
 * `SessionRecord` the Home list shows, so rating and titles work the same everywhere.
 */
data class SessionDetail(
    val record: SessionRecord,
    /** flowtime, timer, meeting, walk */
    val mode: String? = null,
    /** running, paused, break, ended */
    val state: String? = null,
    /** The local day it started on (a DayKey), as the backend filed it. */
    val date: String? = null,
    val plannedMinutes: Int? = null,
    /** Wall-clock minutes, pauses and breaks included. */
    val wallMinutes: Int = 0,
    /** Minutes worked: wall minutes less pauses and breaks. What the capacity charge counts. */
    val activeMinutes: Int = 0,
    /** Active minutes without an interruption. */
    val protectedMinutes: Int = 0,
    val interruptions: Int = 0,
    val capacity: SessionCapacity? = null,
    val notes: List<SessionNote> = emptyList(),
    /** XP the session earned (focus only), when it has ended. */
    val xpEarned: Int? = null,
    val levelUp: Boolean = false,
) {
    val id: String get() = record.id

    /** True once it has ended: only then can a focus session be rated. */
    val hasEnded: Boolean get() = state == "ended" || record.endedAt != null || record.outcome != null
}

/** One page of GET /v1/sessions. A non-null `cursor` means there are more. */
data class SessionPage(val sessions: List<SessionDetail>, val cursor: String?)

/** One of the four focus badges. */
data class RewardBadge(
    val id: String,
    val title: String,
    val requirementMinutes: Int,
    val unlocked: Boolean,
    val unlockedAt: Instant? = null,
)

/** GET /v1/rewards: XP (one per focus minute), a level every `xpPerLevel`, the plant and the badges. */
data class Rewards(
    val xp: Int,
    val level: Int,
    val xpIntoLevel: Int,
    val xpPerLevel: Int,
    val plantStage: Int,
    val plantName: String,
    val badges: List<RewardBadge>,
    val totalFocusMinutes: Int,
    val sessionsCompleted: Int,
) {
    /** How far into the current level, 0 to 1. */
    val levelProgress: Double
        get() = if (xpPerLevel <= 0) 0.0 else (xpIntoLevel.toDouble() / xpPerLevel).coerceIn(0.0, 1.0)
}

/**
 * The history's filter. `serverKind` goes to GET /v1/sessions as `kind` when one kind covers the filter; Recovery also
 * takes walks, so it asks for every kind and keeps the matching ones here. `matches` is applied either way.
 */
enum class SessionFilter(val title: String, val serverKind: String?) {
    all("All", null),
    focus("Focus", "focus"),
    recovery("Recovery", null),
    meetings("Meetings", "meeting");

    fun matches(kind: String): Boolean = when (this) {
        all -> true
        focus -> kind == "focus"
        recovery -> kind == "recovery" || kind == "walk"
        meetings -> kind == "meeting"
    }
}

/** One day of the history, newest session first. */
data class SessionDay(val date: LocalDate, val sessions: List<SessionDetail>) {
    /** The day's active minutes, every kind. */
    val minutes: Int get() = sessions.sumOf { it.activeMinutes }
}

/** The history header's week: Monday to today. */
data class WeekSummary(val focusMinutes: Int, val sessionsCompleted: Int, val sessions: Int)

object SessionHistory {
    /** Newest day first, newest session first in each day. A session without a start goes under its `date`, else last. */
    fun days(sessions: List<SessionDetail>, zone: ZoneId = DayKey.zone): List<SessionDay> {
        val byDay = LinkedHashMap<LocalDate, MutableList<SessionDetail>>()
        for (session in sessions) {
            val day = session.record.startedAt?.atZone(zone)?.toLocalDate()
                ?: session.date?.let { DayKey.localDate(it) }
                ?: continue
            byDay.getOrPut(day) { mutableListOf() }.add(session)
        }
        return byDay.entries
            .sortedByDescending { it.key }
            .map { (day, list) -> SessionDay(day, list.sortedByDescending { it.record.startedAt ?: Instant.MIN }) }
    }

    /** The Monday of `today`'s week. */
    fun weekStart(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Focus minutes (active, focus sessions only), completed sessions and all sessions from Monday to `today`. */
    fun week(sessions: List<SessionDetail>, today: LocalDate, zone: ZoneId = DayKey.zone): WeekSummary {
        val monday = weekStart(today)
        val inWeek = sessions.filter { session ->
            val day = session.record.startedAt?.atZone(zone)?.toLocalDate() ?: session.date?.let { DayKey.localDate(it) }
            day != null && day >= monday && day <= today
        }
        return WeekSummary(
            focusMinutes = inWeek.filter { it.record.isFocus }.sumOf { it.activeMinutes },
            sessionsCompleted = inWeek.count { it.record.outcome == "completed" },
            sessions = inWeek.size,
        )
    }

    /**
     * `totalDays` ending today, as consecutive (from, to) DayKey windows of at most `windowDays`, newest first. The
     * route takes at most 62 days a call.
     */
    fun windows(today: LocalDate, totalDays: Int = 30, windowDays: Int = 10): List<Pair<String, String>> {
        val size = windowDays.coerceIn(1, 62)
        val result = mutableListOf<Pair<String, String>>()
        var end = today
        val first = today.minusDays(totalDays.coerceAtLeast(1) - 1L)
        while (end >= first) {
            val start = end.minusDays(size - 1L).let { if (it < first) first else it }
            result.add(DayKey.string(start) to DayKey.string(end))
            end = start.minusDays(1)
        }
        return result
    }

    /** `more` added to `existing`; a session already there is replaced by the newer copy. Newest first. */
    fun merged(existing: List<SessionDetail>, more: List<SessionDetail>): List<SessionDetail> {
        val byId = LinkedHashMap<String, SessionDetail>()
        existing.forEach { byId[it.id] = it }
        more.forEach { byId[it.id] = it }
        return byId.values.sortedByDescending { it.record.startedAt ?: Instant.MIN }
    }

    /** A record the list knew, as a detail with only what a `SessionRecord` holds (the Home list's rows). */
    fun detailOf(record: SessionRecord): SessionDetail =
        SessionDetail(record = record, state = if (record.endedAt != null) "ended" else null,
            wallMinutes = record.minutes, activeMinutes = record.minutes, protectedMinutes = record.minutes)
}

/**
 * Loads the history window by window, newest first: each `loadNext` reads one whole window (every cursor page of it),
 * so a day is never shown half loaded. The route lists oldest first; the answer here is newest first, filtered.
 * A failed load leaves the window to try again. Not thread-safe: call it from one coroutine at a time.
 */
class SessionHistoryPager(
    private val api: AuraApi,
    today: LocalDate,
    val filter: SessionFilter = SessionFilter.all,
    totalDays: Int = 30,
    windowDays: Int = 10,
    private val pageLimit: Int = 100,
    private val maxPagesPerWindow: Int = 20,
) {
    val windows: List<Pair<String, String>> = SessionHistory.windows(today, totalDays, windowDays)
    private var next = 0

    val hasMore: Boolean get() = next < windows.size

    /** The oldest day loaded so far (a DayKey), or null before the first load. */
    val loadedFrom: String? get() = if (next == 0) null else windows[next - 1].first

    suspend fun loadNext(): List<SessionDetail> {
        if (!hasMore) return emptyList()
        val (from, to) = windows[next]
        val found = mutableListOf<SessionDetail>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = api.sessionPage(from, to, filter.serverKind, cursor, pageLimit)
            found.addAll(page.sessions)
            cursor = page.cursor?.takeIf { it.isNotEmpty() }
            pages += 1
        } while (cursor != null && pages < maxPagesPerWindow)
        next += 1
        return found.filter { filter.matches(it.record.kind) }.sortedByDescending { it.record.startedAt ?: Instant.MIN }
    }
}
