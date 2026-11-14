package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Session history: the grouping, the week, the windows and the pager (on the stub and on the network fake), and the
// stub's seeded month, rewards, export, trace and event read.

class SessionHistoryTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2030, 3, 20) // a Wednesday

    private fun at(day: LocalDate, hour: Int): Instant = day.atTime(hour, 0).atZone(zone).toInstant()

    private fun session(id: String, day: LocalDate, hour: Int, kind: String = "focus", minutes: Int = 30, outcome: String? = "completed") =
        SessionDetail(
            record = SessionRecord(id = id, kind = kind, startedAt = at(day, hour), endedAt = at(day, hour).plusSeconds(minutes * 60L),
                minutes = minutes, outcome = outcome),
            activeMinutes = minutes,
        )

    @Test
    fun daysGroupNewestFirst() {
        val list = listOf(
            session("a", today.minusDays(1), 9),
            session("b", today, 9),
            session("c", today, 15),
            session("d", today.minusDays(1), 16, minutes = 20),
        )
        val days = SessionHistory.days(list, zone)
        assertEquals(listOf(today, today.minusDays(1)), days.map { it.date })
        assertEquals(listOf("c", "b"), days[0].sessions.map { it.id })
        assertEquals(listOf("d", "a"), days[1].sessions.map { it.id })
        assertEquals(50, days[1].minutes)
    }

    @Test
    fun theWeekCountsFocusMinutesAndCompletedSinceMonday() {
        val monday = LocalDate.of(2030, 3, 18)
        val list = listOf(
            session("before", monday.minusDays(1), 9, minutes = 100),
            session("f1", monday, 9, minutes = 40),
            session("m1", monday, 11, kind = "meeting", minutes = 30),
            session("f2", today, 9, minutes = 25, outcome = "stopped"),
        )
        assertEquals(monday, SessionHistory.weekStart(today))
        assertEquals(WeekSummary(focusMinutes = 65, sessionsCompleted = 2, sessions = 3), SessionHistory.week(list, today, zone))
    }

    @Test
    fun windowsCoverThirtyDaysNewestFirst() {
        val windows = SessionHistory.windows(today, totalDays = 30, windowDays = 10)
        assertEquals(listOf("2030-03-11" to "2030-03-20", "2030-03-01" to "2030-03-10", "2030-02-19" to "2030-02-28"), windows)
        val uneven = SessionHistory.windows(today, totalDays = 12, windowDays = 5)
        assertEquals("2030-03-09" to "2030-03-10", uneven.last())
        assertEquals(3, uneven.size)
    }

    @Test
    fun mergedReplacesByIdAndSortsNewestFirst() {
        val old = session("a", today, 9)
        val newer = old.copy(record = old.record.copy(difficulty = Difficulty.hard))
        val merged = SessionHistory.merged(listOf(old, session("b", today, 8)), listOf(newer, session("c", today, 10)))
        assertEquals(listOf("c", "a", "b"), merged.map { it.id })
        assertEquals(Difficulty.hard, merged[1].record.difficulty)
    }

    @Test
    fun filtersMatchTheirKinds() {
        assertTrue(SessionFilter.recovery.matches("walk"))
        assertTrue(SessionFilter.recovery.matches("recovery"))
        assertFalse(SessionFilter.focus.matches("brainstorm"))
        assertTrue(SessionFilter.all.matches("brainstorm"))
        assertEquals("meeting", SessionFilter.meetings.serverKind)
        assertNull(SessionFilter.recovery.serverKind)
    }

    // MARK: The pager

    @Test
    fun thePagerFollowsCursorsWindowByWindow() = runBlocking<Unit> {
        val rig = NetRig()
        val one = """{"id":"s1","kind":"focus","startedAt":"2030-03-12T09:00:00Z"}"""
        val two = """{"id":"s2","kind":"focus","startedAt":"2030-03-19T09:00:00Z"}"""
        val three = """{"id":"w1","kind":"walk","startedAt":"2030-03-05T09:00:00Z"}"""
        rig.server.respond("/v1/sessions") { call ->
            when {
                call.query["from"] == "2030-03-11" && call.query["cursor"] == null -> netJson(200, """{"sessions":[$one],"cursor":"p2"}""")
                call.query["from"] == "2030-03-11" -> netJson(200, """{"sessions":[$two],"cursor":null}""")
                else -> netJson(200, """{"sessions":[$three],"cursor":null}""")
            }
        }
        val pager = SessionHistoryPager(rig.api, today, totalDays = 20, windowDays = 10, pageLimit = 1)
        assertTrue(pager.hasMore)
        val first = pager.loadNext()
        assertEquals(listOf("s2", "s1"), first.map { it.id })
        assertEquals("2030-03-11", pager.loadedFrom)
        val calls = rig.server.calls("/v1/sessions")
        assertEquals(listOf(null, "p2"), calls.map { it.query["cursor"] })
        assertEquals("1", calls[0].query["limit"])
        assertNull(calls[0].query["kind"])
        assertEquals(listOf("w1"), pager.loadNext().map { it.id })
        assertFalse(pager.hasMore)
        assertTrue(pager.loadNext().isEmpty())
    }

    @Test
    fun thePagerSendsTheFilterKindAndKeepsAFailedWindow() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions", netJson(500, """{"error":{"code":"internal","message":"x"}}"""),
            netJson(200, """{"sessions":[{"id":"m1","kind":"meeting","startedAt":"2030-03-19T09:00:00Z"}],"cursor":null}"""))
        val pager = SessionHistoryPager(rig.api, today, filter = SessionFilter.meetings)
        assertNotNull(netCaught { pager.loadNext() })
        assertNull(pager.loadedFrom)
        assertEquals(listOf("m1"), pager.loadNext().map { it.id })
        assertTrue(rig.server.calls("/v1/sessions").all { it.query["kind"] == "meeting" })
    }

    @Test
    fun theRecoveryFilterAsksForEverythingAndKeepsBreaksAndWalks() = runBlocking<Unit> {
        val api = StubApi(now = { at(today, 12) })
        val pager = SessionHistoryPager(api, today, filter = SessionFilter.recovery)
        val found = mutableListOf<SessionDetail>()
        while (pager.hasMore) found.addAll(pager.loadNext())
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.record.kind == "recovery" || it.record.kind == "walk" })
    }

    // MARK: The stub's month

    private fun stub() = StubApi(now = { at(today, 12) })

    @Test
    fun theStubSeedsAMonthOfBelievableSessions() = runBlocking<Unit> {
        val api = stub()
        val all = mutableListOf<SessionDetail>()
        var cursor: String? = null
        do {
            val page = api.sessionPage("2030-02-19", "2030-03-20", cursor = cursor, limit = 25)
            all.addAll(page.sessions)
            cursor = page.cursor
        } while (cursor != null)
        assertTrue(all.size > 60, "Only ${all.size} sessions")
        assertEquals(all.map { it.id }.toSet().size, all.size, "Ids must be unique")
        // Oldest first, as the route lists them; the stub's own live session is today.
        assertEquals(all.sortedBy { it.record.startedAt }.map { it.id }, all.map { it.id })
        assertTrue(all.any { it.id == "s-deep" })
        assertTrue(all.none { it.id.startsWith("h-") && it.date!! >= "2030-03-19" }, "Seeds stop two days ago")
        assertTrue(all.any { it.notes.isNotEmpty() })
        assertTrue(all.filter { it.id.startsWith("h-") }.all { it.capacity != null })
        assertEquals(setOf("focus", "meeting", "recovery", "brainstorm"), all.map { it.record.kind }.toSet())
    }

    @Test
    fun theStubKindFilterAndDetail() = runBlocking<Unit> {
        val api = stub()
        val meetings = api.sessionPage("2030-03-01", "2030-03-18", kind = "meeting").sessions
        assertTrue(meetings.isNotEmpty())
        assertTrue(meetings.all { it.record.kind == "meeting" })
        val one = meetings.first()
        assertEquals(one, api.session(one.id))
        assertEquals("s-deep", api.session("s-deep").id)
        val missing = assertFailsWith<AuraError.Server> { api.session("nope") }
        assertEquals(404, missing.status)
    }

    @Test
    fun ratingAHistorySessionStaysOnItsDay() = runBlocking<Unit> {
        val api = stub()
        val focus = api.sessionPage("2030-03-10", "2030-03-10", kind = "focus").sessions.first()
        val rated = api.setDifficulty(focus.id, Difficulty.veryHard)
        assertEquals(Difficulty.veryHard, rated.difficulty)
        assertEquals(Difficulty.veryHard, api.session(focus.id).record.difficulty)
        // Nothing new shows up today.
        assertTrue(api.sessions("2030-03-20", "2030-03-20").none { it.id == focus.id })
        val meeting = api.sessionPage("2030-03-01", "2030-03-18", kind = "meeting").sessions.first()
        val refused = assertFailsWith<AuraError.Server> { api.setDifficulty(meeting.id, Difficulty.easy) }
        assertEquals("not_focus_session", refused.code)
    }

    @Test
    fun theStubRewardsFollowTheXpRules() = runBlocking<Unit> {
        val rewards = stub().rewards()
        assertTrue(rewards.xp > 300)
        assertEquals(rewards.xp / 60 + 1, rewards.level)
        assertEquals(rewards.xp % 60, rewards.xpIntoLevel)
        assertEquals(listOf("first", "hour", "deep", "master"), rewards.badges.map { it.id })
        assertTrue(rewards.badges.first { it.id == "deep" }.unlocked)
        assertNotNull(rewards.badges.first { it.id == "deep" }.unlockedAt)
    }

    @Test
    fun theStubExportsTheServersColumns() = runBlocking<Unit> {
        val csv = stub().exportSessionsCsv("2030-03-01", "2030-03-20")
        val lines = csv.trimEnd().split("\r\n")
        assertTrue(lines[0].startsWith("id,date,kind,mode,title,"))
        assertTrue(lines.size > 20)
        assertTrue(lines.any { it.contains("s-deep") })
        assertEquals("\"a,b\"", StubSessionHistory.csvCell("a,b"))
        assertEquals("'=1", StubSessionHistory.csvCell("=1"))
    }

    @Test
    fun theStubTraceIsReadyForSeedsOnly() = runBlocking<Unit> {
        val api = stub()
        val focus = api.sessionPage("2030-03-10", "2030-03-10", kind = "focus").sessions.first()
        val trace = api.sessionTrace(focus.id)
        assertEquals("ready", trace.status)
        assertTrue(trace.heartRate.size > 20)
        assertTrue(trace.heartRate.any { it.bpm == null })
        assertNotNull(trace.avgHr)
        assertTrue(trace.stress.isNotEmpty())
        assertEquals("noData", api.sessionTrace("s-deep").status)
    }

    @Test
    fun theStubReadsAnEventByIdAndForgetsADeletedOne() = runBlocking<Unit> {
        val api = stub()
        assertEquals("10 Min nap", api.event("e1")?.title)
        assertNull(api.event("unknown"))
        api.deleteEvent("e1")
        assertNull(api.event("e1"))
    }
}
