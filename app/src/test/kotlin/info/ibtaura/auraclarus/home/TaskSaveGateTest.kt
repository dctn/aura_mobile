package info.ibtaura.auraclarus.home

import info.ibtaura.aura.core.StubApi
import info.ibtaura.aura.core.StubAuth
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.LaunchFlags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Find a time opens at once after "Schedule" and waits here for the task's background save before placing it.

class TaskSaveGateTest {
    private val scope = CoroutineScope(SupervisorJob())

    /** Each test gets its own AppState, so the gate's per-app entries never leak between tests. */
    private val app = AppState(StubApi(), StubAuth(), LaunchFlags(), scope, modes = emptyList())

    @After
    fun tearDown() = scope.cancel()

    /** A save that never lands: awaiting it would hang, so a test that finishes proves it wasn't awaited. */
    private fun never() = CompletableDeferred<Boolean>()

    @Test
    fun nothingPendingAnswersTrueAtOnce() = runBlocking {
        assertTrue(withTimeout(1_000) { TaskSaveGate.await(app, null) })
        assertTrue(withTimeout(1_000) { TaskSaveGate.await(app, listOf("a")) })
    }

    @Test
    fun waitsForAPendingSaveAndReturnsItsAnswer() = runBlocking {
        val landed = CompletableDeferred<Boolean>()
        TaskSaveGate.track(app, "a", landed)
        val waiting = async { TaskSaveGate.await(app, listOf("a")) }
        repeat(3) { yield() }
        assertFalse(waiting.isCompleted, "await returned before the save landed")
        landed.complete(true)
        assertTrue(withTimeout(1_000) { waiting.await() })
    }

    @Test
    fun aRefusedSaveAnswersFalse() = runBlocking {
        TaskSaveGate.track(app, "a", CompletableDeferred(false))
        assertFalse(withTimeout(1_000) { TaskSaveGate.await(app, listOf("a")) })
    }

    @Test
    fun oneRefusedSaveAmongSeveralAnswersFalse() = runBlocking {
        val a = CompletableDeferred<Boolean>()
        val b = CompletableDeferred<Boolean>()
        val c = CompletableDeferred<Boolean>()
        TaskSaveGate.track(app, "a", a)
        TaskSaveGate.track(app, "b", b)
        TaskSaveGate.track(app, "c", c)
        val waiting = async { TaskSaveGate.await(app, listOf("a", "b", "c")) }
        a.complete(true)
        b.complete(false)
        repeat(3) { yield() }
        assertFalse(waiting.isCompleted, "await returned before every save was in")
        c.complete(true)
        assertFalse(withTimeout(1_000) { waiting.await() })
    }

    @Test
    fun idsLimitWhichSavesAreAwaited() = runBlocking {
        TaskSaveGate.track(app, "a", CompletableDeferred(true))
        TaskSaveGate.track(app, "other", never())
        // "other" never lands; asking only for "a" must not wait for it.
        val answer = withTimeoutOrNull(1_000) { TaskSaveGate.await(app, listOf("a")) }
        assertEquals(true, answer)
        // An id with nothing pending is not waited for either.
        assertEquals(true, withTimeoutOrNull(1_000) { TaskSaveGate.await(app, listOf("unknown")) })
        // The unrelated save is still tracked: asking for it (or for everything) does wait.
        assertNull(withTimeoutOrNull(200) { TaskSaveGate.await(app, listOf("other")) })
        assertNull(withTimeoutOrNull(200) { TaskSaveGate.await(app, null) })
    }

    @Test
    fun nullIdsWaitsForEverySave() = runBlocking {
        val a = CompletableDeferred<Boolean>()
        val b = CompletableDeferred<Boolean>()
        TaskSaveGate.track(app, "a", a)
        TaskSaveGate.track(app, "b", b)
        val waiting = async { TaskSaveGate.await(app, null) }
        a.complete(true)
        repeat(3) { yield() }
        assertFalse(waiting.isCompleted, "await(null) returned with a save still on its way")
        b.complete(true)
        assertTrue(withTimeout(1_000) { waiting.await() })
    }

    @Test
    fun aNewerTrackForTheSameIdReplacesTheOlder() = runBlocking {
        TaskSaveGate.track(app, "a", never())
        TaskSaveGate.track(app, "a", CompletableDeferred(true))
        // Only the newer save is awaited; the older one never lands.
        assertEquals(true, withTimeoutOrNull(1_000) { TaskSaveGate.await(app, listOf("a")) })
    }

    @Test
    fun aNewerRefusalReplacesAnOlderSuccess() = runBlocking {
        TaskSaveGate.track(app, "a", CompletableDeferred(true))
        TaskSaveGate.track(app, "a", CompletableDeferred(false))
        assertFalse(withTimeout(1_000) { TaskSaveGate.await(app, listOf("a")) })
    }

    @Test
    fun entriesAreForgottenOnceAwaited() = runBlocking {
        TaskSaveGate.track(app, "a", CompletableDeferred(false))
        assertFalse(withTimeout(1_000) { TaskSaveGate.await(app, listOf("a")) })
        // The refusal was reported once; a second wait finds nothing pending.
        assertTrue(withTimeout(1_000) { TaskSaveGate.await(app, listOf("a")) })
        assertTrue(withTimeout(1_000) { TaskSaveGate.await(app, null) })
    }

    @Test
    fun awaitingAnOlderSaveKeepsANewerOneTrackedMeanwhile() = runBlocking {
        val older = CompletableDeferred<Boolean>()
        TaskSaveGate.track(app, "a", older)
        val waiting = async { TaskSaveGate.await(app, listOf("a")) }
        repeat(3) { yield() }
        // A second save of the same task starts while the first is still awaited.
        TaskSaveGate.track(app, "a", CompletableDeferred(false))
        older.complete(true)
        assertTrue(withTimeout(1_000) { waiting.await() })
        // Forgetting the older entry must not drop the newer one.
        assertFalse(withTimeout(1_000) { TaskSaveGate.await(app, listOf("a")) })
    }

    @Test
    fun eachAppKeepsItsOwnSaves() = runBlocking {
        val other = AppState(StubApi(), StubAuth(), LaunchFlags(), scope, modes = emptyList())
        TaskSaveGate.track(other, "a", never())
        assertEquals(true, withTimeoutOrNull(1_000) { TaskSaveGate.await(app, null) })
        assertNull(withTimeoutOrNull(200) { TaskSaveGate.await(other, null) })
    }
}
