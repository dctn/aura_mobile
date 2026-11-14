package info.ibtaura.aura.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hands out scripted values in turn, round and round, so a test knows every gap and digit. */
private class Scripted<T>(private val values: List<T>) {
    private var index = 0

    fun next(): T {
        val value = values[index % values.size]
        index += 1
        return value
    }
}

/** A reaction test whose gaps come from `randoms` (gap = 1 + r * 3 with the standard config). */
private fun reaction(randoms: List<Double>, config: ReactionTest.Config = ReactionTest.Config.standard): ReactionTest {
    val script = Scripted(randoms)
    return ReactionTest(config) { script.next() }
}

/** A span test whose digits come from `digits`. */
private fun span(digits: List<Int>, config: SpanTest.Config = SpanTest.Config.standard): SpanTest {
    val script = Scripted(digits)
    return SpanTest(config) { script.next() }
}

/** Types a whole row; returns what the last digit returned. */
private fun SpanTest.enter(row: List<Int>): Boolean? {
    var last: Boolean? = null
    for (digit in row) last = type(digit)
    return last
}

private val digits10 = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)

/** A port of QuickCheckEngineTests.swift. */
class QuickCheckEngineTest {
    private val waiting = ReactionTest.Phase.Waiting
    private val dot = ReactionTest.Phase.Dot

    // MARK: Reaction test

    @Test
    fun standardConfig() {
        val config = ReactionTest.Config.standard
        assertEquals(180.0, config.duration)
        assertEquals(1.0, config.minGap)
        assertEquals(4.0, config.maxGap)
        assertEquals(1.0, config.showResultFor)
        assertEquals(10.0, config.timeout)
        assertEquals(8.0, ReactionTest.Config.short(8.0).duration)
        assertEquals(4.0, ReactionTest.Config.short(8.0).maxGap)
    }

    @Test
    fun firstDotComesAfterTheGap() {
        val test = reaction(listOf(0.5))
        assertEquals(ReactionTest.Phase.NotStarted, test.phase)
        test.start(0.0)
        assertEquals(waiting, test.phase)
        test.tick(1.0)
        assertEquals(waiting, test.phase)
        test.tick(2.49)
        assertEquals(waiting, test.phase)
        test.tick(2.5)
        assertEquals(dot, test.phase)

        val soonest = reaction(listOf(0.0))
        soonest.start(0.0)
        soonest.tick(0.99)
        assertEquals(waiting, soonest.phase)
        soonest.tick(1.0)
        assertEquals(dot, soonest.phase)

        val latest = reaction(listOf(0.999))
        latest.start(0.0)
        latest.tick(3.99)
        assertEquals(waiting, latest.phase)
        latest.tick(4.0)
        assertEquals(dot, latest.phase)
    }

    @Test
    fun tapOnTheDotRecordsTheReaction() {
        val test = reaction(listOf(0.5))
        test.start(0.0)
        test.tick(2.5)
        val outcome = test.tap(2.75)
        assertEquals(ReactionTest.TapOutcome.Reaction(250.0), outcome)
        assertEquals(ReactionTest.Phase.Result(250.0), test.phase)
        assertEquals(listOf(250.0), test.reactionTimesMs)
        assertEquals(0, test.falseStarts)
        // The time shows for a second, then the card is dark until the next dot (2.75 + 2.5).
        test.tick(3.5)
        assertEquals(ReactionTest.Phase.Result(250.0), test.phase)
        test.tick(3.75)
        assertEquals(waiting, test.phase)
        test.tick(5.0)
        assertEquals(waiting, test.phase)
        test.tick(5.25)
        assertEquals(dot, test.phase)
    }

    @Test
    fun tapWithNoDotIsAFalseStart() {
        val test = reaction(listOf(0.5, 0.0, 0.0))
        test.start(0.0)
        val early = test.tap(1.0)
        assertEquals(ReactionTest.TapOutcome.FalseStart, early)
        assertEquals(1, test.falseStarts)
        assertEquals(ReactionTest.Phase.TooSoon, test.phase)
        assertTrue(test.reactionTimesMs.isEmpty())
        // The gap is drawn again from the tap (r = 0, so 1 s): the dot that was due at 2.5 is now due at 2.
        test.tick(1.5)
        assertEquals(ReactionTest.Phase.TooSoon, test.phase)
        test.tick(2.0)
        assertEquals(dot, test.phase)
        assertEquals(ReactionTest.TapOutcome.Reaction(250.0), test.tap(2.25))
        // A tap while the time shows is a false start too.
        assertEquals(ReactionTest.TapOutcome.FalseStart, test.tap(2.5))
        assertEquals(2, test.falseStarts)
        assertEquals(ReactionTest.Phase.TooSoon, test.phase)
        assertEquals(listOf(250.0), test.reactionTimesMs)
    }

    @Test
    fun unansweredDotRecordsTheTimeout() {
        val test = reaction(listOf(0.0))
        test.start(0.0)
        test.tick(1.0)
        assertEquals(dot, test.phase)
        test.tick(10.99)
        assertEquals(dot, test.phase)
        test.tick(11.0)
        assertEquals(listOf(10_000.0), test.reactionTimesMs)
        assertEquals(waiting, test.phase)
        assertFalse(test.isFinished)
        // The run carries on: the next gap counts from the timeout.
        test.tick(12.0)
        assertEquals(dot, test.phase)
        assertEquals(ReactionTest.TapOutcome.Reaction(250.0), test.tap(12.25))
        assertEquals(listOf(10_000.0, 250.0), test.reactionTimesMs)
        assertEquals(0, test.falseStarts)
    }

    @Test
    fun aTapAfterAMissedTimeoutRecordsTheTimeoutAndAFalseStart() {
        val test = reaction(listOf(0.0))
        test.start(0.0)
        test.tick(1.0)
        // No tick came for 10 s: the dot ran out before the tap.
        assertEquals(ReactionTest.TapOutcome.FalseStart, test.tap(12.0))
        assertEquals(listOf(10_000.0), test.reactionTimesMs)
        assertEquals(1, test.falseStarts)
    }

    @Test
    fun runEndsAtTheDuration() {
        val test = reaction(listOf(0.0), config = ReactionTest.Config.short(10.0))
        test.start(0.0)
        assertEquals(6.0, test.remaining(4.0))
        test.tick(4.5)
        assertEquals(4, test.durationSeconds)
        test.tick(9.5)
        assertEquals(dot, test.phase)
        test.tick(10.0)
        assertEquals(ReactionTest.Phase.Finished, test.phase)
        assertTrue(test.isFinished)
        // The dot showing at the end is dropped.
        assertTrue(test.reactionTimesMs.isEmpty())
        assertEquals(ReactionTest.TapOutcome.Ignored, test.tap(10.5))
        assertEquals(0, test.falseStarts)
        test.tick(30.0)
        assertEquals(ReactionTest.Phase.Finished, test.phase)
        assertEquals(0.0, test.remaining(12.0))
        assertEquals(0.0, test.remaining(9.0))
        assertEquals(10, test.durationSeconds)

        // A tap at the end, before any tick saw it, ends the run and is ignored.
        val late = reaction(listOf(0.0), config = ReactionTest.Config.short(10.0))
        late.start(0.0)
        late.tick(9.5)
        assertEquals(ReactionTest.TapOutcome.Ignored, late.tap(10.0))
        assertTrue(late.isFinished)
        assertTrue(late.reactionTimesMs.isEmpty())
        assertEquals(10, late.durationSeconds)
    }

    @Test
    fun remainingNeverGoesBelowZero() {
        val test = reaction(listOf(0.5))
        assertEquals(180.0, test.remaining(0.0))
        test.start(100.0)
        assertEquals(120.0, test.remaining(160.0))
        assertEquals(0.0, test.remaining(1_000.0))
        assertEquals(ReactionTest.TapOutcome.Ignored, test.tap(1_000.0))
        assertEquals(0.0, test.remaining(1_000.0))
        assertEquals(180, test.durationSeconds)
    }

    @Test
    fun startTwiceAndTicksBeforeTheStartDoNothing() {
        val test = reaction(listOf(0.0))
        test.tick(5.0)
        assertEquals(ReactionTest.TapOutcome.Ignored, test.tap(5.0))
        assertEquals(ReactionTest.Phase.NotStarted, test.phase)
        assertEquals(0, test.durationSeconds)
        test.start(0.0)
        test.start(50.0)
        test.tick(1.0)
        assertEquals(dot, test.phase)
    }

    @Test
    fun recentHoldsTheNewestThree() {
        val test = reaction(listOf(0.0))
        test.start(0.0)
        assertEquals(emptyList(), test.recent)
        test.tick(1.0)
        test.tap(1.25)
        assertEquals(listOf(250.0), test.recent)
        // Each gap is 1 s, the same as the time showing, so the next dot is due as the time goes.
        test.tick(2.25)
        assertEquals(dot, test.phase)
        test.tap(2.375)
        test.tick(3.375)
        test.tap(3.75)
        test.tick(4.75)
        test.tap(5.25)
        assertEquals(listOf(250.0, 125.0, 375.0, 500.0), test.reactionTimesMs)
        assertEquals(listOf(500.0, 375.0, 125.0), test.recent)
    }

    @Test
    fun aFullRunHasAPlausibleNumberOfDots() {
        val test = reaction(listOf(0.1, 0.5, 0.9, 0.3, 0.7))
        test.start(0.0)
        var dotSeenAt: Double? = null
        // 100 ticks a second; each dot is tapped about 300 ms after it shows.
        for (i in 1..18_100) {
            val t = i / 100.0
            test.tick(t)
            if (test.phase == dot && dotSeenAt == null) dotSeenAt = t
            val seen = dotSeenAt
            if (seen != null && t >= seen + 0.3) {
                test.tap(t)
                dotSeenAt = null
            }
        }
        assertTrue(test.isFinished)
        assertEquals(180, test.durationSeconds)
        assertEquals(0, test.falseStarts)
        val count = test.reactionTimesMs.size
        assertTrue(count in 36..90, "$count answers")
        assertTrue(test.reactionTimesMs.all { it in 290.0..320.0 })
    }

    // MARK: Span test

    @Test
    fun spanConfigs() {
        assertEquals(SpanTest.Config(startLength = 3, maxLength = 9, showPerDigit = 1.0), SpanTest.Config.standard)
        assertEquals(SpanTest.Config(startLength = 3, maxLength = 3, showPerDigit = 1.0), SpanTest.Config.short)
    }

    @Test
    fun beginShowsThreeDigitsWithNoEqualNeighbours() {
        val test = span(listOf(4, 4, 7, 7, 2))
        test.begin()
        assertEquals(SpanTest.Phase.showing, test.phase)
        assertEquals(listOf(4, 7, 2), test.digits)
        assertEquals(3.0, test.showDuration)
        // Typing while the row shows is ignored.
        assertNull(test.type(4))
        assertEquals(emptyList(), test.entered)
        test.hideDigits()
        assertEquals(SpanTest.Phase.entering, test.phase)

        // A source stuck on one digit still makes a row.
        val stuck = span(listOf(5))
        stuck.begin()
        assertEquals(3, stuck.digits.size)
        assertNotEquals(stuck.digits[0], stuck.digits[1])
        assertNotEquals(stuck.digits[1], stuck.digits[2])
    }

    @Test
    fun rightAnswerGrowsTheRow() {
        val test = span(digits10)
        test.begin()
        assertEquals(listOf(1, 2, 3), test.digits)
        test.hideDigits()
        assertNull(test.type(1))
        assertNull(test.type(2))
        assertEquals(true, test.type(3))
        assertEquals(SpanTest.Phase.showing, test.phase)
        assertEquals(listOf(4, 5, 6, 7), test.digits)
        assertEquals(emptyList(), test.entered)
        assertEquals(1, test.spanCorrect)
        assertEquals(1, test.spanAttempts)
        assertEquals(3, test.bestSpan)
    }

    @Test
    fun wrongAnswerRetriesThenTwoWrongsFinish() {
        val test = span(digits10)
        test.begin()
        test.hideDigits()
        assertEquals(false, test.enter(listOf(1, 2, 9)))
        // One more try at the same length, with new digits.
        assertEquals(SpanTest.Phase.showing, test.phase)
        assertEquals(listOf(4, 5, 6), test.digits)
        assertEquals(1, test.spanAttempts)
        assertEquals(0, test.spanCorrect)
        assertEquals(0, test.bestSpan)
        test.hideDigits()
        assertEquals(true, test.enter(listOf(4, 5, 6)))
        assertEquals(listOf(7, 8, 9, 0), test.digits)
        // The wrong at length 3 doesn't count against length 4.
        test.hideDigits()
        assertEquals(false, test.enter(listOf(0, 0, 0, 0)))
        assertEquals(SpanTest.Phase.showing, test.phase)
        assertEquals(listOf(1, 2, 3, 4), test.digits)
        test.hideDigits()
        assertEquals(false, test.enter(listOf(4, 3, 2, 1)))
        assertEquals(SpanTest.Phase.finished, test.phase)
        assertTrue(test.isFinished)
        assertEquals(1, test.spanCorrect)
        assertEquals(4, test.spanAttempts)
        assertEquals(3, test.bestSpan)
        assertEquals(SpanResult(spanCorrect = 1, spanAttempts = 4), test.result)
        assertNull(test.type(1))
        assertEquals(4, test.spanAttempts)
    }

    @Test
    fun deleteLast() {
        val test = span(digits10)
        test.begin()
        test.hideDigits()
        test.deleteLast()
        assertEquals(emptyList(), test.entered)
        test.type(1)
        test.type(9)
        test.deleteLast()
        assertEquals(listOf(1), test.entered)
        test.type(2)
        assertEquals(true, test.type(3))
        assertEquals(1, test.spanCorrect)
    }

    @Test
    fun aNumberOutsideZeroToNineIsIgnored() {
        val test = span(digits10)
        test.begin()
        test.hideDigits()
        assertNull(test.type(10))
        assertNull(test.type(-1))
        assertEquals(emptyList(), test.entered)
    }

    @Test
    fun rightAnswerAtMaxLengthFinishes() {
        val four = SpanTest.Config(startLength = 3, maxLength = 4, showPerDigit = 1.0)
        val test = span(digits10, config = four)
        test.begin()
        test.hideDigits()
        assertEquals(true, test.enter(listOf(1, 2, 3)))
        test.hideDigits()
        assertEquals(true, test.enter(listOf(4, 5, 6, 7)))
        assertEquals(SpanTest.Phase.finished, test.phase)
        assertEquals(4, test.bestSpan)
        assertEquals(SpanResult(spanCorrect = 2, spanAttempts = 2), test.result)
    }

    @Test
    fun shortIsASingleRound() {
        val right = span(digits10, config = SpanTest.Config.short)
        right.begin()
        right.hideDigits()
        assertEquals(true, right.enter(listOf(1, 2, 3)))
        assertTrue(right.isFinished)
        assertEquals(SpanResult(spanCorrect = 1, spanAttempts = 1), right.result)
        assertEquals(3, right.bestSpan)

        val wrong = span(digits10, config = SpanTest.Config.short)
        wrong.begin()
        wrong.hideDigits()
        assertEquals(false, wrong.enter(listOf(0, 0, 0)))
        assertFalse(wrong.isFinished)
        wrong.hideDigits()
        assertEquals(false, wrong.enter(listOf(0, 0, 0)))
        assertTrue(wrong.isFinished)
        assertEquals(SpanResult(spanCorrect = 0, spanAttempts = 2), wrong.result)
        assertEquals(0, wrong.bestSpan)
    }
}
