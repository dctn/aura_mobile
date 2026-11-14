package info.ibtaura.aura.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A port of MetricMathsTests.swift. Swift's UTC calendar is `ZoneOffset.UTC`; `dailyPoints` needs no calendar. */
class MetricMathsTest {
    private val utc = ZoneOffset.UTC

    private fun day(year: Int, month: Int, day: Int): Instant = LocalDate.of(year, month, day).atStartOfDay(utc).toInstant()

    @Test
    fun rangesEndTodayAndCountToday() {
        val today = day(2026, 3, 1)
        assertEquals(MetricDaySpan(from = "2026-02-16", to = "2026-03-01"), MetricRange.twoWeeks.span(today, utc))
        assertEquals("2026-01-31", MetricRange.oneMonth.span(today, utc).from)
        assertEquals("2026-01-01", MetricRange.twoMonths.span(today, utc).from)
        assertEquals("2026-03-01", MetricMaths.daySpan(1, today, utc).from)
    }

    @Test
    fun missingDaysBecomeGaps() {
        val span = MetricDaySpan(from = "2026-02-27", to = "2026-03-02")
        val filled = MetricMaths.dailyPoints(listOf(MetricPoint("2026-02-28", 2.0), MetricPoint("2026-03-01", null)), span)
        assertEquals(listOf("2026-02-27", "2026-02-28", "2026-03-01", "2026-03-02"), filled.map { it.date })
        assertEquals(listOf(null, 2.0, null, null), filled.map { it.value })
    }

    @Test
    fun averageSkipsGapsAndTrendReadsFilledDays() {
        val span = MetricDaySpan(from = "2026-03-01", to = "2026-03-14")
        // Only the first and last day came back; filling keeps them in their own weeks.
        val filled = MetricMaths.dailyPoints(listOf(MetricPoint("2026-03-01", 2.0), MetricPoint("2026-03-14", 3.0)), span)
        assertEquals(14, filled.size)
        assertEquals(2.5, MetricMaths.average(filled) ?: 0.0, 0.001)
        assertEquals(50.0, MetricMaths.weekTrend(filled) ?: 0.0, 0.001)
        assertNull(MetricMaths.average(listOf(MetricPoint("2026-03-01", null))))
    }

    @Test
    fun recordedRowsAreNewestFirstWithoutGaps() {
        val rows = MetricMaths.recordedRows(
            listOf(MetricPoint("2026-03-01", 1.0), MetricPoint("2026-03-02", null), MetricPoint("2026-03-03", 0.0)),
        )
        assertEquals(listOf("2026-03-03", "2026-03-01"), rows.map { it.date })
    }

    @Test
    fun barsScaleToTheHighestAndKeepGaps() {
        val bars = MetricMaths.barFractions(listOf(2.5, null, 5.0, 0.0))
        assertEquals(0.5, bars[0] ?: -1.0, 0.001)
        assertNull(bars[1])
        assertEquals(1.0, bars[2] ?: -1.0, 0.001)
        assertEquals(0.0, bars[3] ?: -1.0, 0.001)
    }

    @Test
    fun barsWithNegativesFlatValuesAndNothing() {
        val bars = MetricMaths.barFractions(listOf(-1.0, 1.0))
        assertEquals(0.0, bars[0] ?: -1.0, 0.001)
        assertEquals(1.0, bars[1] ?: -1.0, 0.001)
        assertEquals(listOf<Double?>(0.0, 0.0), MetricMaths.barFractions(listOf(0.0, 0.0)))
        assertEquals(listOf<Double?>(null, null), MetricMaths.barFractions(listOf(null, null)))
    }

    @Test
    fun formatsFollowTheContract() {
        assertEquals("8,200", MetricMaths.display(8200.4, "int"))
        assertEquals("32", MetricMaths.display(32.2, "minutes"))
        assertEquals("97", MetricMaths.display(96.6, "percent"))
        assertEquals("3.4", MetricMaths.display(3.44, "decimal1"))
        assertEquals("3.45", MetricMaths.display(3.446, "decimal2"))
        assertEquals("7.5", MetricMaths.display(450.0, "hoursFromMinutes"))
        assertEquals("3.4", MetricMaths.display(3400.0, "metresAsKm"))
        assertEquals("5:30", MetricMaths.display(330.0, "hhmm"))
        assertEquals("10:00", MetricMaths.display(10.0, "hourOfDay"))
        assertEquals("Yes", MetricMaths.display(1.0, "bool"))
        assertEquals("No", MetricMaths.display(0.0, "bool"))
    }

    @Test
    fun noFormatIsWholeForCountsAndOneDecimalOtherwise() {
        assertEquals("8,200", MetricMaths.display(8200.0, "", unit = "steps"))
        assertEquals("420", MetricMaths.display(420.4, "", unit = "kcal"))
        assertEquals("3.4", MetricMaths.display(3.4, "", unit = "km"))
        assertEquals("0.0", MetricMaths.display(-0.01, "", unit = "C"))
        assertEquals("1,234,567", MetricMaths.display(1234567.0, "int"))
        assertEquals("-1,200", MetricMaths.display(-1200.0, "int"))
    }

    @Test
    fun unitsAndAveragesForWords() {
        assertFalse(MetricMaths.canAverage("bool"))
        assertFalse(MetricMaths.canAverage("text"))
        assertTrue(MetricMaths.canAverage("metresAsKm"))
        assertEquals("", MetricMaths.shownUnit("bool", "x"))
        assertEquals("km", MetricMaths.shownUnit("metresAsKm", "km"))
    }

    @Test
    fun latestTextPrefersTheNumber() {
        val number = FieldAvailability(field = "a", latestDate = "2026-03-01", latestNumber = 3400.0)
        assertEquals("3.4", MetricMaths.latestText(number, "metresAsKm", "km"))
        val words = FieldAvailability(field = "a", latestDate = "2026-03-01", latestText = "Moderate")
        assertEquals("Moderate", MetricMaths.latestText(words, "text", ""))
        assertNull(MetricMaths.latestText(FieldAvailability(field = "a", latestDate = "2026-03-01"), "", ""))
    }

    @Test
    fun shortDay() {
        assertEquals("Nov 1", MetricMaths.shortDay("2026-11-01"))
        assertEquals("Jan 31", MetricMaths.shortDay("2026-01-31"))
        assertEquals("soon", MetricMaths.shortDay("soon"))
    }

    @Test
    fun trendWords() {
        assertEquals(MetricTrendDirection.up, MetricMaths.trendDirection(8.0))
        assertEquals(MetricTrendDirection.down, MetricMaths.trendDirection(-8.0))
        assertEquals(MetricTrendDirection.steady, MetricMaths.trendDirection(1.9))
        assertEquals("Trending Up", MetricMaths.trendTitle(8.0))
        assertEquals("Trending Down", MetricMaths.trendTitle(-8.0))
        assertEquals("Steady", MetricMaths.trendTitle(0.0))
        assertEquals("+8%", MetricMaths.trendPercentText(8.4))
        assertEquals("-10%", MetricMaths.trendPercentText(-10.2))
        assertEquals("+0%", MetricMaths.trendPercentText(0.0))
    }

    private val walk = CatalogueParameter(name = "Walk Distance", field = "walkDistanceM", read = "series", unit = "km", format = "metresAsKm")
    private val mood = CatalogueParameter(name = "Mood Word", field = "moodWord", read = "labels", unit = "", format = "text")
    private val push = CatalogueParameter(name = "Push Count", field = null, read = "none", unit = "")
    private val runDistance = CatalogueParameter(name = "Run Distance", field = "runDistanceM", read = "series", unit = "km", format = "metresAsKm")

    @Test
    fun categorySplitKeepsOrderAndNeedsAValue() {
        val rows = MetricMaths.categoryRows(
            listOf(walk, mood, push, runDistance),
            listOf(
                FieldAvailability(field = "moodWord", latestDate = "2026-03-01", latestText = "Calm"),
                FieldAvailability(field = "walkDistanceM", latestDate = "2026-03-01", latestNumber = 3400.0),
                FieldAvailability(field = "runDistanceM", latestDate = "2026-03-01"),
            ),
        )
        assertEquals(listOf("Walk Distance", "Mood Word"), rows.available.map { it.parameter.name })
        assertEquals(listOf("Push Count", "Run Distance"), rows.empty.map { it.name })
    }

    @Test
    fun searchFindsCategoriesAndMetricsOnce() {
        val movement = CatalogueSection(id = "movement", title = "Movement", parameters = listOf(walk, runDistance))
        val running = CatalogueSection(id = "running", title = "Running", parameters = listOf(runDistance, push))
        val hits = MetricMaths.search(" run ", listOf(movement, running))
        assertEquals(listOf("running"), hits.categories.map { it.id })
        assertEquals(listOf("Run Distance"), hits.metrics.map { it.parameter.name })
        assertEquals("movement", hits.metrics.firstOrNull()?.sectionId)
        assertTrue(MetricMaths.search("nothing like this", listOf(movement)).metrics.isEmpty())
        assertTrue(MetricMaths.matches("  ", "Anything"))
        assertTrue(MetricMaths.matches("WALK", "Walk Distance"))
    }
}
