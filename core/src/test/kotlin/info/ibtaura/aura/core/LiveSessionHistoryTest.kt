package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Session history and the event read, against a real local HTTP server (MockWebServer): path, method, query and
// headers as they leave the phone, and how the answers are read.

class LiveSessionHistoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: LiveApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val config = NetRig.config.copy(services = ServiceName.entries.associate { it.raw to base })
        api = LiveApi(config, NetTokens(), OkHttpClient(), sleep = {}, now = { NetRig.now })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun answer(status: Int, body: String, type: String = "application/json") {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", type).setBody(body))
    }

    private fun taken(): RecordedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS), "No request reached the server")

    private val fullSession = """
        {"id":"s1","kind":"focus","mode":"timer","state":"ended","date":"2030-03-17","title":"Deep work",
         "startedAt":"2030-03-17T09:00:00Z","endedAt":"2030-03-17T09:50:00.123Z","plannedMinutes":50,
         "minutes":50,"activeMinutes":44.6,"protectedMinutes":38,"interruptions":3,"outcome":"completed",
         "difficulty":"hard","recoveryType":null,
         "notes":[{"at":"2030-03-17T09:10:00Z","text":"Call Ana"},{"at":"2030-03-17T09:20:00Z","text":"  "}],
         "capacity":{"before":72.5,"after":71.7,"cost":0.8,"charged":true},
         "rewards":{"xpEarned":45,"levelUp":true},"telemetry":{"appSwitches":2},"version":3}
    """.trimIndent()

    @Test
    fun sessionPageSendsTheRangeKindCursorAndLimit() = runBlocking<Unit> {
        answer(200, """{"sessions":[$fullSession,{"kind":"focus"}],"cursor":"next-1"}""")
        val page = api.sessionPage("2030-03-01", "2030-03-17", kind = "focus", cursor = "abc", limit = 50)

        val request = taken()
        assertEquals("GET", request.method)
        val url = assertNotNull(request.requestUrl)
        assertEquals("/v1/sessions", url.encodedPath)
        assertEquals("2030-03-01", url.queryParameter("from"))
        assertEquals("2030-03-17", url.queryParameter("to"))
        assertEquals("focus", url.queryParameter("kind"))
        assertEquals("abc", url.queryParameter("cursor"))
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize.coerceAtLeast(0))

        // The item without an id is left out; the full one is read whole.
        assertEquals(1, page.sessions.size)
        assertEquals("next-1", page.cursor)
        val s = page.sessions[0]
        assertEquals("s1", s.id)
        assertEquals("Deep work", s.record.title)
        assertEquals(45, s.record.minutes)
        assertEquals(45, s.activeMinutes)
        assertEquals(50, s.wallMinutes)
        assertEquals(38, s.protectedMinutes)
        assertEquals(3, s.interruptions)
        assertEquals("timer", s.mode)
        assertEquals("ended", s.state)
        assertEquals(50, s.plannedMinutes)
        assertEquals(Difficulty.hard, s.record.difficulty)
        assertEquals(SessionCapacity(72.5, 71.7, 0.8, true), s.capacity)
        assertEquals(listOf("Call Ana"), s.notes.map { it.text })
        assertEquals(45, s.xpEarned)
        assertTrue(s.levelUp)
    }

    @Test
    fun sessionPageLeavesOutWhatIsntGiven() = runBlocking<Unit> {
        answer(200, """{"sessions":[],"cursor":null}""")
        val page = api.sessionPage("2030-03-01", "2030-03-17")
        val url = assertNotNull(taken().requestUrl)
        assertEquals(setOf("from", "to"), url.queryParameterNames)
        assertNull(page.cursor)
        assertTrue(page.sessions.isEmpty())
    }

    @Test
    fun sessionReadsOneByIdEscaped() = runBlocking<Unit> {
        answer(200, """{"session":$fullSession}""")
        val detail = api.session("s 1")
        val request = taken()
        assertEquals("GET", request.method)
        assertEquals("/v1/sessions/s%201", request.requestUrl?.encodedPath)
        assertEquals("s1", detail.id)
        assertEquals(71.7, detail.capacity?.after)
    }

    @Test
    fun aMissingSessionIs404() = runBlocking<Unit> {
        answer(404, """{"error":{"code":"session_not_found","message":"Not found."}}""")
        val error = netCaught { api.session("gone") }
        assertEquals("session_not_found", (error as? AuraError)?.serverCode)
        assertEquals(404, (error as? AuraError.Server)?.status)
    }

    @Test
    fun aSessionWithoutCapacityOrNotesStillReads() = runBlocking<Unit> {
        answer(200, """{"session":{"id":"r1","kind":"recovery","recoveryType":"walk","minutes":15,"capacity":null,"notes":"bad"}}""")
        val detail = api.session("r1")
        assertNull(detail.capacity)
        assertTrue(detail.notes.isEmpty())
        assertEquals(15, detail.activeMinutes)
        assertEquals(RecoveryType.walk, detail.record.recoveryType)
    }

    @Test
    fun rewardsAreRead() = runBlocking<Unit> {
        answer(200, """
            {"xp":130,"level":3,"xpIntoLevel":10,"xpPerLevel":60,"plant":{"stage":2,"name":"Seedling"},
             "badges":[{"id":"first","title":"First Focus","requirementMinutes":1,"unlocked":true,"unlockedAt":"2030-03-01T10:00:00Z"},
                       {"id":"deep","title":"Deep Diver","requirementMinutes":300,"unlocked":false,"unlockedAt":null},
                       {"title":"no id"}],
             "totalFocusMinutes":130,"sessionsCompleted":4}
        """.trimIndent())
        val rewards = api.rewards()
        val request = taken()
        assertEquals("GET", request.method)
        assertEquals("/v1/rewards", request.requestUrl?.encodedPath)
        assertEquals(130, rewards.xp)
        assertEquals(3, rewards.level)
        assertEquals(10, rewards.xpIntoLevel)
        assertEquals("Seedling", rewards.plantName)
        assertEquals(2, rewards.plantStage)
        assertEquals(listOf("first", "deep"), rewards.badges.map { it.id })
        assertEquals(Instant.parse("2030-03-01T10:00:00Z"), rewards.badges[0].unlockedAt)
        assertFalse(rewards.badges[1].unlocked)
        assertEquals(4, rewards.sessionsCompleted)
        assertEquals(10.0 / 60, rewards.levelProgress, 1e-9)
    }

    @Test
    fun exportAsksForCsvAndHandsBackTheText() = runBlocking<Unit> {
        val csv = "id,date,kind\r\ns1,2030-03-17,focus\r\n"
        answer(200, csv, type = "text/csv; charset=utf-8")
        val text = api.exportSessionsCsv("2030-02-16", "2030-03-17")
        val request = taken()
        assertEquals("GET", request.method)
        val url = assertNotNull(request.requestUrl)
        assertEquals("/v1/sessions/export", url.encodedPath)
        assertEquals("csv", url.queryParameter("format"))
        assertEquals("2030-02-16", url.queryParameter("from"))
        assertEquals("2030-03-17", url.queryParameter("to"))
        assertEquals("text/csv", request.getHeader("Accept"))
        assertEquals(csv, text)
    }

    @Test
    fun exportTooLargeIs413() = runBlocking<Unit> {
        answer(413, """{"error":{"code":"too_large","message":"That range is too large for one file."}}""")
        val error = netCaught { api.exportSessionsCsv("2020-01-01", "2030-03-17") }
        assertEquals(413, (error as? AuraError.Server)?.status)
    }

    @Test
    fun traceReadsTheHeartRateAndStressPoints() = runBlocking<Unit> {
        answer(200, """
            {"sessionId":"s1","date":"2030-03-17","status":"ready","avgHr":71.5,"stressDrop":null,
             "hr":[{"at":"2030-03-17T09:01:00Z","bpm":72},{"at":"2030-03-17T09:00:00Z","bpm":70},
                   {"at":"2030-03-17T09:02:00Z","bpm":null},{"bpm":80}],
             "stress":[{"hour":10,"value":40},{"hour":9,"value":null},{"hour":30,"value":1}]}
        """.trimIndent())
        val trace = api.sessionTrace("s1")
        assertEquals("/v1/trends/sessions/s1", taken().requestUrl?.encodedPath)
        assertEquals("ready", trace.status)
        assertEquals(71.5, trace.avgHr)
        assertEquals(listOf(70.0, 72.0, null), trace.heartRate.map { it.bpm })
        assertEquals(listOf(9, 10), trace.stress.map { it.hour })
    }

    @Test
    fun eventReadsOneByIdWithItsVersion() = runBlocking<Unit> {
        answer(200, """{"event":{"id":"e1@2030-03-17","title":"Stand-up","start":"2030-03-17T09:00:00Z",
            "end":"2030-03-17T09:15:00Z","kind":"meeting","seriesId":"e1","version":7}}""")
        val event = assertNotNull(api.event("e1@2030-03-17"))
        val request = taken()
        assertEquals("GET", request.method)
        assertEquals("/v1/calendar/events/e1%402030-03-17", request.requestUrl?.encodedPath)
        assertEquals("Stand-up", event.title)
        assertEquals(7, event.version)
        assertTrue(event.isOccurrence)
    }

    @Test
    fun aDeletedEventIsNull() = runBlocking<Unit> {
        answer(404, """{"error":{"code":"event_not_found","message":"Not found."}}""")
        assertNull(api.event("gone"))
    }

    @Test
    fun anEventServerErrorIsThrown() = runBlocking<Unit> {
        answer(500, """{"error":{"code":"internal","message":"Oops."}}""")
        val error = netCaught { api.event("e1") }
        assertEquals(500, (error as? AuraError.Server)?.status)
    }
}
