package info.ibtaura.aura.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthSourcePickerTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val day1: LocalDate = LocalDate.of(2026, 11, 10)
    private val unknown = "com.example.walker"

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Instant = day.atTime(hour, minute).atZone(zone).toInstant()

    private fun input(
        day: LocalDate,
        hrv: List<HealthReading> = emptyList(),
        sleep: List<SleepSample> = emptyList(),
        workouts: List<HealthWorkout> = emptyList(),
        sources: List<String> = emptyList(),
    ) = DailyHealthInput(day = day, zone = zone, recordedAt = at(day, 23), sources = sources, hrvRmssd = hrv, sleep = sleep, workouts = workouts)

    private fun hrv(day: LocalDate, source: String, vararg values: Double) =
        values.mapIndexed { i, v -> HealthReading(at(day, 3 + i), v, source) }

    private fun night(day: LocalDate, source: String, hours: Int): List<SleepSample> {
        val start = at(day.minusDays(1), 23)
        return listOf(
            SleepSample(start, start.plusSeconds(hours * 3600L), SleepSample.Kind.inBed, source),
            SleepSample(start, start.plusSeconds(hours * 1800L), SleepSample.Kind.asleepDeep, source),
            SleepSample(start.plusSeconds(hours * 1800L), start.plusSeconds(hours * 3600L), SleepSample.Kind.asleepREM, source),
        )
    }

    private fun run(id: String, source: String, start: Instant, minutes: Int) =
        HealthWorkout(id, "running", start, start.plusSeconds(minutes * 60L), minutes, null, null, null, source)

    @Test
    fun twoAppsHrvKeepsOneAppOnly() {
        val day = input(day1, hrv = hrv(day1, HealthSources.SAMSUNG, 40.0, 42.0) + hrv(day1, HealthSources.OURA, 70.0))
        val pick = HealthSourcePicker.pick(day, emptyMap(), emptyMap())
        assertEquals(setOf(HealthSources.OURA), pick.input.hrvRmssd.map { it.source }.toSet())
        assertEquals(HealthSources.OURA, pick.choices[HealthMetric.hrv]?.source)
        assertEquals(setOf(HealthSources.SAMSUNG, HealthSources.OURA), pick.seen[HealthSourceGroup.heart])
        assertTrue(HealthSources.OURA in pick.input.sources)
        assertTrue(HealthSources.SAMSUNG !in pick.input.sources)
    }

    @Test
    fun sameRankMostDataWins() {
        val day = input(day1, hrv = hrv(day1, unknown, 50.0) + hrv(day1, "com.example.other", 51.0, 52.0, 53.0))
        val pick = HealthSourcePicker.pick(day, emptyMap(), emptyMap())
        assertEquals(setOf("com.example.other"), pick.input.hrvRmssd.map { it.source }.toSet())
    }

    @Test
    fun mainAppHoldsForShortGapsThenSwitchesAfterThreeDays() {
        // Day 1 Oura and Samsung; days 2-4 only Samsung (the ring is off); day 5 Oura back.
        val days = listOf(
            input(day1, hrv = hrv(day1, HealthSources.OURA, 70.0) + hrv(day1, HealthSources.SAMSUNG, 40.0)),
            input(day1.plusDays(1), hrv = hrv(day1.plusDays(1), HealthSources.SAMSUNG, 41.0)),
            input(day1.plusDays(2), hrv = hrv(day1.plusDays(2), HealthSources.SAMSUNG, 42.0)),
            input(day1.plusDays(3), hrv = hrv(day1.plusDays(3), HealthSources.SAMSUNG, 43.0)),
        )
        var choices = emptyMap<HealthMetric, HealthSourceChoice>()
        val mains = days.map { d ->
            val pick = HealthSourcePicker.pick(d, choices, emptyMap())
            choices = pick.choices
            // Each day still sends the app that has data (Samsung on gap days).
            assertEquals(1, pick.input.hrvRmssd.map { it.source }.distinct().size)
            choices[HealthMetric.hrv]?.source
        }
        // Oura stays main on day 2 and 3 (1 and 2 days missing), Samsung takes over on day 4 (3 days missing).
        assertEquals(listOf(HealthSources.OURA, HealthSources.OURA, HealthSources.OURA, HealthSources.SAMSUNG), mains)
    }

    @Test
    fun stickyMainAppBeatsBetterRankedNewcomer() {
        val current = mapOf(HealthMetric.hrv to HealthSourceChoice(HealthSources.SAMSUNG, day1.minusDays(1)))
        val day = input(day1, hrv = hrv(day1, HealthSources.SAMSUNG, 40.0) + hrv(day1, HealthSources.OURA, 70.0))
        val pick = HealthSourcePicker.pick(day, current, emptyMap())
        assertEquals(setOf(HealthSources.SAMSUNG), pick.input.hrvRmssd.map { it.source }.toSet())
        assertEquals(day1, pick.choices[HealthMetric.hrv]?.lastSeen)
    }

    @Test
    fun overrideWins() {
        val current = mapOf(HealthMetric.hrv to HealthSourceChoice(HealthSources.OURA, day1))
        val day = input(day1, hrv = hrv(day1, HealthSources.SAMSUNG, 40.0) + hrv(day1, HealthSources.OURA, 70.0))
        val pick = HealthSourcePicker.pick(day, current, mapOf(HealthSourceGroup.heart to HealthSources.SAMSUNG))
        assertEquals(setOf(HealthSources.SAMSUNG), pick.input.hrvRmssd.map { it.source }.toSet())
    }

    @Test
    fun overrideWithoutDataFallsBack() {
        val day = input(day1, hrv = hrv(day1, HealthSources.OURA, 70.0))
        val pick = HealthSourcePicker.pick(day, emptyMap(), mapOf(HealthSourceGroup.heart to HealthSources.WHOOP))
        assertEquals(setOf(HealthSources.OURA), pick.input.hrvRmssd.map { it.source }.toSet())
    }

    @Test
    fun sameRunFromTwoAppsKeptOnce() {
        val start = at(day1, 7)
        val garmin = run("g1", HealthSources.GARMIN, start, 40)
        val strava = run("s1", HealthSources.STRAVA, start.plusSeconds(60), 39)
        val evening = run("s2", HealthSources.STRAVA, at(day1, 18), 30)
        val pick = HealthSourcePicker.pick(input(day1, workouts = listOf(strava, garmin, evening)), emptyMap(), emptyMap())
        assertEquals(listOf("g1", "s2"), pick.input.workouts.map { it.id })
        assertTrue(HealthSources.STRAVA in pick.input.sources)
    }

    @Test
    fun workoutsOverlappingLessThanHalfBothKept() {
        val a = run("a", HealthSources.GARMIN, at(day1, 7), 60)
        val b = run("b", HealthSources.STRAVA, at(day1, 7, 50), 60)
        assertEquals(2, HealthSourcePicker.dedupeWorkouts(listOf(a, b), null).size)
    }

    @Test
    fun twoAppsSleepKeepsOneAppsNight() {
        val sleep = night(day1, HealthSources.SAMSUNG, 7) + night(day1, HealthSources.OURA, 6)
        val pick = HealthSourcePicker.pick(input(day1, sleep = sleep), emptyMap(), emptyMap())
        assertEquals(setOf(HealthSources.OURA), pick.input.sleep.map { it.source }.toSet())
        assertEquals(HealthSources.OURA, pick.choices[HealthMetric.sleep]?.source)
    }

    @Test
    fun nightSamplesUsesMainApp() {
        val sleep = night(day1, HealthSources.SAMSUNG, 7) + night(day1, HealthSources.OURA, 6)
        val main = HealthSourceChoice(HealthSources.SAMSUNG, day1.minusDays(1))
        assertEquals(setOf(HealthSources.SAMSUNG), HealthSourcePicker.nightSamples(sleep, main, null).map { it.source }.toSet())
        assertEquals(setOf(HealthSources.OURA), HealthSourcePicker.nightSamples(sleep, null, null).map { it.source }.toSet())
        assertEquals(setOf(HealthSources.SAMSUNG), HealthSourcePicker.nightSamples(sleep, null, HealthSources.SAMSUNG).map { it.source }.toSet())
    }

    @Test
    fun oneAppIsLeftAsIs() {
        val sleep = night(day1, HealthSources.SAMSUNG, 7)
        assertEquals(sleep, HealthSourcePicker.nightSamples(sleep, null, null))
        val day = input(day1, hrv = hrv(day1, HealthSources.SAMSUNG, 40.0, 41.0), sources = listOf(HealthSources.SAMSUNG))
        val pick = HealthSourcePicker.pick(day, emptyMap(), emptyMap())
        assertEquals(day.hrvRmssd, pick.input.hrvRmssd)
        assertEquals(listOf(HealthSources.SAMSUNG), pick.input.sources)
    }

    @Test
    fun emptyDayChangesNothing() {
        val current = mapOf(HealthMetric.hrv to HealthSourceChoice(HealthSources.OURA, day1.minusDays(5)))
        val pick = HealthSourcePicker.pick(input(day1), current, emptyMap())
        assertEquals(current, pick.choices)
        assertTrue(pick.seen.isEmpty())
        assertNull(pick.choices[HealthMetric.sleep])
    }

    @Test
    fun pickDaysRunsOldestFirstAndCollectsSeen() {
        val later = input(day1.plusDays(1), hrv = hrv(day1.plusDays(1), HealthSources.SAMSUNG, 40.0))
        val first = input(day1, hrv = hrv(day1, HealthSources.OURA, 70.0))
        val result = HealthSourcePicker.pickDays(listOf(later, first), emptyMap(), emptyMap())
        assertEquals(listOf(day1, day1.plusDays(1)), result.inputs.map { it.day })
        // Oura was main from day 1; one day missing keeps it main.
        assertEquals(HealthSourceChoice(HealthSources.OURA, day1), result.choices[HealthMetric.hrv])
        assertEquals(setOf(HealthSources.OURA, HealthSources.SAMSUNG), result.seen[HealthSourceGroup.heart])
    }

    @Test
    fun lastSeenHoldsEachAppsLatestDayWithData() {
        val days = listOf(
            input(day1, hrv = hrv(day1, HealthSources.OURA, 70.0) + hrv(day1, HealthSources.SAMSUNG, 40.0)),
            input(day1.plusDays(2), hrv = hrv(day1.plusDays(2), HealthSources.SAMSUNG, 41.0)),
            input(day1.plusDays(3)),
        )
        val result = HealthSourcePicker.pickDays(days, emptyMap(), emptyMap())
        assertEquals(
            mapOf(HealthSources.OURA to day1, HealthSources.SAMSUNG to day1.plusDays(2)),
            result.lastSeen[HealthSourceGroup.heart],
        )
        // An app that never wrote data on the phone is never seen or chosen.
        assertTrue(result.lastSeen.values.none { HealthSources.WHOOP in it })
        assertNull(result.lastSeen[HealthSourceGroup.sleep])
    }

    @Test
    fun namesForKnownAndUnknownApps() {
        assertEquals("Samsung Health", HealthSources.name(HealthSources.SAMSUNG))
        assertEquals("walker", HealthSources.name(unknown))
    }
}
