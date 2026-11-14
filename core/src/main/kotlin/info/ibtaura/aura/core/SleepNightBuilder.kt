package info.ibtaura.aura.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor

// A port of AuraKit/SleepNightBuilder.swift.

/**
 * Last night from the health store's sleep samples, for PUT /v1/sleep/nights/{date}. Several sources (phone, watch)
 * can write the same night, so overlapping samples are merged before anything is counted. On Android the samples
 * come from Health Connect (see `SleepSample` for how its stages map).
 */
object SleepNightBuilder {
    /** A gap this long (3 h, in seconds) between asleep samples starts another sleep (a nap, or the night before). */
    internal const val clusterGap: Double = 3.0 * 60 * 60
    /** The longest night the route takes, bedtime to wake: 16 h. */
    internal const val maxSpan: Double = 16.0 * 60 * 60

    /** A stretch of time, start before end. */
    internal data class Span(val start: Instant, val end: Instant) {
        val seconds: Double get() = secondsBetween(start, end)
    }

    /**
     * Builds last night from the samples, or null when there is no asleep sample (inBed alone is no night).
     * The night is the latest run of asleep samples with no gap of 3 h or more. Awake and in-bed time count only
     * inside it; in-bed time below the sleep counted is left out, as the route refuses it. `date` is the wake's day
     * in `timeZone`.
     */
    fun build(samples: List<SleepSample>, timeZone: ZoneId): SleepNight? {
        val night = latestCluster(merge(spans(samples) { it.isAsleep })) ?: return null
        val window = Span(night.first().start, night.last().end)
        if (window.seconds > maxSpan) return null

        val totalSleepMin = minutes(night.sumOf { it.seconds })
        val awakeMin = minutes(seconds(merge(spans(samples) { it == SleepSample.Kind.awake }), window))
        val inBedMin = minutes(seconds(merge(spans(samples) { it == SleepSample.Kind.inBed }), window))

        return SleepNight(
            date = DayKey.string(window.end, timeZone), timeZone = timeZone.id,
            bedtimeAt = window.start, wakeAt = window.end, totalSleepMin = totalSleepMin,
            awakeMin = if (awakeMin > 0) awakeMin else null,
            inBedMin = if (inBedMin > 0 && inBedMin >= totalSleepMin) inBedMin else null,
        )
    }

    /** Bedtime to wake of the night `build` would pick, or null when it would pick none. */
    internal fun nightWindow(samples: List<SleepSample>): Span? {
        val night = latestCluster(merge(spans(samples) { it.isAsleep })) ?: return null
        val window = Span(night.first().start, night.last().end)
        if (window.seconds > maxSpan) return null
        return window
    }

    internal fun spans(samples: List<SleepSample>, kind: (SleepSample.Kind) -> Boolean): List<Span> =
        samples.filter { kind(it.kind) }.map { Span(it.start, it.end) }

    /** Sorted by start, with overlapping or touching spans joined. Empty or backwards samples are dropped. */
    internal fun merge(spans: List<Span>): List<Span> {
        val sorted = spans.filter { it.end > it.start }.sortedBy { it.start }
        val merged = mutableListOf<Span>()
        for (span in sorted) {
            val previous = merged.lastOrNull()
            if (previous != null && span.start <= previous.end) {
                merged[merged.size - 1] = Span(previous.start, maxOf(previous.end, span.end))
            } else {
                merged.add(span)
            }
        }
        return merged
    }

    /** The last run of merged spans with every gap under `clusterGap`, or null when there are none. */
    internal fun latestCluster(merged: List<Span>): List<Span>? {
        if (merged.isEmpty()) return null
        var cluster = mutableListOf<Span>()
        for (span in merged) {
            val previous = cluster.lastOrNull()
            if (previous != null && secondsBetween(previous.end, span.start) >= clusterGap) {
                cluster = mutableListOf()
            }
            cluster.add(span)
        }
        return cluster
    }

    /** How much of the merged spans falls inside the window, in seconds. */
    internal fun seconds(merged: List<Span>, inside: Span): Double {
        var total = 0.0
        for (span in merged) {
            val start = maxOf(span.start, inside.start)
            val end = minOf(span.end, inside.end)
            if (end > start) total += secondsBetween(start, end)
        }
        return total
    }

    /** Whole minutes, rounded (halves up, as Swift's `rounded()` for positive numbers). */
    internal fun minutes(seconds: Double): Int {
        if (!seconds.isFinite() || seconds <= 0) return 0
        return floor(seconds / 60 + 0.5).toInt()
    }

    private fun secondsBetween(from: Instant, to: Instant): Double = Duration.between(from, to).toNanos() / 1e9
}
