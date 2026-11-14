package info.ibtaura.aura.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The network speed pass: the shared GET /v1/me, planner block clean-up without needless calls, parallel reads, and
// the connection warm-up. All against the fake network (FakeNetwork.kt) or a local MockWebServer.

private const val ME_V7 = "{\"sub\":\"sub-1\",\"timezone\":null,\"version\":7,\"settings\":{\"units\":\"metric\"}," +
    "\"healthGoals\":{\"waterGoalMl\":2000,\"bedtime\":\"23:00\",\"wakeTime\":\"07:00\"}," +
    "\"workSchedule\":{\"workdayStart\":\"08:30\",\"workdayEnd\":\"17:00\",\"lunchStart\":\"12:30\",\"lunchMinutes\":45,\"workDays\":[1,2,3,4,5]}}"
private const val ME_V8 = "{\"sub\":\"sub-1\",\"timezone\":\"Europe/Paris\",\"version\":8,\"settings\":{\"units\":\"imperial\"}," +
    "\"healthGoals\":{\"waterGoalMl\":2500}}"
private const val STALE_PROFILE = "{\"error\":{\"code\":\"precondition_failed\",\"message\":\"Changed.\"}}"

/** A live API on the fake network that keeps GET /v1/me, as LiveServices builds it. */
private class CachedRig {
    val server = NetServer()
    val api = LiveApi(NetRig.config, NetTokens(), server.client(), sleep = {}, now = { NetRig.now },
        meKeepMillis = MeCache.KEEP_MILLIS)

    fun meReads(): Int = server.calls("/v1/me").size
}

class MeCacheTest {
    @Test
    fun concurrentReadsShareOneRequest() = runBlocking<Unit> {
        val fetches = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val cache = MeCache(MeCache.KEEP_MILLIS) {
            fetches.incrementAndGet()
            gate.await()
            ME_V7
        }
        val readers = (0 until 5).map { async { cache.text() } }
        // Let every reader reach the cache before the one request answers.
        kotlinx.coroutines.yield()
        gate.complete(Unit)
        assertEquals(List(5) { ME_V7 }, readers.awaitAll())
        assertEquals(1, fetches.get())
        assertEquals(ME_V7, cache.text())
        assertEquals(1, fetches.get(), "A young copy is kept")
    }

    @Test
    fun anOldCopyIsReadAgain() = runBlocking<Unit> {
        val clock = AtomicLong(0)
        val fetches = AtomicInteger(0)
        val cache = MeCache(1_000, ticker = { clock.get() }) { if (fetches.incrementAndGet() == 1) ME_V7 else ME_V8 }
        assertEquals(ME_V7, cache.text())
        clock.set(999_000_000)
        assertEquals(ME_V7, cache.text())
        clock.set(1_000_000_000)
        assertEquals(ME_V8, cache.text())
        assertEquals(2, fetches.get())
    }

    @Test
    fun aFailedReadIsNotKept() = runBlocking<Unit> {
        val fetches = AtomicInteger(0)
        val cache = MeCache(MeCache.KEEP_MILLIS) {
            if (fetches.incrementAndGet() == 1) throw AuraError.Offline
            ME_V7
        }
        assertEquals(AuraError.Offline, netCaught { cache.text() })
        assertEquals(ME_V7, cache.text())
        assertEquals(2, fetches.get())
    }

    @Test
    fun aWriteKeepsItsAnswerAndAFailedWriteForgets() = runBlocking<Unit> {
        val fetches = AtomicInteger(0)
        val cache = MeCache(MeCache.KEEP_MILLIS) { fetches.incrementAndGet(); ME_V7 }
        cache.text()
        cache.write { ME_V8 }
        assertEquals(ME_V8, cache.text())
        assertEquals(1, fetches.get())
        netCaught { cache.write { throw AuraError.Server("precondition_failed", "Changed.", 412) } }
        assertEquals(ME_V7, cache.text())
        assertEquals(2, fetches.get())
        cache.clear()
        cache.text()
        assertEquals(3, fetches.get())
    }

    @Test
    fun aReadStartedBeforeAWriteDoesNotOverwriteIt() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val fetches = AtomicInteger(0)
        val cache = MeCache(MeCache.KEEP_MILLIS) {
            if (fetches.incrementAndGet() == 1) gate.await()
            ME_V7
        }
        val early = async { cache.text() }
        kotlinx.coroutines.yield()
        cache.store(ME_V8)
        gate.complete(Unit)
        assertEquals(ME_V7, early.await())
        assertEquals(ME_V8, cache.text(), "The write's answer is newer than the read that started before it")
    }

    @Test
    fun withoutKeepingEveryReadIsItsOwnRequest() = runBlocking<Unit> {
        val fetches = AtomicInteger(0)
        val cache = MeCache(0) { fetches.incrementAndGet(); ME_V7 }
        cache.text()
        cache.store(ME_V8)
        assertEquals(ME_V7, cache.text())
        assertEquals(2, fetches.get())
    }
}

class LiveMeCacheTest {
    @Test
    fun screensReadingTheProfileAtOnceShareOneRequest() = runBlocking<Unit> {
        val rig = CachedRig()
        rig.server.on("/v1/me", listOf(netJson(200, ME_V7)), delayMs = 50)
        coroutineScope {
            val reads = listOf(
                async { rig.api.workSchedule() },
                async { rig.api.sleepSchedule() },
                async { rig.api.meSettings() },
                async { rig.api.waterGoalMl() },
                async { rig.api.goalsProfile() },
            )
            reads.awaitAll()
        }
        assertEquals(1, rig.meReads())
        assertEquals(2000, rig.api.waterGoalMl())
        assertEquals(1, rig.meReads())
    }

    @Test
    fun allocatePreviewsReadTheProfileOnce() = runBlocking<Unit> {
        val rig = CachedRig()
        val plan = "{\"plan\":{\"date\":\"2030-03-17\",\"generatedAt\":\"2030-03-17T03:00:00.000Z\",\"placed\":[],\"unplaced\":[]}}"
        rig.server.on("/v1/me", netJson(200, ME_V7))
        rig.server.on("/v1/planner/allocate", netJson(200, plan))
        repeat(3) { rig.api.allocate("2030-03-17", dryRun = true, skipCheck = false, taskIds = null) }
        assertEquals(1, rig.meReads())
        val sent = rig.server.calls("/v1/planner/allocate")
        assertEquals(3, sent.size)
        assertTrue(sent.all { it.string("workdayStart") == "08:30" })
    }

    @Test
    fun aSettingsWriteReplacesTheCopy() = runBlocking<Unit> {
        val rig = CachedRig()
        rig.server.on("/v1/me", netJson(200, ME_V7))
        rig.server.on("/v1/me/settings", netJson(200, ME_V8))
        assertEquals(JsonPrimitive("metric"), rig.api.meSettings()["units"])
        rig.api.putMeSettings(mapOf("units" to JsonPrimitive("imperial")))
        assertEquals(JsonPrimitive("imperial"), rig.api.meSettings()["units"])
        assertEquals(2500, rig.api.waterGoalMl())
        assertEquals(1, rig.meReads(), "The write's answer is the new profile, so no read follows it")
    }

    @Test
    fun aGoalsWriteReplacesTheCopy() = runBlocking<Unit> {
        val rig = CachedRig()
        rig.server.on("/v1/me", netJson(200, ME_V7))
        rig.server.on("/v1/me/profile", netJson(200, ME_V8))
        assertEquals(2000, rig.api.waterGoalMl())
        rig.api.putHealthGoals(mapOf(HealthGoals.WATER_ML to JsonPrimitive(2500)), version = 7)
        assertEquals(2500, rig.api.waterGoalMl())
        assertEquals(1, rig.meReads())
    }

    @Test
    fun aStaleCopyIsReadAgainAfterA412() = runBlocking<Unit> {
        val rig = CachedRig()
        val newer = ME_V7.replace("\"version\":7", "\"version\":9")
        rig.server.on("/v1/me", netJson(200, ME_V7), netJson(200, newer))
        rig.server.on("/v1/me/profile", netJson(412, STALE_PROFILE), netJson(200, ME_V8))
        rig.api.workSchedule()
        rig.api.setWorkSchedule(WorkSchedule.suggested)
        val puts = rig.server.calls("/v1/me/profile")
        assertEquals(listOf("\"v7\"", "\"v9\""), puts.map { it.header("If-Match") })
        assertEquals(2, rig.meReads())
        assertNull(rig.api.workSchedule(), "The write's answer (no work hours in it) is kept")
        assertEquals(2, rig.meReads())
    }

    @Test
    fun aFailedWriteForgetsTheCopy() = runBlocking<Unit> {
        val rig = CachedRig()
        rig.server.on("/v1/me", netJson(200, ME_V7))
        rig.server.on("/v1/me/settings", netJson(500, "{\"error\":{\"code\":\"internal\",\"message\":\"Try again.\"}}"))
        rig.api.meSettings()
        netCaught { rig.api.putMeSettings(mapOf("units" to JsonPrimitive("imperial"))) }
        rig.api.meSettings()
        assertEquals(2, rig.meReads())
    }

    @Test
    fun clearCachesReadsAgain() = runBlocking<Unit> {
        val rig = CachedRig()
        rig.server.on("/v1/me", netJson(200, ME_V7))
        rig.api.meSettings()
        rig.api.clearCaches()
        rig.api.meSettings()
        assertEquals(2, rig.meReads())
    }
}

class LivePlannerCleanupSpeedTest {
    private fun blocks(vararg ids: String): String {
        val start = WireDate.string(NetRig.now.plusSeconds(3600))
        return "{\"events\":[" + ids.joinToString(",") { id ->
            "{\"id\":\"$id\",\"title\":\"x\",\"start\":\"$start\",\"end\":\"$start\",\"source\":\"planner\",\"taskId\":\"t1\"}"
        } + "],\"cursor\":null}"
    }

    @Test
    fun aTaskWithNoBlockSkipsTheCalendarRead() = runBlocking<Unit> {
        val rig = NetRig()
        val task = TaskItem(id = "t1", title = "Reply", autoAllocate = true)
        assertEquals(0, rig.api.removePlannerBlocks(task))
        assertTrue(rig.server.allCalls.isEmpty())
    }

    @Test
    fun aTaskWithABlockIsLookedFor() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/calendar/events", netJson(200, blocks("plan-2030-01-02-t1")))
        rig.server.on("/v1/calendar/events/plan-2030-01-02-t1", netJson(204, ""))
        val task = TaskItem(id = "t1", title = "Reply", blockId = "plan-2030-01-02-t1")
        assertEquals(1, rig.api.removePlannerBlocks(task))
        assertEquals(1, rig.server.calls("/v1/calendar/events").size)
    }

    @Test
    fun aTaskPlacedInThisRunIsLookedForBeforeTaskMirrorsIt() = runBlocking<Unit> {
        val rig = NetRig()
        val plan = "{\"plan\":{\"date\":\"2030-03-17\",\"generatedAt\":\"2030-03-17T03:00:00.000Z\",\"placed\":[" +
            "{\"taskId\":\"t1\",\"blockId\":\"plan-2030-03-17-t1\",\"start\":\"2030-03-17T04:00:00.000Z\"," +
            "\"end\":\"2030-03-17T05:00:00.000Z\",\"demand\":40,\"reason\":\"Fits.\"}],\"unplaced\":[]}}"
        rig.server.on("/v1/me", netJson(200, ME_V7))
        rig.server.on("/v1/planner/allocate", netJson(200, plan))
        rig.server.on("/v1/calendar/events", netJson(200, "{\"events\":[],\"cursor\":null}"))
        rig.api.allocate("2030-03-17", dryRun = false, skipCheck = false, taskIds = null)
        rig.api.removePlannerBlocks(TaskItem(id = "t1", title = "Reply"))
        assertEquals(1, rig.server.calls("/v1/calendar/events").size)
        rig.api.removePlannerBlocks(TaskItem(id = "t2", title = "Other"))
        assertEquals(1, rig.server.calls("/v1/calendar/events").size, "t2 wasn't placed")
    }

    @Test
    fun deletesRunSideBySideAndAllGo() = runBlocking<Unit> {
        val rig = NetRig()
        val ids = (1..6).map { "plan-2030-01-02-t1-p$it" }
        rig.server.on("/v1/calendar/events", netJson(200, blocks(*ids.toTypedArray())))
        for (id in ids) rig.server.on("/v1/calendar/events/$id", listOf(netJson(204, "")), delayMs = 100)
        val removed = rig.api.removePlannerBlocks("t1")
        assertEquals(6, removed)
        val deletes = rig.server.allCalls.filter { it.method == "DELETE" }.map { it.key }
        assertEquals(ids.map { "/v1/calendar/events/$it" }.sorted(), deletes.sorted())
        assertTrue(rig.server.peakOpen > 1, "deletes run at the same time (peak ${rig.server.peakOpen})")
    }

    @Test
    fun unpinWithAVersionPatchesAtOnce() = runBlocking<Unit> {
        val rig = NetRig()
        val written = "{\"task\":{\"id\":\"t1\",\"title\":\"Reply\",\"autoAllocate\":false,\"version\":4}}"
        rig.server.on("/v1/tasks/t1", netJson(200, written))
        rig.api.unpinTaskFromPlanner("t1", version = 3)
        val calls = rig.server.calls("/v1/tasks/t1")
        assertEquals(listOf("PATCH"), calls.map { it.method })
        assertEquals("\"v3\"", calls[0].header("If-Match"))
    }
}

class LiveParallelReadsTest {
    @Test
    fun goalProgressReadsRunSideBySide() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/metrics/daily", listOf(netJson(200, "{\"days\":[],\"cursor\":null}")), delayMs = 150)
        rig.server.on("/v1/logs", listOf(netJson(200, "{\"logs\":[]}")), delayMs = 150)
        rig.server.on("/v1/sessions", listOf(netJson(200, "{\"sessions\":[],\"cursor\":null}")), delayMs = 150)
        val progress = rig.api.goalProgressToday()
        assertEquals(0.0, progress.waterMl)
        assertEquals(0, progress.focusMinutes)
        assertEquals(3, rig.server.peakOpen)
    }

    @Test
    fun sessionsAskForFullPages() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions", netJson(200, "{\"sessions\":[],\"cursor\":null}"))
        rig.api.sessions("2030-03-16", "2030-03-17")
        assertEquals("500", rig.server.calls("/v1/sessions").single().query["limit"])
    }
}

class ConnectionWarmerTest {
    private fun localServer(hits: MutableList<RecordedRequest>): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(hits) { hits.add(request) }
                return MockResponse().setResponseCode(404).setBody("{\"message\":\"Not Found\"}")
            }
        }
        start()
    }

    @Test
    fun opensTwoForTheBusyHostsAndOneForTheRest() {
        val config = NetRig.config { services ->
            services["identity"] = "https://id.stub.test/dev/"
            services["health-data"] = "https://hd.stub.test"
            services["session"] = "https://ss.stub.test/x?y=1"
        }
        val targets = ConnectionWarmer(config, OkHttpClient()).targets()
        val byHost = targets.groupBy { it.second.host }.mapValues { it.value.size }
        assertEquals(2, byHost["hd.stub.test"])
        assertEquals(2, byHost["ss.stub.test"])
        assertEquals(1, byHost["id.stub.test"])
        assertTrue(targets.all { it.second.encodedPath == "/" && it.second.query == null })
    }

    @Test
    fun sendsUnsignedHeadsAndStaysQuietForAWhile() = runBlocking<Unit> {
        val hits = mutableListOf<RecordedRequest>()
        val server = localServer(hits)
        try {
            val base = server.url("/dev/").toString()
            val config = NetRig.config.copy(services = mapOf("identity" to base, "task" to base))
            val clock = AtomicLong(0)
            val warmer = ConnectionWarmer(config, OkHttpClient(), quietMillis = 30_000, ticker = { clock.get() })
            warmer.warmUp()
            assertEquals(1, hits.size, "One host, opened once")
            val hit = hits.single()
            assertEquals("HEAD", hit.method)
            assertEquals("/", hit.path)
            assertNull(hit.getHeader("Authorization"))
            clock.set(29_000_000_000)
            warmer.warmUp()
            assertEquals(1, hits.size, "Within 30 s nothing goes")
            clock.set(31_000_000_000)
            warmer.warmUp()
            assertEquals(2, hits.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun errorsAreIgnored() = runBlocking<Unit> {
        // Nothing listens here, so every connection fails.
        val config = NetRig.config.copy(services = mapOf("identity" to "http://127.0.0.1:1/"))
        assertNull(netCaught { ConnectionWarmer(config, OkHttpClient()).warmUp() })
    }

    @Test
    fun theStubsDoNothing() = runBlocking<Unit> {
        val stub = StubApi()
        assertNull(netCaught { stub.warmUp() })
        stub.clearCaches()
    }
}
