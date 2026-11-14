package info.ibtaura.auraclarus.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScreenCacheTest {
    private var now = 0L

    /** Runs `block` with a cache on the test's own thread; the cache's loads don't keep runBlocking waiting. */
    private fun withCache(maxAgeMs: Long = 1_000L, block: suspend CoroutineScope.(ScreenCache) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
        try {
            block(ScreenCache(scope, maxAgeMs) { now })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFreshEntryIsAnsweredFromMemory() = withCache { cache ->
        var calls = 0
        assertEquals("a", cache.load("k") { calls += 1; "a" })
        assertEquals("a", cache.load("k") { calls += 1; "b" })
        assertEquals(1, calls)
        assertTrue(cache.isFresh("k"))
        assertEquals("a", cache.peek<String>("k"))
    }

    @Test
    fun anEntryOlderThanMaxAgeIsAskedAgain() = withCache { cache ->
        cache.load("k") { "a" }
        now += 999
        assertEquals("a", cache.load("k") { "b" })
        now += 1
        assertFalse(cache.isFresh("k"))
        assertEquals("a", cache.peek<String>("k"))
        assertEquals("b", cache.load("k") { "b" })
    }

    @Test
    fun markStaleKeepsTheValueButTheNextLoadAsksAgain() = withCache { cache ->
        cache.load("history:1") { 1 }
        cache.load("scores") { 2 }
        cache.markStale("history")
        assertEquals(1, cache.peek<Int>("history:1"))
        assertFalse(cache.isFresh("history:1"))
        assertTrue(cache.isFresh("scores"))
        assertEquals(10, cache.load("history:1") { 10 })
        cache.markStale()
        assertFalse(cache.isFresh("scores"))
    }

    @Test
    fun forceSkipsAFreshEntry() = withCache { cache ->
        cache.load("k") { "a" }
        assertEquals("b", cache.load("k", force = true) { "b" })
    }

    @Test
    fun twoLoadsOfTheSameKeyShareOneRequest() = withCache { cache ->
        val gate = CompletableDeferred<String>()
        var calls = 0
        val first = async { cache.load("k") { calls += 1; gate.await() } }
        val second = async { cache.load("k") { calls += 1; "other" } }
        yield()
        gate.complete("a")
        assertEquals("a", first.await())
        assertEquals("a", second.await())
        assertEquals(1, calls)
    }

    @Test
    fun aLoadStartedBeforeMarkStaleStoresAStaleEntry() = withCache { cache ->
        val gate = CompletableDeferred<String>()
        val going = async { cache.load("k") { gate.await() } }
        yield()
        cache.markStale()
        gate.complete("old")
        assertEquals("old", going.await())
        assertEquals("old", cache.peek<String>("k"))
        assertFalse(cache.isFresh("k"))
    }

    @Test
    fun aLoadAfterMarkStaleDoesNotJoinTheOlderRequest() = withCache { cache ->
        val gate = CompletableDeferred<String>()
        val older = async { cache.load("k") { gate.await() } }
        yield()
        cache.markStale()
        assertEquals("new", cache.load("k") { "new" })
        gate.complete("old")
        older.await()
        assertEquals("new", cache.peek<String>("k"))
    }

    @Test
    fun clearForgetsEverythingAndDropsTheAnswerOnItsWay() = withCache { cache ->
        cache.put("a", 1)
        val gate = CompletableDeferred<Int>()
        val going = async { runCatching { cache.load("b") { gate.await() } } }
        yield()
        cache.clear()
        gate.complete(2)
        assertTrue(going.await().isFailure)
        assertFalse(cache.has("a"))
        assertFalse(cache.has("b"))
        assertNull(cache.peek<Int>("b"))
    }

    @Test
    fun aFailedLoadKeepsTheEntryAndThrows() = withCache { cache ->
        cache.put("k", "kept")
        cache.markStale()
        try {
            cache.load<String>("k") { throw IllegalStateException("offline") }
            fail("expected the load to throw")
        } catch (e: IllegalStateException) {
            assertEquals("offline", e.message)
        }
        assertEquals("kept", cache.peek<String>("k"))
        assertEquals("again", cache.load("k") { "again" })
    }

    @Test
    fun putIsFreshAndUpdateKeepsAgeAndStaleness() = withCache { cache ->
        cache.put("list", listOf(1, 2, 3))
        assertTrue(cache.isFresh("list"))
        cache.markStale()
        cache.update<List<Int>>("list") { it - 2 }
        assertEquals(listOf(1, 3), cache.peek<List<Int>>("list"))
        assertFalse(cache.isFresh("list"))
        cache.update<List<Int>>("missing") { it + 1 }
        assertFalse(cache.has("missing"))
    }

    @Test
    fun aNullAnswerIsStillAnEntry() = withCache { cache ->
        var calls = 0
        assertNull(cache.load<String?>("plan") { calls += 1; null })
        assertTrue(cache.has("plan"))
        assertNull(cache.load<String?>("plan") { calls += 1; "x" })
        assertEquals(1, calls)
    }

    @Test
    fun maxAgeZeroAlwaysAsksButStillShowsTheLastAnswer() = withCache(maxAgeMs = 0L) { cache ->
        cache.load("k") { "a" }
        assertEquals("a", cache.peek<String>("k"))
        assertEquals("b", cache.load("k") { "b" })
    }

    @Test
    fun awaitIdleWaitsForRequestsOnTheirWay() = withCache { cache ->
        val gate = CompletableDeferred<String>()
        async { cache.load("k") { gate.await() } }
        yield()
        val idle = async { cache.awaitIdle(); cache.peek<String>("k") }
        yield()
        assertFalse(idle.isCompleted)
        gate.complete("done")
        assertEquals("done", idle.await())
    }

    @Test
    fun keysForDifferentRangesDiffer() {
        assertTrue(CacheKeys.series("hrv", "2026-11-01", "2026-11-14") != CacheKeys.series("hrv", "2026-11-02", "2026-11-14"))
        assertTrue(CacheKeys.historyWindow("all", "2026-11-01", "2026-11-10").startsWith(CacheKeys.HISTORY_PREFIX))
        assertTrue(CacheKeys.historyWeek("2026-11-08", "2026-11-14").startsWith(CacheKeys.HISTORY_PREFIX))
    }
}
