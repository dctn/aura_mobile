package info.ibtaura.aura.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** An instant written with its offset, e.g. "2030-03-16T23:00:00+05:30". */
internal fun at(text: String): Instant = OffsetDateTime.parse(text).toInstant()

internal fun sample(start: String, end: String, kind: SleepSample.Kind): SleepSample = SleepSample(at(start), at(end), kind)

/** A port of SleepNightBuilderTests.swift. */
class SleepNightBuilderTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun noAsleepSampleIsNoNight() {
        assertNull(SleepNightBuilder.build(emptyList(), kolkata))
        val inBedOnly = listOf(
            sample("2030-03-16T23:00:00+05:30", "2030-03-17T07:00:00+05:30", SleepSample.Kind.inBed),
            sample("2030-03-17T03:00:00+05:30", "2030-03-17T03:10:00+05:30", SleepSample.Kind.awake),
        )
        assertNull(SleepNightBuilder.build(inBedOnly, kolkata))
    }

    @Test
    fun overlappingSourcesAreMerged() {
        val samples = listOf(
            sample("2030-03-16T23:00:00+05:30", "2030-03-17T03:00:00+05:30", SleepSample.Kind.asleepCore),
            sample("2030-03-16T23:30:00+05:30", "2030-03-17T02:00:00+05:30", SleepSample.Kind.asleepUnspecified),
            sample("2030-03-17T02:30:00+05:30", "2030-03-17T04:00:00+05:30", SleepSample.Kind.asleepDeep),
            sample("2030-03-17T04:00:00+05:30", "2030-03-17T06:30:00+05:30", SleepSample.Kind.asleepREM),
        )
        val night = assertNotNull(SleepNightBuilder.build(samples, kolkata))
        assertEquals(at("2030-03-16T23:00:00+05:30"), night.bedtimeAt)
        assertEquals(at("2030-03-17T06:30:00+05:30"), night.wakeAt)
        assertEquals(450, night.totalSleepMin)
        assertNull(night.awakeMin)
        assertNull(night.inBedMin)
        assertEquals("2030-03-17", night.date)
        assertEquals("Asia/Kolkata", night.timeZone)
    }

    @Test
    fun awakeCountsOnlyInsideTheNight() {
        val samples = listOf(
            sample("2030-03-16T22:30:00+05:30", "2030-03-16T22:50:00+05:30", SleepSample.Kind.awake),
            sample("2030-03-16T23:00:00+05:30", "2030-03-17T02:00:00+05:30", SleepSample.Kind.asleepCore),
            sample("2030-03-17T02:00:00+05:30", "2030-03-17T02:20:00+05:30", SleepSample.Kind.awake),
            sample("2030-03-17T02:10:00+05:30", "2030-03-17T02:20:00+05:30", SleepSample.Kind.awake),
            sample("2030-03-17T02:20:00+05:30", "2030-03-17T06:00:00+05:30", SleepSample.Kind.asleepCore),
            sample("2030-03-17T06:30:00+05:30", "2030-03-17T06:40:00+05:30", SleepSample.Kind.awake),
        )
        val night = assertNotNull(SleepNightBuilder.build(samples, kolkata))
        assertEquals(400, night.totalSleepMin)
        assertEquals(20, night.awakeMin)
    }

    @Test
    fun inBedIsClampedToTheNightAndDroppedBelowTheSleep() {
        val asleep = sample("2030-03-16T23:00:00+05:30", "2030-03-17T07:00:00+05:30", SleepSample.Kind.asleepCore)
        val short = listOf(asleep, sample("2030-03-16T23:00:00+05:30", "2030-03-17T05:00:00+05:30", SleepSample.Kind.inBed))
        val shortNight = assertNotNull(SleepNightBuilder.build(short, kolkata))
        assertEquals(480, shortNight.totalSleepMin)
        assertNull(shortNight.inBedMin, "Less in bed than asleep is left out")

        val long = listOf(asleep, sample("2030-03-16T22:30:00+05:30", "2030-03-17T07:30:00+05:30", SleepSample.Kind.inBed))
        val longNight = assertNotNull(SleepNightBuilder.build(long, kolkata))
        assertEquals(480, longNight.inBedMin, "Clamped to bedtime ... wake")
    }

    @Test
    fun aGapOfThreeHoursSplitsAndTheLatestSleepIsTheNight() {
        val samples = listOf(
            sample("2030-03-16T14:00:00+05:30", "2030-03-16T15:00:00+05:30", SleepSample.Kind.asleepUnspecified),
            sample("2030-03-16T23:00:00+05:30", "2030-03-17T01:00:00+05:30", SleepSample.Kind.asleepCore),
            sample("2030-03-17T04:00:00+05:30", "2030-03-17T07:00:00+05:30", SleepSample.Kind.asleepCore),
        )
        val night = assertNotNull(SleepNightBuilder.build(samples, kolkata))
        assertEquals(at("2030-03-17T04:00:00+05:30"), night.bedtimeAt)
        assertEquals(at("2030-03-17T07:00:00+05:30"), night.wakeAt)
        assertEquals(180, night.totalSleepMin)
    }

    @Test
    fun aGapUnderThreeHoursStaysOneNight() {
        val samples = listOf(
            sample("2030-03-16T23:00:00+05:30", "2030-03-17T01:00:00+05:30", SleepSample.Kind.asleepCore),
            sample("2030-03-17T03:59:00+05:30", "2030-03-17T07:00:00+05:30", SleepSample.Kind.asleepCore),
        )
        val night = assertNotNull(SleepNightBuilder.build(samples, kolkata))
        assertEquals(at("2030-03-16T23:00:00+05:30"), night.bedtimeAt)
        assertEquals(120 + 181, night.totalSleepMin)
    }

    @Test
    fun aNightOverSixteenHoursIsNone() {
        val samples = listOf(
            sample("2030-03-16T08:00:00+05:30", "2030-03-16T20:00:00+05:30", SleepSample.Kind.asleepCore),
            sample("2030-03-16T21:00:00+05:30", "2030-03-17T01:00:00+05:30", SleepSample.Kind.asleepCore),
        )
        assertNull(SleepNightBuilder.build(samples, kolkata))
    }

    @Test
    fun emptyAndBackwardsSamplesAreDropped() {
        val samples = listOf(
            sample("2030-03-17T05:00:00+05:30", "2030-03-17T05:00:00+05:30", SleepSample.Kind.asleepCore),
            sample("2030-03-17T06:00:00+05:30", "2030-03-17T05:30:00+05:30", SleepSample.Kind.asleepCore),
        )
        assertNull(SleepNightBuilder.build(samples, kolkata))
    }

    @Test
    fun theDateIsTheWakesDayInTheGivenZone() {
        // 01:30-04:30 in Kolkata on the 17th is still the 16th in UTC.
        val samples = listOf(sample("2030-03-16T20:00:00Z", "2030-03-16T23:00:00Z", SleepSample.Kind.asleepCore))
        val inKolkata = assertNotNull(SleepNightBuilder.build(samples, kolkata))
        assertEquals("2030-03-17", inKolkata.date)
        assertEquals("Asia/Kolkata", inKolkata.timeZone)
        assertEquals(180, inKolkata.totalSleepMin)
        val inUtc = assertNotNull(SleepNightBuilder.build(samples, ZoneId.of("UTC")))
        assertEquals("2030-03-16", inUtc.date)
    }
}
