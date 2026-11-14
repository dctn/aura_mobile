package info.ibtaura.aura.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// A port of AuraKit/QuickCheckEngine.swift. Both tests are mutable state machines the screen keeps one of.

/** How long the reaction test runs, in seconds. The one place to change it. (iOS and Basner's PVT-B use 180 s.) */
const val REACTION_TEST_SECONDS: Double = 45.0

/** How long the memory test runs at the most, in seconds, from its first row. The one place to change it. */
const val MEMORY_TEST_SECONDS: Double = 45.0

/**
 * The reaction test (after PVT-B, Basner 2011, shortened to `REACTION_TEST_SECONDS`) as a state machine.
 * It reads no clock: the screen passes in the time in seconds (e.g. from `SystemClock.uptimeMillis() / 1000.0`), so
 * tests can script every moment. The backend scores the raw times; this only runs the test.
 *
 * `random` gives a number in 0..<1 for each gap; tests pass a script.
 */
class ReactionTest(
    val config: Config = Config.standard,
    private val random: () -> Double = { Random.nextDouble() },
) {
    data class Config(
        /** The whole run, `REACTION_TEST_SECONDS`. */
        val duration: Double,
        /** From one answer (or the start) to the next dot, at the least: 1 s. */
        val minGap: Double,
        /** And at the most: 4 s. */
        val maxGap: Double,
        /** How long the reaction time (or "Too soon") shows, inside the gap: 1 s. */
        val showResultFor: Double,
        /** A dot not tapped within this records timeout*1000 ms: 10 s. */
        val timeout: Double,
    ) {
        companion object {
            val standard = Config(
                duration = REACTION_TEST_SECONDS,
                minGap = 1.0,
                maxGap = 4.0,
                showResultFor = 1.0,
                timeout = 10.0,
            )

            /** The same rules over `seconds` (UI tests). */
            fun short(seconds: Double): Config = standard.copy(duration = seconds)
        }
    }

    sealed interface Phase {
        data object NotStarted : Phase
        /** Dark card, no dot yet. */
        data object Waiting : Phase
        /** Green "Tap!". */
        data object Dot : Phase
        /** The reaction just made, shown for showResultFor (card dark again). */
        data class Result(val ms: Double) : Phase
        /** A tap with no dot, shown for showResultFor. */
        data object TooSoon : Phase
        data object Finished : Phase
    }

    sealed interface TapOutcome {
        data class Reaction(val ms: Double) : TapOutcome
        data object FalseStart : TapOutcome
        data object Ignored : TapOutcome
    }

    var phase: Phase = Phase.NotStarted
        private set

    private val times = mutableListOf<Double>()

    /** One per dot, in order; an unanswered dot holds timeout*1000. */
    val reactionTimesMs: List<Double> get() = times.toList()

    /** Taps while no dot showed. */
    var falseStarts: Int = 0
        private set

    private var startedAt = 0.0
    /** The newest time the screen passed in. */
    private var latest = 0.0
    private var nextDotAt = 0.0
    private var dotShownAt = 0.0
    /** When `Result` / `TooSoon` ends. */
    private var messageUntil = 0.0

    /** The newest reactions first, at most 3 (the row under the card). Timeouts are in it too. */
    val recent: List<Double> get() = times.takeLast(3).reversed()

    val isFinished: Boolean get() = phase == Phase.Finished

    /**
     * Whole seconds from start to the end of the run (the configured duration once finished).
     * While running it is the time up to the newest tick or tap.
     */
    val durationSeconds: Int
        get() = when (phase) {
            Phase.NotStarted -> 0
            Phase.Finished -> roundHalfAway(max(0.0, config.duration)).toInt()
            else -> max(0.0, latest - startedAt).toInt()
        }

    /** Starts the run: the first dot comes after one gap. Does nothing once started; make a new test to run again. */
    fun start(at: Double) {
        if (phase != Phase.NotStarted) return
        startedAt = at
        latest = at
        nextDotAt = at + drawGap()
        phase = if (config.duration > 0) Phase.Waiting else Phase.Finished
    }

    /** Moves time on: shows the dot when its gap is over, records a timeout, clears result/tooSoon, ends the run. */
    fun tick(at: Double) {
        if (!isRunning) return
        latest = max(latest, at)
        if (at >= endsAt) {
            // A dot still showing is dropped, not recorded.
            phase = Phase.Finished
            return
        }
        // A late tick can pass several moments at once; they are handled in order.
        var changed = true
        var steps = 0
        while (changed && steps < 10_000) {
            changed = false
            steps += 1
            when (phase) {
                Phase.NotStarted, Phase.Finished -> return
                Phase.Waiting -> if (at >= nextDotAt) {
                    // The dot counts from the tick that put it on screen.
                    dotShownAt = at
                    phase = Phase.Dot
                    changed = true
                }
                Phase.Dot -> {
                    val timesOutAt = dotShownAt + config.timeout
                    if (at >= timesOutAt) {
                        times.add(config.timeout * 1000)
                        nextDotAt = timesOutAt + drawGap()
                        phase = Phase.Waiting
                        changed = true
                    }
                }
                is Phase.Result, Phase.TooSoon -> if (at >= messageUntil) {
                    // Back to waiting; the loop shows the dot at once if it is due.
                    phase = Phase.Waiting
                    changed = true
                }
            }
        }
    }

    /**
     * A tap on the card. On the dot it records the reaction; with no dot on screen it is a false start
     * (a dot that is due but not yet shown by a tick has not been seen, so it is a false start too).
     */
    fun tap(at: Double): TapOutcome {
        if (!isRunning) return TapOutcome.Ignored
        latest = max(latest, at)
        if (at >= endsAt) {
            phase = Phase.Finished
            return TapOutcome.Ignored
        }
        if (phase == Phase.Dot) {
            if (at - dotShownAt < config.timeout) {
                val ms = max(0.0, at - dotShownAt) * 1000
                times.add(ms)
                phase = Phase.Result(ms)
                messageUntil = at + config.showResultFor
                nextDotAt = at + drawGap()
                return TapOutcome.Reaction(ms)
            }
            // The dot ran out before this tap (no tick came in time): it counts as unanswered.
            times.add(config.timeout * 1000)
        }
        falseStarts += 1
        phase = Phase.TooSoon
        messageUntil = at + config.showResultFor
        nextDotAt = at + drawGap()
        return TapOutcome.FalseStart
    }

    /** Time left in the run, never below 0. The full duration before the start. */
    fun remaining(at: Double): Double = when (phase) {
        Phase.NotStarted -> max(0.0, config.duration)
        Phase.Finished -> 0.0
        else -> max(0.0, endsAt - at)
    }

    private val isRunning: Boolean get() = phase != Phase.NotStarted && phase != Phase.Finished

    private val endsAt: Double get() = startedAt + config.duration

    /** minGap + random()*(maxGap-minGap), with the random number held to 0...1. */
    private fun drawGap(): Double {
        val raw = random()
        val r = if (raw.isFinite()) min(max(raw, 0.0), 1.0) else 0.0
        val low = max(0.0, config.minGap)
        val high = max(low, config.maxGap)
        return low + r * (high - low)
    }

    private companion object {
        fun roundHalfAway(value: Double): Double = if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
    }
}

/**
 * The memory part: digits show, then are typed back; the row grows by one after each right answer. It ends at the
 * longest row, after two misses at one length, or when its time (`config.duration`, from the first row) is up.
 * Like `ReactionTest` it reads no clock: the screen passes the time in seconds to `begin`, `tick` and `remaining`.
 *
 * `digit` gives a digit 0-9 for each place in a row; tests pass a script.
 */
class SpanTest(
    val config: Config = Config.standard,
    private val digit: () -> Int = { Random.nextInt(0, 10) },
) {
    data class Config(
        /** The first row's length: 3. */
        val startLength: Int,
        /** A right answer at this length ends the test: 9. */
        val maxLength: Int,
        /** The screen shows the row for length * showPerDigit seconds: 1. */
        val showPerDigit: Double,
        /** The most the test runs, from the first row: `MEMORY_TEST_SECONDS`. A row not answered by then is dropped. */
        val duration: Double = MEMORY_TEST_SECONDS,
    ) {
        companion object {
            val standard = Config(startLength = 3, maxLength = 9, showPerDigit = 1.0)
            /** One round of 3 digits (UI tests), with the same time. */
            val short = Config(startLength = 3, maxLength = 3, showPerDigit = 1.0)
        }
    }

    enum class Phase { showing, entering, finished }

    /** `showing` with no digits until `begin()`. */
    var phase: Phase = Phase.showing
        private set

    /** The row of the current round. */
    var digits: List<Int> = emptyList()
        private set

    private val typed = mutableListOf<Int>()
    val entered: List<Int> get() = typed.toList()

    /** Rounds answered right. */
    var spanCorrect: Int = 0
        private set
    /** Rounds answered. */
    var spanAttempts: Int = 0
        private set
    /** Longest row answered right, 0 if none. */
    var bestSpan: Int = 0
        private set

    private var length: Int = max(1, config.startLength)
    private var wrongAtLength = 0
    private var begun = false
    private var startedAt = 0.0

    /** How long the screen shows the current row, in seconds. */
    val showDuration: Double get() = digits.size * config.showPerDigit

    val isFinished: Boolean get() = phase == Phase.finished

    val result: SpanResult get() = SpanResult(spanCorrect = spanCorrect, spanAttempts = spanAttempts)

    /**
     * Starts the first round (phase showing with new digits) and the clock, at `at` seconds.
     * Called once; later calls do nothing.
     */
    fun begin(at: Double = 0.0) {
        if (begun) return
        begun = true
        startedAt = at
        if (config.duration <= 0) {
            phase = Phase.finished
            return
        }
        newRound()
    }

    /**
     * Moves time on: once the time is up the test finishes. A row still showing or half typed is dropped, not
     * counted; rows already answered stay counted.
     */
    fun tick(at: Double) {
        if (!begun || phase == Phase.finished) return
        if (at >= startedAt + config.duration) {
            phase = Phase.finished
            typed.clear()
        }
    }

    /** Time left, never below 0. The full time before `begin`, 0 once finished. */
    fun remaining(at: Double): Double = when {
        !begun -> max(0.0, config.duration)
        phase == Phase.finished -> 0.0
        else -> max(0.0, startedAt + config.duration - at)
    }

    /** The row has been shown long enough: phase entering. */
    fun hideDigits() {
        if (phase != Phase.showing || digits.isEmpty()) return
        phase = Phase.entering
    }

    /**
     * A keypad digit. When the row is full it is checked: returns true/false then, null before.
     * Ignored (null) outside `entering` or for a number outside 0-9.
     */
    fun type(digit: Int): Boolean? {
        if (phase != Phase.entering || digit !in 0..9 || typed.size >= digits.size) return null
        typed.add(digit)
        if (typed.size != digits.size) return null
        spanAttempts += 1
        val right = typed == digits
        if (right) {
            spanCorrect += 1
            bestSpan = max(bestSpan, digits.size)
            if (digits.size >= longest) {
                phase = Phase.finished
            } else {
                length += 1
                wrongAtLength = 0
                newRound()
            }
        } else {
            wrongAtLength += 1
            if (wrongAtLength >= 2) phase = Phase.finished else newRound()
        }
        return right
    }

    fun deleteLast() {
        if (phase != Phase.entering || typed.isEmpty()) return
        typed.removeAt(typed.size - 1)
    }

    /** The longest row the test asks for, never shorter than the first. */
    private val longest: Int get() = max(max(1, config.startLength), config.maxLength)

    private fun newRound() {
        digits = drawRow(length)
        typed.clear()
        phase = Phase.showing
    }

    /** A row with no digit next to the same digit (a repeat is re-drawn). */
    private fun drawRow(count: Int): List<Int> {
        val row = ArrayList<Int>(count)
        while (row.size < count) {
            var next = oneDigit(digit())
            var tries = 0
            while (row.isNotEmpty() && next == row.last() && tries < 20) {
                next = oneDigit(digit())
                tries += 1
            }
            // A source that keeps giving the same digit can't stall the row.
            if (row.isNotEmpty() && next == row.last()) next = (row.last() + 1) % 10
            row.add(next)
        }
        return row
    }

    private companion object {
        fun oneDigit(value: Int): Int = ((value % 10) + 10) % 10
    }
}
