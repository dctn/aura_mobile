package info.ibtaura.auraclarus.shell

import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.Checkin
import info.ibtaura.aura.core.CheckinKind
import info.ibtaura.aura.core.RemoteSession
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyncCadenceTest {
    private val session = RemoteSession(
        id = "s1", kind = "focus", mode = "timer", state = "running", device = "mac",
        startedAt = Instant.parse("2026-11-15T09:00:00Z"),
    )

    @Test
    fun checksEvery15SecondsWithARunAndEveryMinuteWithout() {
        assertEquals(15_000L, SyncCadence.interval(hasRun = true))
        assertEquals(60_000L, SyncCadence.interval(hasRun = false))
    }

    @Test
    fun reloadsAfterMoreThan30SecondsAwayAnd5MinutesInFront() {
        assertFalse(SyncCadence.refreshOnReturn(30_000L))
        assertTrue(SyncCadence.refreshOnReturn(30_001L))
        assertFalse(SyncCadence.refreshDue(299_999L))
        assertTrue(SyncCadence.refreshDue(300_000L))
    }

    @Test
    fun aFailedReadNeverHandsOverNoSession() = runBlocking {
        val applied = mutableListOf<RemoteSession?>()
        val error = SyncCadence.pullSession(read = { throw AuraError.Offline }, stamp = { false }, apply = { applied += it })
        assertSame(AuraError.Offline, error)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun aSuccessfulReadHandsOverTheSessionOrNone() = runBlocking {
        val applied = mutableListOf<RemoteSession?>()
        assertNull(SyncCadence.pullSession(read = { session }, stamp = { false }, apply = { applied += it }))
        assertNull(SyncCadence.pullSession(read = { null }, stamp = { true }, apply = { applied += it }))
        assertEquals(listOf(session, null), applied)
    }

    @Test
    fun anAnswerIsDroppedWhenTheRunHereChangedDuringTheRead() = runBlocking {
        val applied = mutableListOf<RemoteSession?>()
        var hasRun = false
        // Start tapped here while the read was on its way: its "no session" answer is already out of date.
        SyncCadence.pullSession(read = { hasRun = true; null }, stamp = { hasRun }, apply = { applied += it })
        assertTrue(applied.isEmpty())
    }

    @Test
    fun onlyTodaysMorningCheckinCounts() {
        val today = "2026-11-15"
        val mood = Checkin("c1", CheckinKind.mood, null, today, mood = 4)
        val yesterday = Checkin("c2", CheckinKind.morning, null, "2026-11-14", alertness = 3)
        val morning = Checkin("c3", CheckinKind.morning, null, today, alertness = 4)
        assertFalse(SyncCadence.morningCheckinDone(emptyList(), today))
        assertFalse(SyncCadence.morningCheckinDone(listOf(mood, yesterday), today))
        assertTrue(SyncCadence.morningCheckinDone(listOf(mood, morning), today))
    }
}
