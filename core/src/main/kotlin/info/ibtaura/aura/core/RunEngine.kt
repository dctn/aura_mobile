@file:UseSerializers(InstantSerializer::class)

package info.ibtaura.aura.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// A port of AuraKit/RunEngine.swift.

/** Where a breathing run is. Follows demo_website/src/run/breath.ts. */
data class BreathPhase(
    val word: Word,
    /** Whole seconds left in this phase, 1 at the least. */
    val left: Int,
    /** The circle: `smallest` (empty) to 1 (full). */
    val scale: Double,
) {
    enum class Word(val raw: String) { inhale("In"), hold("Hold"), exhale("Out") }

    private data class Step(val word: Word, val length: Double, val held: Double)

    companion object {
        /** Frame 08: the circle eases between 62% and 100%. */
        const val smallest = 0.62

        fun at(seconds: Double, pattern: BreathPattern?): BreathPhase {
            val p = pattern ?: BreathPattern.coherent
            // word, length, how full a hold stays
            val all = listOf(
                Step(Word.inhale, p.`in`, 0.0), Step(Word.hold, p.hold ?: 0.0, 1.0),
                Step(Word.exhale, p.out, 0.0), Step(Word.hold, p.hold2 ?: 0.0, 0.0),
            )
            val steps = all.filter { it.length > 0 }
            val cycle = steps.sumOf { it.length }
            if (cycle <= 0) return BreathPhase(Word.inhale, 1, smallest)
            var t = seconds % cycle
            if (t < 0) t += cycle
            var index = 0
            while (index < steps.size - 1 && t >= steps[index].length) {
                t -= steps[index].length
                index += 1
            }
            val (word, length, held) = steps[index]
            val x = when (word) {
                Word.inhale -> t / length
                Word.exhale -> 1 - t / length
                Word.hold -> held
            }
            val eased = 0.5 - 0.5 * cos(Math.PI * x)
            val left = max(1, ceil(length - t - 1e-9).toInt())
            return BreathPhase(word, left, smallest + (1 - smallest) * eased)
        }
    }
}

sealed interface RunPhase {
    /** The 3-2-1 before a Focus run. */
    data class Countdown(val left: Int) : RunPhase
    data object Running : RunPhase
    data object Paused : RunPhase
    /** The time ran out (not reached by an open-ended run). */
    data object TimeUp : RunPhase
}

/** What the run screen draws at one moment. Times are in seconds. */
data class RunSnapshot(
    val phase: RunPhase,
    val elapsed: Double,
    /** Null when the run is open-ended. */
    val remaining: Double?,
    /** 0 to 1; null when the run is open-ended. */
    val progress: Double?,
    /** The countdown's ring, 0 to 1, full at the start of the 3-2-1. */
    val countdownProgress: Double,
)

/**
 * A run worked out from the clock, so it stays right while the app is in the background. Times are stored and
 * nothing counts ticks. Serializable so the app can restore a run after it's closed. Immutable: `pause` and `resume`
 * return the changed run (Swift's mutating methods).
 */
@Serializable
data class RunEngine(
    /** The planned length in seconds; 0 is open-ended. */
    val duration: Double,
    val hasCountdown: Boolean,
    /** When the clock starts, after any countdown. */
    val startsAt: Instant,
    val pausedAt: Instant? = null,
    /** Seconds spent paused so far. */
    val pausedTotal: Double = 0.0,
) {
    constructor(minutes: Int, hasCountdown: Boolean, now: Instant) : this(
        duration = max(0, minutes).toDouble() * 60,
        hasCountdown = hasCountdown,
        startsAt = if (hasCountdown) now.plusSeconds(countdownSeconds) else now,
    )

    val isOpenEnded: Boolean get() = duration == 0.0
    val isPaused: Boolean get() = pausedAt != null

    /** Paused at `now`; unchanged when already paused or still counting down. */
    fun pause(at: Instant): RunEngine {
        if (pausedAt != null || at < startsAt) return this
        return copy(pausedAt = at)
    }

    fun resume(at: Instant): RunEngine {
        val paused = pausedAt ?: return this
        return copy(pausedTotal = pausedTotal + max(0.0, seconds(paused, at)), pausedAt = null)
    }

    fun elapsed(at: Instant): Double {
        val end = pausedAt ?: at
        val clamped = max(0.0, seconds(startsAt, end) - pausedTotal)
        return if (isOpenEnded) clamped else min(clamped, duration)
    }

    /** When the time runs out if nothing changes, for the local notification. Null when open-ended or paused. */
    fun endDate(): Instant? {
        if (isOpenEnded || isPaused) return null
        return startsAt.plusSeconds(duration + pausedTotal)
    }

    fun snapshot(at: Instant): RunSnapshot {
        val spent = elapsed(at)
        val remaining: Double? = if (isOpenEnded) null else max(0.0, duration - spent)
        val progress: Double? = if (isOpenEnded) null else min(1.0, spent / duration)
        var ring = 0.0
        val phase: RunPhase = when {
            at < startsAt -> {
                val left = seconds(at, startsAt)
                ring = left / countdownSeconds
                RunPhase.Countdown(ceil(left).toInt())
            }
            isPaused -> RunPhase.Paused
            remaining != null && remaining <= 0 -> RunPhase.TimeUp
            else -> RunPhase.Running
        }
        return RunSnapshot(phase, spent, remaining, progress, ring)
    }

    /** The guided prompt to show: one every (length / count), never past the last. */
    fun promptIndex(at: Instant, count: Int): Int {
        if (count <= 0 || duration <= 0) return 0
        return min(count - 1, (elapsed(at) / (duration / count)).toInt())
    }

    /** Frame 13's question: "End now? 19 minutes left.", or the time so far when open-ended. */
    fun endEarlyTitle(at: Instant): String {
        if (isOpenEnded) return "End now? ${clock(elapsed(at))} so far."
        val left = max(0.0, duration - elapsed(at))
        if (left < 60) return "End now? Less than a minute left."
        val minutes = ceil(left / 60).toInt()
        return "End now? $minutes minute${if (minutes == 1) "" else "s"} left."
    }

    companion object {
        const val countdownSeconds: Double = 3.0

        /** How long a finished run lasted, for the Done sheet: "5 minutes", "1 minute", "Under a minute". */
        fun lengthText(seconds: Double): String {
            val minutes = roundHalfAwayFromZero(seconds / 60).toInt()
            if (minutes < 1) return "Under a minute"
            return "$minutes minute${if (minutes == 1) "" else "s"}"
        }

        /** "09:54" (or "1:02:03" past an hour). */
        fun clock(seconds: Double): String {
            val total = ceil(seconds).toInt()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.ROOT, "%02d:%02d", m, s)
        }

        private fun seconds(from: Instant, to: Instant): Double = Duration.between(from, to).toNanos() / 1e9

        private fun Instant.plusSeconds(seconds: Double): Instant = plusNanos((seconds * 1e9).roundToLong())

        private fun roundHalfAwayFromZero(value: Double): Double =
            if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
    }
}
