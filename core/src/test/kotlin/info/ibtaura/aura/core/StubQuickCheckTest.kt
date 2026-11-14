package info.ibtaura.aura.core

import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A run of `answers` reactions of `ms` each. */
private fun makeRun(
    id: String = UUID.randomUUID().toString().uppercase(Locale.ROOT),
    takenAt: Instant = stubNoon,
    seconds: Int = 180,
    answers: Int = 60,
    ms: Double = 300.0,
    falseStarts: Int = 0,
    memory: SpanResult? = null,
): QuickCheckRun = QuickCheckRun(
    id = id, takenAt = takenAt, durationSeconds = seconds, reactionTimesMs = List(answers) { ms },
    falseStarts = falseStarts, memory = memory,
)

/** A port of QuickCheckStubTests (QuickCheckAPITests.swift). */
class StubQuickCheckTest {
    // MARK: Scoring

    @Test
    fun aThreeMinuteRunWithSixtyAnswersIsValid() = runTest {
        val result = stubApi().submitCheck(makeRun(seconds = 180, answers = 60, ms = 300.0))
        assertTrue(result.valid)
        assertNull(result.invalidReason)
        assertEquals(300.0, result.meanRtMs)
        assertEquals(300.0, result.medianRtMs)
        assertEquals(0, result.lapses)
        assertEquals(0, result.falseStarts)
        assertEquals(1.0, result.performance)
        assertTrue(result.qualifiesForNormal)
    }

    @Test
    fun aOneMinuteRunIsNotValid() = runTest {
        val stub = stubApi()
        val result = stub.submitCheck(makeRun(seconds = 60, answers = 20))
        assertFalse(result.valid)
        assertEquals("not_3_minutes", result.invalidReason)
        assertFalse(result.qualifiesForNormal)

        val tooLong = stub.submitCheck(makeRun(seconds = 201, answers = 60))
        assertEquals("not_3_minutes", tooLong.invalidReason, "The length is checked before the answers")
    }

    @Test
    fun tooFewAnswersIsNotValid() = runTest {
        val result = stubApi().submitCheck(makeRun(seconds = 180, answers = 29))
        assertFalse(result.valid)
        assertEquals("too_few_responses", result.invalidReason)
    }

    @Test
    fun tooManyFalseStartsIsNotValid() = runTest {
        val stub = stubApi()
        // 20 taps with no dot against 60 answers: more than 20% of 80.
        val tooMany = stub.submitCheck(makeRun(seconds = 180, answers = 60, falseStarts = 20))
        assertFalse(tooMany.valid)
        assertNotNull(tooMany.invalidReason)
        assertEquals("too_many_false_starts", tooMany.invalidReason)
        assertEquals(20, tooMany.falseStarts)

        // 12 of 72 is under 20%.
        val fine = stub.submitCheck(makeRun(seconds = 180, answers = 60, falseStarts = 12))
        assertTrue(fine.valid)
    }

    /** As the server: reactions under 100 ms are false starts, 355 ms or slower are lapses. */
    @Test
    fun measuresFollowTheServer() {
        val run = QuickCheckRun(takenAt = stubNoon, durationSeconds = 180, reactionTimesMs = listOf(250.0, 400.0, 90.0, 300.0), falseStarts = 1)
        val measured = StubCheck.measure(run, shortChecksCount = false)
        assertEquals(2, measured.falseStarts)
        assertEquals(1, measured.lapses)
        assertEquals(316.7, measured.meanRtMs)
        assertEquals(300.0, measured.medianRtMs)
        assertEquals(0.4, measured.performance)
        assertEquals("too_few_responses", measured.invalidReason)

        val none = StubCheck.measure(
            QuickCheckRun(takenAt = stubNoon, durationSeconds = 180, reactionTimesMs = listOf(50.0), falseStarts = 0),
            shortChecksCount = false,
        )
        assertEquals(0.0, none.meanRtMs)
        assertEquals(0.0, none.performance)
        assertEquals(1, none.falseStarts)
    }

    @Test
    fun shortChecksCountMakesAFiveSecondRunValid() = runTest {
        val short = makeRun(seconds = 5, answers = 2, ms = 320.0)
        assertFalse(stubApi().submitCheck(short).valid)

        val result = stubApi(shortChecksCount = true).submitCheck(short)
        assertTrue(result.valid)
        assertNull(result.invalidReason)
        assertEquals(320.0, result.meanRtMs)
    }

    // MARK: Gate and plan

    @Test
    fun aValidCheckClearsTheQuickCheckGate() = runTest {
        val stub = stubApi(needsQuickCheck = true)
        val today = stubTodayKey
        val before = caught { stub.allocate(date = today, dryRun = true, skipCheck = false, taskIds = null) }
        assertEquals("quick_check_required", (before as? AuraError)?.serverCode)

        // A check that doesn't count leaves the gate shut.
        stub.submitCheck(makeRun(seconds = 60))
        val stillShut = caught { stub.allocate(date = today, dryRun = true, skipCheck = false, taskIds = null) }
        assertEquals("quick_check_required", (stillShut as? AuraError)?.serverCode)

        val takenAt = stubNoon
        assertTrue(stub.submitCheck(makeRun(takenAt = takenAt)).valid)
        val plan = stub.allocate(date = today, dryRun = true, skipCheck = false, taskIds = null)
        assertEquals(takenAt, plan.basis.quickCheckAt)
        assertFalse(plan.placed.isEmpty())

        // A later valid check doesn't replace the day's first.
        stub.submitCheck(makeRun(takenAt = takenAt.plusSeconds(1)))
        val again = stub.allocate(date = today, dryRun = true, skipCheck = false, taskIds = null)
        assertEquals(takenAt, again.basis.quickCheckAt)
    }

    @Test
    fun validChecksRaiseAttentionsCount() = runTest {
        val stub = stubApi()
        assertEquals(0, stub.scoresToday().attention.checksCounted)

        val result = stub.submitCheck(makeRun())
        assertEquals(1, result.scores.attention.checksCounted)
        assertEquals(75.0, result.scores.attention.value)
        stub.submitCheck(makeRun(seconds = 60))
        assertEquals(1, stub.scoresToday().attention.checksCounted, "A check that isn't valid doesn't count")
    }

    @Test
    fun resendingTheSameIdReturnsTheSameResult() = runTest {
        val stub = stubApi()
        val run = makeRun(id = "check-same")
        val first = stub.submitCheck(run)
        val second = stub.submitCheck(run)
        assertEquals(first, second)
        assertEquals("check-same", second.id)
        assertEquals(1, stub.scoresToday().attention.checksCounted, "Stored once")
    }
}
