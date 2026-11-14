package info.ibtaura.aura.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// A port of AuraKit/MetricMaths.swift. Swift's `Calendar` parameters are a `ZoneId` (always Gregorian).

/** The metric page's three ranges. */
enum class MetricRange(val days: Int) {
    twoWeeks(14), oneMonth(30), twoMonths(60);

    val id: Int get() = days

    val title: String
        get() = when (this) {
            twoWeeks -> "2 Weeks"
            oneMonth -> "1 Month"
            twoMonths -> "2 Months"
        }

    fun span(endingOn: Instant, zone: ZoneId = DayKey.zone): MetricDaySpan = MetricMaths.daySpan(days, endingOn, zone)

    companion object {
        fun fromDays(days: Int): MetricRange? = entries.firstOrNull { it.days == days }
    }
}

/** A run of days as the backend reads them, both ends included. */
data class MetricDaySpan(val from: String, val to: String)

enum class MetricTrendDirection { up, steady, down }

/** A category row that has a latest value. */
data class MetricAvailableRow(val parameter: CatalogueParameter, val availability: FieldAvailability) {
    val id: String get() = parameter.id
}

/** A category page's two lists, in catalogue order. */
data class MetricCategoryRows(val available: List<MetricAvailableRow>, val empty: List<CatalogueParameter>)

/** A metric found by typing in Search, with the section whose icon it shows. */
data class MetricSearchHit(val sectionId: String, val parameter: CatalogueParameter) {
    val id: String get() = parameter.id
}

data class MetricSearchHits(val categories: List<CatalogueSection>, val metrics: List<MetricSearchHit>)

/** The numbers on the metric page and the burnout card, worked out on the phone. */
object MetricMaths {
    fun average(points: List<MetricPoint>): Double? {
        val values = points.mapNotNull { it.value }
        if (values.isEmpty()) return null
        return values.sum() / values.size
    }

    /** The newest point with a value. */
    fun latest(points: List<MetricPoint>): MetricPoint? = points.lastOrNull { it.value != null }

    /** The last 7 days against the 7 before, in percent. Null without data in both weeks or with a zero base. */
    fun weekTrend(points: List<MetricPoint>): Double? {
        if (points.size < 14) return null
        val recent = average(points.takeLast(7)) ?: return null
        val before = average(points.dropLast(7).takeLast(7)) ?: return null
        if (before == 0.0) return null
        return (recent - before) / abs(before) * 100
    }

    const val burnoutDots = 12

    /** How many of the 12 dots to fill for a burnout score of 0-100. Null score: none. */
    fun burnoutLevel(score: Double?): Int {
        if (score == null) return 0
        val clamped = minOf(100.0, maxOf(0.0, score))
        return ceil(clamped / 100 * burnoutDots).toInt()
    }

    // MARK: Days

    /** The backend takes at most 400 days. */
    const val availabilityDays = 400
    const val recordedDays = 365

    fun daySpan(days: Int, endingOn: Instant, zone: ZoneId = DayKey.zone): MetricDaySpan {
        val today = endingOn.atZone(zone).toLocalDate()
        val first = today.plusDays((1 - days).toLong())
        return MetricDaySpan(from = DayKey.string(first), to = DayKey.string(today))
    }

    /** One point for every day of the span, oldest first. A day the backend left out has no value. */
    fun dailyPoints(points: List<MetricPoint>, span: MetricDaySpan): List<MetricPoint> {
        val first = DayKey.localDate(span.from) ?: return points
        val last = DayKey.localDate(span.to) ?: return points
        val values = HashMap<String, Double>()
        for (point in points) {
            point.value?.let { values[point.date.take(10)] = it }
        }
        val filled = mutableListOf<MetricPoint>()
        var day = first
        while (day <= last) {
            val key = DayKey.string(day)
            filled.add(MetricPoint(date = key, value = values[key]))
            day = day.plusDays(1)
        }
        return filled
    }

    /** The points that have a value, newest first. */
    fun recordedRows(points: List<MetricPoint>): List<MetricPoint> =
        points.filter { it.value != null }.sortedByDescending { it.date }

    // MARK: Chart

    /** Each bar's height as a share of the chart, the highest value filling it. A day with no value stays null, a gap. */
    fun barFractions(values: List<Double?>): List<Double?> {
        val known = values.filterNotNull()
        val top = known.maxOrNull() ?: return values.map { null }
        val low = known.min()
        val base = minOf(0.0, low)
        val height = top - base
        return values.map { value ->
            when {
                value == null -> null
                height <= 0 -> 0.0
                else -> (value - base) / height
            }
        }
    }

    // MARK: Trend words

    /** A change smaller than this reads as steady. */
    const val steadyPercent = 2.0

    fun trendDirection(percent: Double): MetricTrendDirection {
        if (abs(percent) < steadyPercent) return MetricTrendDirection.steady
        return if (percent > 0) MetricTrendDirection.up else MetricTrendDirection.down
    }

    fun trendTitle(percent: Double): String = when (trendDirection(percent)) {
        MetricTrendDirection.up -> "Trending Up"
        MetricTrendDirection.steady -> "Steady"
        MetricTrendDirection.down -> "Trending Down"
    }

    fun trendPercentText(percent: Double): String {
        val whole = roundHalfAway(percent).toInt()
        return if (whole < 0) "$whole%" else "+$whole%"
    }

    // MARK: Showing values

    /** Averages and trends mean nothing for words and yes/no values. */
    fun canAverage(format: String): Boolean = format != "bool" && format != "text"

    /** A stored value as the catalogue's `format` prints it. With no format: whole for counts and calories, else one decimal. */
    fun display(value: Double, format: String, unit: String = ""): String = when (format) {
        "int", "minutes", "percent" -> whole(value)
        "decimal1" -> fixed(value, 1)
        "decimal2" -> fixed(value, 2)
        "hoursFromMinutes" -> fixed(value / 60, 1)
        "metresAsKm" -> fixed(value / 1000, 1)
        "hhmm" -> minutesAndSeconds(value)
        "hourOfDay" -> clockTime(value)
        "bool" -> if (value != 0.0) "Yes" else "No"
        else -> if (wholeUnits.contains(unit.lowercase(Locale.ROOT))) whole(value) else fixed(value, 1)
    }

    /** The unit printed beside a value: words and yes/no values have none. */
    fun shownUnit(format: String, unit: String): String = if (canAverage(format)) unit else ""

    /** A field's latest value as text. Null when it has none. */
    fun latestText(availability: FieldAvailability, format: String, unit: String): String? {
        availability.latestNumber?.let { return display(it, format, unit) }
        return availability.latestText
    }

    /** "Nov 1" from "2026-11-01". */
    fun shortDay(key: String): String {
        val parts = key.take(10).split("-").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        if (parts.size != 3 || parts[1] < 1 || parts[1] > 12) return key
        return "${monthNames[parts[1] - 1]} ${parts[2]}"
    }

    private val wholeUnits = setOf("steps", "kcal", "cal", "min")
    private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Swift's `String(format: "%.Nf")`: the exact binary value rounded half-even, "-0.0" read as "0.0". */
    private fun fixed(value: Double, digits: Int): String {
        val text = when {
            value.isNaN() -> "nan"
            value.isInfinite() -> if (value > 0) "inf" else "-inf"
            else -> BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
                .let { if (value < 0 && !it.startsWith("-")) "-$it" else it }
        }
        val isZero = text.all { it == '-' || it == '0' || it == '.' }
        return if (isZero && text.startsWith("-")) text.drop(1) else text
    }

    private fun whole(value: Double): String {
        if (!value.isFinite() || abs(value) >= 1e15) return fixed(value, 0)
        val number = roundHalfAway(value).toLong()
        val digits = abs(number).toString()
        val grouped = StringBuilder()
        for ((index, digit) in digits.withIndex()) {
            if (index > 0 && (digits.length - index) % 3 == 0) grouped.append(',')
            grouped.append(digit)
        }
        return if (number < 0) "-$grouped" else grouped.toString()
    }

    private fun minutesAndSeconds(seconds: Double): String {
        if (!seconds.isFinite() || abs(seconds) >= 1e15) return ""
        val total = roundHalfAway(seconds).toLong()
        return String.format(Locale.ROOT, "%d:%02d", total / 60, abs(total % 60))
    }

    private fun clockTime(hour: Double): String {
        if (!hour.isFinite() || abs(hour) >= 1e12) return ""
        val minutes = roundHalfAway(hour * 60).toLong()
        return String.format(Locale.ROOT, "%d:%02d", minutes / 60, abs(minutes % 60))
    }

    // MARK: Lists

    /** True when the text holds what was typed. Nothing typed matches everything. */
    fun matches(query: String, text: String): Boolean {
        val wanted = query.trim()
        return wanted.isEmpty() || text.contains(wanted, ignoreCase = true)
    }

    /** Splits a category into rows with a latest value and the rest. */
    fun categoryRows(parameters: List<CatalogueParameter>, availability: List<FieldAvailability>): MetricCategoryRows {
        val byField = HashMap<String, FieldAvailability>()
        for (item in availability) {
            if (item.latestNumber != null || item.latestText != null) byField[item.field] = item
        }
        val available = mutableListOf<MetricAvailableRow>()
        val empty = mutableListOf<CatalogueParameter>()
        for (parameter in parameters) {
            val item = parameter.field?.let { byField[it] }
            if (item != null) available.add(MetricAvailableRow(parameter, item)) else empty.add(parameter)
        }
        return MetricCategoryRows(available, empty)
    }

    /** What typing finds. A metric listed in two sections shows once. */
    fun search(query: String, sections: List<CatalogueSection>): MetricSearchHits {
        val categories = sections.filter { matches(query, it.title) }
        val seen = HashSet<String>()
        val metrics = mutableListOf<MetricSearchHit>()
        for (section in sections) {
            for (parameter in section.parameters) {
                if (matches(query, parameter.name) && seen.add(parameter.id)) {
                    metrics.add(MetricSearchHit(section.id, parameter))
                }
            }
        }
        return MetricSearchHits(categories, metrics)
    }

    /** Swift's `rounded()`: halves go away from zero. */
    private fun roundHalfAway(value: Double): Double = if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
}
