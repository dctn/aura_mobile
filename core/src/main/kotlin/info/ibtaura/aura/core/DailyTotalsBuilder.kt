package info.ibtaura.aura.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Android only, no iOS equivalent: one Health Connect day for PUT /v1/health/days/{date}.

/** One timed value from Health Connect (a resting heart rate, a weight, ...). */
data class HealthReading(val at: Instant, val value: Double)

/** A stretch of time, e.g. one mindfulness session. */
data class HealthSpan(val start: Instant, val end: Instant)

/**
 * What the Health Connect reader hands over for one day. Totals are Health Connect's own aggregates for the day (which
 * already settle overlapping sources); readings are the raw records, and only those inside the day are used. Null or
 * empty means Health Connect had nothing, which becomes null in the metrics, never 0.
 */
data class DailyHealthInput(
    /** The day, in `zone`. */
    val day: LocalDate,
    val zone: ZoneId,
    /** When the reader read it; a later copy of the same day wins on the backend. */
    val recordedAt: Instant,
    /** The apps the data came from, by package name. */
    val sources: List<String> = emptyList(),
    /** StepsRecord.COUNT_TOTAL. */
    val steps: Long? = null,
    /** DistanceRecord.DISTANCE_TOTAL in metres. */
    val distanceMetres: Double? = null,
    /** ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL in kcal. */
    val activeKcal: Double? = null,
    /** ExerciseSessionRecord.EXERCISE_DURATION_TOTAL in minutes. */
    val exerciseMinutes: Double? = null,
    /** RestingHeartRateRecord, bpm: the day's average. */
    val restingHr: List<HealthReading> = emptyList(),
    /** HeartRateVariabilityRmssdRecord, ms: the day's average. */
    val hrvRmssd: List<HealthReading> = emptyList(),
    /** RespiratoryRateRecord, breaths a minute: the day's average. */
    val respiratoryRate: List<HealthReading> = emptyList(),
    /** OxygenSaturationRecord, percent: the day's average. */
    val spo2: List<HealthReading> = emptyList(),
    /** SkinTemperatureRecord deltas from the baseline, °C: the day's average. */
    val skinTempDelta: List<HealthReading> = emptyList(),
    /** WeightRecord, kg: the latest of the day. */
    val weight: List<HealthReading> = emptyList(),
    /** MindfulnessSessionRecord spans; overlaps count once. */
    val mindful: List<HealthSpan> = emptyList(),
    /** Sleep samples around the night that ended this day (the same list SleepNightBuilder takes). */
    val sleep: List<SleepSample> = emptyList(),
    val workouts: List<HealthWorkout> = emptyList(),
)

/** Builds the day's `HealthDay` from plain inputs. Values outside the backend's limits are left out (null). */
object DailyTotalsBuilder {
    const val maxSteps = 200_000
    const val maxKm = 500.0
    const val maxKcal = 20_000.0
    const val maxMinutes = 1440
    const val maxHr = 250.0
    const val maxHrv = 500.0
    const val maxBreaths = 80.0
    const val maxSpo2 = 100.0
    const val minSkinTemp = -10.0
    const val maxSkinTemp = 10.0
    const val maxWeight = 400.0
    const val maxWorkouts = 50

    fun build(input: DailyHealthInput): HealthDay = HealthDay(
        date = DayKey.string(input.day),
        timeZone = input.zone.id,
        recordedAt = input.recordedAt,
        sources = input.sources.distinct(),
        metrics = metrics(input),
        workouts = workouts(input.workouts, input.day, input.zone),
    )

    fun metrics(input: DailyHealthInput): HealthMetrics {
        val window = dayWindow(input.day, input.zone)
        fun inDay(readings: List<HealthReading>) = readings.filter { it.at >= window.start && it.at < window.end }
        val stages = sleepStages(input.sleep, input.day, input.zone)
        return HealthMetrics(
            stepCount = steps(input.steps),
            distanceKm = distanceKm(input.distanceMetres),
            activeKcal = input.activeKcal?.takeIf { it.isFinite() && it in 0.0..maxKcal }?.let { round(it, 1) },
            exerciseMinutes = wholeMinutes(input.exerciseMinutes),
            restingHrBpm = average(inDay(input.restingHr), above = 0.0, upTo = maxHr)?.let { round(it, 1) },
            hrvRmssdMs = average(inDay(input.hrvRmssd), above = 0.0, upTo = maxHrv)?.let { round(it, 1) },
            breathsPerMin = average(inDay(input.respiratoryRate), above = 0.0, upTo = maxBreaths)?.let { round(it, 1) },
            spo2Pct = average(inDay(input.spo2), above = 0.0, upTo = maxSpo2)?.let { round(it, 1) },
            skinTempDeltaC = inDay(input.skinTempDelta).map { it.value }
                .filter { it.isFinite() && it in minSkinTemp..maxSkinTemp }
                .takeIf { it.isNotEmpty() }?.average()?.let { round(it, 2) },
            mindfulMinutes = mindfulMinutes(input.mindful, input.day, input.zone),
            weightKg = inDay(input.weight).filter { valid(it.value, above = 0.0, upTo = maxWeight) }
                .maxByOrNull { it.at }?.value?.let { round(it, 2) },
            sleepDeepMin = stages.deep,
            sleepLightMin = stages.light,
            sleepRemMin = stages.rem,
            sleepAwakeMin = stages.awake,
        )
    }

    /** Midnight to the next midnight of `day` in `zone`. */
    fun dayWindow(day: LocalDate, zone: ZoneId): HealthSpan =
        HealthSpan(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())

    /** Steps within 0...200000, else null. */
    fun steps(count: Long?): Int? = count?.takeIf { it in 0..maxSteps.toLong() }?.toInt()

    /** Metres as km rounded to 2 places, within 0...500 km, else null. */
    fun distanceKm(metres: Double?): Double? {
        if (metres == null || !metres.isFinite() || metres < 0) return null
        val km = round(metres / 1000, 2)
        return if (km <= maxKm) km else null
    }

    /** Minutes rounded to whole, within 0...1440, else null. */
    fun wholeMinutes(minutes: Double?): Int? {
        if (minutes == null || !minutes.isFinite() || minutes < 0) return null
        val whole = round(minutes, 0).toInt()
        return if (whole <= maxMinutes) whole else null
    }

    /** The day's mindful minutes, overlapping sessions counted once; null when there are none. */
    fun mindfulMinutes(sessions: List<HealthSpan>, day: LocalDate, zone: ZoneId): Int? {
        val window = dayWindow(day, zone)
        val merged = SleepNightBuilder.merge(sessions.map { SleepNightBuilder.Span(it.start, it.end) })
        val seconds = SleepNightBuilder.seconds(merged, SleepNightBuilder.Span(window.start, window.end))
        val minutes = SleepNightBuilder.minutes(seconds)
        return if (minutes in 1..maxMinutes) minutes else null
    }

    /** The minutes of each stage in the main night; each null when it has none. */
    data class SleepStages(val deep: Int? = null, val light: Int? = null, val rem: Int? = null, val awake: Int? = null)

    /**
     * Stage minutes of the night that ended on `day` in `zone`: the night `SleepNightBuilder` picks, with each stage
     * counted only inside its bedtime-to-wake window. All null when that night ended on another day, or there is none.
     */
    fun sleepStages(samples: List<SleepSample>, day: LocalDate, zone: ZoneId): SleepStages {
        val window = SleepNightBuilder.nightWindow(samples) ?: return SleepStages()
        if (DayKey.string(window.end, zone) != DayKey.string(day)) return SleepStages()
        fun stage(kind: SleepSample.Kind): Int? {
            val merged = SleepNightBuilder.merge(SleepNightBuilder.spans(samples) { it == kind })
            val minutes = SleepNightBuilder.minutes(SleepNightBuilder.seconds(merged, window))
            return if (minutes in 1..maxMinutes) minutes else null
        }
        return SleepStages(
            deep = stage(SleepSample.Kind.asleepDeep),
            light = stage(SleepSample.Kind.asleepCore),
            rem = stage(SleepSample.Kind.asleepREM),
            awake = stage(SleepSample.Kind.awake),
        )
    }

    /** The workouts that started on `day`, soonest first, at most 50. */
    fun workouts(workouts: List<HealthWorkout>, day: LocalDate, zone: ZoneId): List<HealthWorkout> {
        val window = dayWindow(day, zone)
        return workouts.filter { it.startedAt >= window.start && it.startedAt < window.end }
            .sortedBy { it.startedAt }
            .take(maxWorkouts)
    }

    private fun valid(value: Double, above: Double, upTo: Double): Boolean = value.isFinite() && value > above && value <= upTo

    /** The average of the readings within (above, upTo]; out-of-range readings are skipped. Null when none are left. */
    private fun average(readings: List<HealthReading>, above: Double, upTo: Double): Double? =
        readings.map { it.value }.filter { valid(it, above, upTo) }.takeIf { it.isNotEmpty() }?.average()

    /** Halves away from zero, on the decimal value. */
    private fun round(value: Double, places: Int): Double =
        BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).toDouble()
}
