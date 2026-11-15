package info.ibtaura.auraclarus.run

import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.RecoveryType
import info.ibtaura.aura.core.RemoteSession
import info.ibtaura.aura.core.RunEngine
import info.ibtaura.aura.core.RunKind
import info.ibtaura.aura.core.SessionSpan
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Cross-device sync's decisions and the adopted run's clock, without Android.

class RunSyncTest {
    private val now: Instant = Instant.parse("2030-03-18T12:00:00Z")
    private val started: Instant = now.minusSeconds(5 * 60)

    /** A 30-minute focus session on the Mac, started five minutes ago. */
    private fun remote(
        id: String = "s1",
        state: String = "running",
        endsAt: Instant? = started.plusSeconds(30 * 60),
        pauses: List<SessionSpan> = emptyList(),
        version: Int = 3,
        kind: String = "focus",
        mode: String = "timer",
        title: String? = "Deep work",
    ) = RemoteSession(
        id = id, kind = kind, mode = mode, state = state, title = title, device = "mac", plannedMinutes = 30,
        startedAt = started, endsAt = endsAt, pauses = pauses, version = version,
    )

    /** The same session as the phone holds it. */
    private fun local(
        engine: RunEngine = RunEngine(duration = 30 * 60.0, hasCountdown = false, startsAt = started),
        sessionId: String? = "s1",
        finished: Boolean = false,
        createsSession: Boolean = true,
        lastVersion: Int = 3,
    ) = LocalRunState(engine, sessionId, finished, createsSession, lastVersion)

    private fun decide(
        local: LocalRunState?,
        remote: RemoteSession?,
        inFlight: Boolean = false,
        closed: Set<String> = emptySet(),
    ) = RunSync.decide(local, remote, inFlight, closed, now)

    // Adopting

    @Test fun adoptsWhenIdle() {
        assertEquals(listOf<SyncAction>(SyncAction.Adopt), decide(null, remote()))
    }

    @Test fun nothingRunningAnywhereChangesNothing() {
        assertEquals(emptyList(), decide(null, null))
    }

    @Test fun doesNotAdoptARecentlyClosedSession() {
        assertEquals(emptyList(), decide(null, remote(), closed = setOf("s1")))
    }

    @Test fun doesNotAdoptASessionWhoseTimeIsUp() {
        assertEquals(emptyList(), decide(null, remote(endsAt = now.minusSeconds(10))))
    }

    @Test fun adoptedClockEndsWhenTheSessionDoes() {
        val engine = RunSync.engineFor(remote(), now)
        assertEquals(25 * 60.0, engine.snapshot(now).remaining!!, 0.01)
        assertEquals(started.plusSeconds(30 * 60), engine.endDate())
    }

    @Test fun adoptedPausedClockIsFrozen() {
        // Paused two minutes ago: the backend's endsAt leaves the open pause out, with its minutes so far.
        val pausedAt = now.minusSeconds(120)
        val session = remote(state = "paused", pauses = listOf(SessionSpan(pausedAt, 2.0)))
        val engine = RunSync.engineFor(session, now)
        assertTrue(engine.isPaused)
        assertEquals(27 * 60.0, engine.snapshot(now).remaining!!, 0.01)
        assertEquals(27 * 60.0, engine.snapshot(now.plusSeconds(600)).remaining!!, 0.01)
    }

    @Test fun pausedTimeLeftIsTheSameWhetherOrNotEndsAtCountsTheOpenPause() {
        val pausedAt = now.minusSeconds(120)
        val backend = remote(state = "paused", pauses = listOf(SessionSpan(pausedAt, 2.0)))
        val stub = remote(state = "paused", endsAt = started.plusSeconds(32 * 60), pauses = listOf(SessionSpan(pausedAt, 0.0)))
        assertEquals(27 * 60.0, RunSync.remaining(backend, now)!!, 0.01)
        assertEquals(27 * 60.0, RunSync.remaining(stub, now)!!, 0.01)
    }

    @Test fun adoptedOpenEndedRunCountsUp() {
        val engine = RunSync.engineFor(remote(endsAt = null, mode = "flowtime"), now)
        assertTrue(engine.isOpenEnded)
        assertNull(engine.snapshot(now).remaining)
        assertEquals(5 * 60.0, engine.snapshot(now).elapsed, 0.01)
    }

    @Test fun modeComesFromTitleThenSessionModeThenKind() {
        val modes = Mode.loadCatalogue()
        assertEquals("timer", RunSync.modeFor(remote(), modes).id)
        assertEquals("flow", RunSync.modeFor(remote(mode = "flowtime", endsAt = null), modes).id)
        assertEquals("meeting", RunSync.modeFor(remote(kind = "meeting", mode = "meeting"), modes).id)
        assertEquals("yoga", RunSync.modeFor(remote(kind = "walk", mode = "walk", title = "Yoga"), modes).id)
        assertEquals("urban-walk", RunSync.modeFor(remote(kind = "walk", mode = "walk", title = null), modes).id)
        val nap = remote(kind = "recovery", title = null).copy(recoveryType = RecoveryType.nap)
        assertEquals("nap-20", RunSync.modeFor(nap, modes).id)
        assertEquals("timer", RunSync.modeFor(remote(kind = "brainstorm"), modes).id)
        // No catalogue at all still gives a timed mode.
        assertEquals(RunKind.timer, RunSync.modeFor(remote(), emptyList()).run)
    }

    @Test fun startedOnWords() {
        assertEquals("Started on your Mac", RunSync.startedOnText("mac"))
        assertEquals("Started on your PC", RunSync.startedOnText("windows"))
        assertEquals("Started on your other phone", RunSync.startedOnText("phone"))
        assertEquals("Started on your computer", RunSync.startedOnText("desktop"))
        assertEquals("Started in your browser", RunSync.startedOnText("web"))
        assertNull(RunSync.startedOnText(null))
    }

    // Reconciling

    @Test fun inStepChangesNothing() {
        assertEquals(emptyList(), decide(local(), remote()))
    }

    @Test fun pausedElsewherePausesHereWhenItPaused() {
        val pausedAt = now.minusSeconds(30)
        val actions = decide(local(), remote(state = "paused", pauses = listOf(SessionSpan(pausedAt, 0.5)), version = 4))
        assertEquals(listOf(SyncAction.Pause(pausedAt), SyncAction.Seen(4)), actions)
    }

    @Test fun resumedElsewhereResumesHere() {
        val paused = RunEngine(duration = 30 * 60.0, hasCountdown = false, startsAt = started, pausedAt = now.minusSeconds(60))
        // Paused a minute and resumed: the end moved a minute later, well inside the slack.
        val actions = decide(local(engine = paused), remote(endsAt = started.plusSeconds(31 * 60), version = 5))
        assertEquals(listOf(SyncAction.Resume, SyncAction.Seen(5)), actions)
    }

    @Test fun extendedElsewhereMovesTheEnd() {
        val later = started.plusSeconds(40 * 60)
        val actions = decide(local(), remote(endsAt = later, version = 4))
        assertEquals(listOf(SyncAction.MoveEnd(later), SyncAction.Seen(4)), actions)
        val moved = RunSync.movedEnd(local().engine, later, now)
        assertEquals(later, moved.endDate())
    }

    @Test fun sameVersionDoesNotMoveTheEnd() {
        assertEquals(emptyList(), decide(local(), remote(endsAt = started.plusSeconds(40 * 60), version = 3)))
    }

    @Test fun firstSyncOfARunStartedHereOnlyTakesTheVersion() {
        val actions = decide(local(lastVersion = 0), remote(endsAt = started.plusSeconds(40 * 60), version = 1))
        assertEquals(listOf<SyncAction>(SyncAction.Seen(1)), actions)
    }

    @Test fun endedElsewhereWhenNothingRuns() {
        assertEquals(listOf<SyncAction>(SyncAction.EndedElsewhere), decide(local(), null))
    }

    @Test fun replacedElsewhereEndsThenAdopts() {
        assertEquals(listOf(SyncAction.EndedElsewhere, SyncAction.Adopt), decide(local(), remote(id = "s2")))
    }

    @Test fun replacedByARecentlyClosedSessionOnlyEnds() {
        assertEquals(listOf<SyncAction>(SyncAction.EndedElsewhere), decide(local(), remote(id = "s2"), closed = setOf("s2")))
    }

    @Test fun skipsWhileOurActionIsInFlight() {
        assertEquals(emptyList(), decide(local(), null, inFlight = true))
        assertEquals(emptyList(), decide(local(), remote(state = "paused"), inFlight = true))
    }

    @Test fun skipsUntilTheStartReachesTheServer() {
        assertEquals(emptyList(), decide(local(sessionId = null), null))
        assertEquals(emptyList(), decide(local(sessionId = null), remote(id = "s2")))
    }

    @Test fun finishedRunIgnoresSync() {
        assertEquals(emptyList(), decide(local(finished = true), null))
        assertEquals(emptyList(), decide(local(finished = true), remote(state = "paused")))
    }

    @Test fun runWithNoSessionIgnoresSync() {
        assertEquals(emptyList(), decide(local(createsSession = false), null))
    }
}
