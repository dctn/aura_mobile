package info.ibtaura.aura.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepSourceCheckTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val day1: LocalDate = LocalDate.of(2026, 11, 1)
    private val available = HealthStoreStatus.available

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Instant = day.atTime(hour, minute).atZone(zone).toInstant()

    /** A night ending on `wake`'s morning: asleep 23:00 the evening before for `hours`. */
    private fun night(wake: LocalDate, source: String, hours: Int = 7): List<SleepSample> {
        val start = at(wake.minusDays(1), 23)
        return listOf(SleepSample(start, start.plusSeconds(hours * 3600L), SleepSample.Kind.asleepUnspecified, source))
    }

    @Test
    fun healthConnectMissingOrNotInstalledBlocks() {
        assertEquals(SleepSourceState.Blocked(SleepSourceBlock.noHealthConnect),
            SleepSourceCheck.decide(HealthStoreStatus.unavailable, true, 5, null, emptyList()))
        assertEquals(SleepSourceState.Blocked(SleepSourceBlock.installHealthConnect),
            SleepSourceCheck.decide(HealthStoreStatus.needsInstall, false, 0, null, listOf(HealthSources.SAMSUNG)))
    }

    @Test
    fun noSleepPermissionBlocks() {
        assertEquals(SleepSourceState.Blocked(SleepSourceBlock.notConnected),
            SleepSourceCheck.decide(available, false, 3, HealthSources.OURA, listOf(HealthSources.OURA)))
    }

    @Test
    fun pastNightsAreReady() {
        val state = SleepSourceCheck.decide(available, true, 12, HealthSources.SAMSUNG, emptyList())
        assertEquals(SleepSourceState.Ready(12, HealthSources.SAMSUNG), state)
        assertTrue(state.canUseApp)
    }

    @Test
    fun noNightsButASleepAppIsCollectingWithTheBestApp() {
        val state = SleepSourceCheck.decide(available, true, 0, null, listOf(HealthSources.STRAVA, HealthSources.SAMSUNG, HealthSources.OURA))
        assertEquals(SleepSourceState.Collecting(HealthSources.OURA), state)
        assertTrue(state.canUseApp)
    }

    @Test
    fun workoutAndHubAppsAloneBlock() {
        val state = SleepSourceCheck.decide(available, true, 0, null, listOf(HealthSources.STRAVA, HealthSources.GOOGLE_FIT, "com.example.x"))
        assertEquals(SleepSourceState.Blocked(SleepSourceBlock.noSleepApp), state)
        assertFalse(state.canUseApp)
        assertFalse(SleepSourceState.Checking.canUseApp)
    }

    @Test
    fun historyHasOneNightPerWakeDateOldestFirst() {
        val samples = night(day1.plusDays(2), HealthSources.SAMSUNG) + night(day1, HealthSources.SAMSUNG) +
            night(day1.plusDays(1), HealthSources.SAMSUNG, hours = 6)
        val history = SleepHistoryBuilder.build(samples, zone, null, null)
        assertEquals(listOf("2026-11-01", "2026-11-02", "2026-11-03"), history.nights.map { it.date })
        assertEquals(listOf(420, 360, 420), history.nights.map { it.totalSleepMin })
        assertEquals(HealthSources.SAMSUNG, history.source)
    }

    @Test
    fun historyKeepsOneAppPerNightAndTheNightOverANap() {
        val nap = SleepSample(at(day1, 14), at(day1, 15), SleepSample.Kind.asleepUnspecified, HealthSources.SAMSUNG)
        val samples = night(day1, HealthSources.SAMSUNG, 8) + night(day1, HealthSources.OURA, 7) + nap
        val history = SleepHistoryBuilder.build(samples, zone, null, null)
        assertEquals(1, history.nights.size)
        assertEquals(420, history.nights.single().totalSleepMin)
        assertEquals(HealthSources.OURA, history.source)
    }

    @Test
    fun emptyHistory() {
        val history = SleepHistoryBuilder.build(emptyList(), zone, null, null)
        assertTrue(history.nights.isEmpty())
        assertNull(history.source)
    }

    @Test
    fun bannerCountsNightsUntilEight() {
        val zero = CollectingCopy.banner(MorningOpening(estimated = true, baselineDays = 0, lastNight = true), "Oura", 0)
        assertEquals("Collecting sleep data: 1 of 8 nights", zero?.title)
        val seven = CollectingCopy.banner(MorningOpening(estimated = true, baselineDays = 6, lastNight = true), "Oura", 0)
        assertEquals("Collecting sleep data: 7 of 8 nights", seven?.title)
        val eight = CollectingCopy.banner(MorningOpening(estimated = true, baselineDays = 7, lastNight = true, reason = "Only part of the morning is in."), null, 0)
        assertEquals("Your capacity score is an estimate", eight?.title)
        assertEquals("Only part of the morning is in.", eight?.detail)
        assertNull(CollectingCopy.banner(MorningOpening(estimated = false, baselineDays = 14, lastNight = true), "Oura", 0))
        assertNull(CollectingCopy.banner(null, "Oura", 0))
    }

    @Test
    fun bannerWarnsAfterThreeMissedNights() {
        val missing = MorningOpening(estimated = true, baselineDays = 3, lastNight = false)
        val one = CollectingCopy.banner(missing, "Samsung Health", 1)
        assertEquals("No sleep from last night", one?.title)
        assertTrue(one!!.detail.endsWith("in Samsung Health."))
        assertFalse(one.warning)
        val three = CollectingCopy.banner(missing, null, 3)
        assertEquals("No sleep for 3 nights", three?.title)
        assertTrue(three!!.warning)
        assertTrue(three.detail.endsWith("in your watch or band's app."))
    }
}
