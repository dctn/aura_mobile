package info.ibtaura.aura.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Android only: there is no Swift counterpart (iOS sends HealthKit nights, not daily totals). */
class DailyTotalsBuilderTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2030, 3, 17)
    private val recorded = at("2030-03-17T22:00:00+05:30")

    private fun input(
        steps: Long? = null,
        distanceMetres: Double? = null,
        activeKcal: Double? = null,
        exerciseMinutes: Double? = null,
        restingHr: List<HealthReading> = emptyList(),
        hrvRmssd: List<HealthReading> = emptyList(),
        respiratoryRate: List<HealthReading> = emptyList(),
        spo2: List<HealthReading> = emptyList(),
        skinTempDelta: List<HealthReading> = emptyList(),
        weight: List<HealthReading> = emptyList(),
        mindful: List<HealthSpan> = emptyList(),
        sleep: List<SleepSample> = emptyList(),
        workouts: List<HealthWorkout> = emptyList(),
        sources: List<String> = emptyList(),
    ) = DailyHealthInput(
        day = day, zone = kolkata, recordedAt = recorded, sources = sources, steps = steps,
        distanceMetres = distanceMetres, activeKcal = activeKcal, exerciseMinutes = exerciseMinutes,
        restingHr = restingHr, hrvRmssd = hrvRmssd, respiratoryRate = respiratoryRate, spo2 = spo2,
        skinTempDelta = skinTempDelta, weight = weight, mindful = mindful, sleep = sleep, workouts = workouts,
    )

    private fun reading(time: String, value: Double) = HealthReading(at("2030-03-17T${time}:00+05:30"), value)

    private fun workout(id: String, start: Instant) = HealthWorkout(
        id = id, type = "walking", startedAt = start, endedAt = start.plusSeconds(1800), minutes = 30,
        activeKcal = null, distanceKm = null, avgHrBpm = null, source = null,
    )

    @Test
    fun anEmptyDayHasTheDateZoneAndNoValues() {
        val health = DailyTotalsBuilder.build(input(sources = listOf("a", "b", "a")))
        assertEquals("2030-03-17", health.date)
        assertEquals("Asia/Kolkata", health.timeZone)
        assertEquals(recorded, health.recordedAt)
        assertEquals(listOf("a", "b"), health.sources)
        assertEquals(HealthMetrics(), health.metrics)
        assertEquals(emptyList(), health.workouts)
    }

    @Test
    fun totalsOutsideTheLimitsAreLeftOut() {
        assertEquals(8200, DailyTotalsBuilder.steps(8200))
        assertEquals(200_000, DailyTotalsBuilder.steps(200_000))
        assertNull(DailyTotalsBuilder.steps(200_001))
        assertNull(DailyTotalsBuilder.steps(-1))
        assertNull(DailyTotalsBuilder.steps(null))

        assertEquals(3.46, DailyTotalsBuilder.distanceKm(3455.0))
        assertEquals(500.0, DailyTotalsBuilder.distanceKm(500_000.0))
        assertNull(DailyTotalsBuilder.distanceKm(500_010.0))
        assertNull(DailyTotalsBuilder.distanceKm(-1.0))
        assertNull(DailyTotalsBuilder.distanceKm(Double.NaN))

        assertEquals(33, DailyTotalsBuilder.wholeMinutes(32.5))
        assertEquals(1440, DailyTotalsBuilder.wholeMinutes(1440.0))
        assertNull(DailyTotalsBuilder.wholeMinutes(1441.0))
        assertNull(DailyTotalsBuilder.wholeMinutes(Double.POSITIVE_INFINITY))

        val metrics = DailyTotalsBuilder.metrics(input(activeKcal = 420.45, exerciseMinutes = 12.4))
        assertEquals(420.5, metrics.activeKcal)
        assertEquals(12, metrics.exerciseMinutes)
        assertNull(DailyTotalsBuilder.metrics(input(activeKcal = 20_001.0)).activeKcal)
    }

    @Test
    fun averagesSkipOtherDaysAndOutOfRangeReadings() {
        val metrics = DailyTotalsBuilder.metrics(
            input(
                restingHr = listOf(
                    reading("06:00", 60.0), reading("07:00", 63.0), reading("08:00", 0.0), reading("09:00", 251.0),
                    // The day before and the next midnight don't count.
                    HealthReading(at("2030-03-16T23:59:00+05:30"), 90.0), HealthReading(at("2030-03-18T00:00:00+05:30"), 90.0),
                ),
                hrvRmssd = listOf(reading("06:00", 41.24), reading("07:00", 500.1)),
                respiratoryRate = listOf(reading("06:00", 14.0), reading("07:00", 15.0)),
                spo2 = listOf(reading("06:00", 97.0), reading("07:00", 100.0), reading("08:00", 100.5)),
                skinTempDelta = listOf(reading("06:00", -0.333), reading("07:00", -10.5), reading("08:00", 0.0)),
            ),
        )
        assertEquals(61.5, metrics.restingHrBpm)
        assertEquals(41.2, metrics.hrvRmssdMs)
        assertEquals(14.5, metrics.breathsPerMin)
        assertEquals(98.5, metrics.spo2Pct)
        assertEquals(-0.17, metrics.skinTempDeltaC)
    }

    @Test
    fun weightIsTheLatestValidReadingOfTheDay() {
        val metrics = DailyTotalsBuilder.metrics(
            input(
                weight = listOf(
                    reading("07:00", 70.456), reading("20:00", 70.123), reading("21:00", 401.0),
                    HealthReading(at("2030-03-18T07:00:00+05:30"), 69.0),
                ),
            ),
        )
        assertEquals(70.12, metrics.weightKg)
    }

    @Test
    fun mindfulSessionsCountOnceAndOnlyInsideTheDay() {
        val sessions = listOf(
            HealthSpan(at("2030-03-17T08:00:00+05:30"), at("2030-03-17T08:10:00+05:30")),
            HealthSpan(at("2030-03-17T08:05:00+05:30"), at("2030-03-17T08:15:00+05:30")),
            // 10 minutes before midnight count; the 10 after don't.
            HealthSpan(at("2030-03-17T23:50:00+05:30"), at("2030-03-18T00:10:00+05:30")),
        )
        assertEquals(25, DailyTotalsBuilder.mindfulMinutes(sessions, day, kolkata))
        assertNull(DailyTotalsBuilder.mindfulMinutes(emptyList(), day, kolkata))
    }

    @Test
    fun sleepStagesComeFromTheNightThatEndedThatDay() {
        val night = listOf(
            sample("2030-03-16T23:00:00+05:30", "2030-03-17T01:00:00+05:30", SleepSample.Kind.asleepCore),
            sample("2030-03-17T01:00:00+05:30", "2030-03-17T02:30:00+05:30", SleepSample.Kind.asleepDeep),
            sample("2030-03-17T02:30:00+05:30", "2030-03-17T02:40:00+05:30", SleepSample.Kind.awake),
            sample("2030-03-17T02:40:00+05:30", "2030-03-17T05:00:00+05:30", SleepSample.Kind.asleepCore),
            sample("2030-03-17T05:00:00+05:30", "2030-03-17T06:30:00+05:30", SleepSample.Kind.asleepREM),
            // Awake after the wake isn't the night's.
            sample("2030-03-17T07:00:00+05:30", "2030-03-17T07:20:00+05:30", SleepSample.Kind.awake),
        )
        val stages = DailyTotalsBuilder.sleepStages(night, day, kolkata)
        assertEquals(DailyTotalsBuilder.SleepStages(deep = 90, light = 260, rem = 90, awake = 10), stages)

        val metrics = DailyTotalsBuilder.metrics(input(sleep = night))
        assertEquals(90, metrics.sleepDeepMin)
        assertEquals(260, metrics.sleepLightMin)
        assertEquals(90, metrics.sleepRemMin)
        assertEquals(10, metrics.sleepAwakeMin)

        // The same night read for the day before has no stages.
        assertEquals(DailyTotalsBuilder.SleepStages(), DailyTotalsBuilder.sleepStages(night, day.minusDays(1), kolkata))
        // A night with no deep sleep leaves deep null.
        val light = listOf(sample("2030-03-16T23:00:00+05:30", "2030-03-17T06:00:00+05:30", SleepSample.Kind.asleepCore))
        assertEquals(DailyTotalsBuilder.SleepStages(light = 420), DailyTotalsBuilder.sleepStages(light, day, kolkata))
    }

    @Test
    fun workoutsThatStartedThatDaySoonestFirstAtMostFifty() {
        val start = at("2030-03-17T00:00:00+05:30")
        val many = (0 until 60).map { workout("w$it", start.plusSeconds((59 - it) * 600L)) } +
            workout("before", at("2030-03-16T23:30:00+05:30")) +
            workout("after", at("2030-03-18T00:00:00+05:30"))
        val kept = DailyTotalsBuilder.workouts(many, day, kolkata)
        assertEquals(50, kept.size)
        assertEquals("w59", kept.first().id)
        assertEquals((59 downTo 10).map { "w$it" }, kept.map { it.id })
        assertEquals(kept, DailyTotalsBuilder.build(input(workouts = many)).workouts)
    }

    @Test
    fun theDayWindowFollowsTheZone() {
        val window = DailyTotalsBuilder.dayWindow(day, kolkata)
        assertEquals(at("2030-03-17T00:00:00+05:30"), window.start)
        assertEquals(at("2030-03-18T00:00:00+05:30"), window.end)
    }
}
