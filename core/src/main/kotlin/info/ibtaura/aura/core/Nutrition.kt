package info.ibtaura.aura.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

// The Calorie Tracker's sums. The server stores food logs as they come and computes nothing from them, so every total
// is worked out here. A meal is a food log `{meal, name, kcal, proteinG, carbsG, fatG, time}`; the diet pattern is a
// food log `{pattern}`.

/** The four meal sections, in the order of the day. `raw` is what goes in `data.meal`. */
enum class Meal(val raw: String, val label: String) {
    breakfast("breakfast", "Breakfast"),
    lunch("lunch", "Lunch"),
    snack("snack", "Snacks"),
    dinner("dinner", "Dinner");

    companion object {
        fun of(raw: String?): Meal? = entries.firstOrNull { it.raw == raw?.lowercase() }

        /** The meal the time of day suggests: before 11 breakfast, before 15 lunch, before 18 snacks, then dinner. */
        fun at(time: LocalTime): Meal {
            val minute = time.hour * 60 + time.minute
            return when {
                minute < 11 * 60 -> breakfast
                minute < 15 * 60 -> lunch
                minute < 18 * 60 -> snack
                else -> dinner
            }
        }
    }
}

/** The diet patterns offered (the website's list). Any text the server has is shown as it is. */
val dietPatterns: List<String> =
    listOf("Mixed", "Vegetarian", "Vegan", "Pescatarian", "Low carb", "High protein", "Time-restricted")

/** One meal read from a food log. Missing numbers count as 0; a missing time falls back to the log's time. */
data class FoodEntry(
    val log: LogEntry,
    val meal: Meal,
    val name: String,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    /** Local time of day, or null when the log has neither `time` nor a time stamp. */
    val time: LocalTime?,
) {
    companion object {
        /** Null for a food log that isn't a meal (a diet pattern). */
        fun of(log: LogEntry): FoodEntry? {
            if (log.type != LogType.food) return null
            val time = clock(log.text("time")) ?: log.at?.atZone(DayKey.zone)?.toLocalTime()
            val meal = Meal.of(log.text("meal")) ?: if (log.has("kcal")) time?.let { Meal.at(it) } ?: Meal.snack else return null
            return FoodEntry(
                log = log,
                meal = meal,
                name = log.text("name")?.takeIf { it.isNotBlank() } ?: meal.label,
                kcal = log.number("kcal")?.coerceAtLeast(0.0) ?: 0.0,
                proteinG = log.number("proteinG")?.coerceAtLeast(0.0) ?: 0.0,
                carbsG = log.number("carbsG")?.coerceAtLeast(0.0) ?: 0.0,
                fatG = log.number("fatG")?.coerceAtLeast(0.0) ?: 0.0,
                time = time,
            )
        }

        /** "08:30" as a time, or null. */
        fun clock(text: String?): LocalTime? {
            val parts = text?.split(":")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
            if (parts.size < 2 || parts[0] !in 0..23 || parts[1] !in 0..59) return null
            return LocalTime.of(parts[0], parts[1])
        }
    }
}

/** A day's meals and what follows from them. */
data class NutritionDay(val date: String, val meals: List<FoodEntry>) {
    val kcal: Double get() = meals.sumOf { it.kcal }
    val proteinG: Double get() = meals.sumOf { it.proteinG }
    val carbsG: Double get() = meals.sumOf { it.carbsG }
    val fatG: Double get() = meals.sumOf { it.fatG }

    fun meals(of: Meal): List<FoodEntry> = meals.filter { it.meal == of }

    fun kcal(of: Meal): Double = meals(of).sumOf { it.kcal }

    private val timed: List<LocalTime> get() = meals.mapNotNull { it.time }.sorted()

    val firstMeal: LocalTime? get() = timed.firstOrNull()
    val lastMeal: LocalTime? get() = timed.lastOrNull()

    /** From the first meal to the last, or null with fewer than two timed meals. */
    val eatingWindow: Duration?
        get() {
            val times = timed
            if (times.size < 2) return null
            return Duration.between(times.first(), times.last())
        }

    /** Grams of protein per 100 kcal, or null without calories. */
    val proteinPer100Kcal: Double?
        get() = if (kcal <= 0.0) null else proteinG / kcal * 100.0

    /** The meal with the most calories, or null without meals. */
    val largestMeal: FoodEntry? get() = meals.maxByOrNull { it.kcal }?.takeIf { it.kcal > 0.0 }

    /** Each macro's share of the calories they give (protein and carbs 4 kcal/g, fat 9), summing to 1; null without any. */
    val macroShares: Triple<Double, Double, Double>?
        get() {
            val p = proteinG * 4
            val c = carbsG * 4
            val f = fatG * 9
            val total = p + c + f
            if (total <= 0.0) return null
            return Triple(p / total, c / total, f / total)
        }

    companion object {
        /** Meals of `date` from any logs, oldest first. Logs of other days and types are ignored. */
        fun of(date: String, logs: List<LogEntry>): NutritionDay {
            val meals = logs.filter { it.date == date }.mapNotNull { FoodEntry.of(it) }
                .sortedWith(compareBy<FoodEntry> { it.time ?: LocalTime.MAX }.thenBy { it.log.createdAt })
            return NutritionDay(date, meals)
        }
    }
}

object NutritionMaths {
    /** Calories from macros: protein and carbs 4 kcal per gram, fat 9. */
    fun kcalFromMacros(proteinG: Double, carbsG: Double, fatG: Double): Double = proteinG * 4 + carbsG * 4 + fatG * 9

    /**
     * From the last meal of the day before to the first meal of `day`, or null when either day has no timed meal or
     * the gap isn't positive.
     */
    fun overnightFast(day: NutritionDay, dayBefore: NutritionDay): Duration? {
        val first = day.firstMeal ?: return null
        val last = dayBefore.lastMeal ?: return null
        val minutes = (24 * 60 - (last.hour * 60 + last.minute)) + first.hour * 60 + first.minute
        return if (minutes > 0) Duration.ofMinutes(minutes.toLong()) else null
    }

    /** The newest diet pattern in `logs`, or null when none was logged. */
    fun latestPattern(logs: List<LogEntry>): String? =
        logs.filter { it.type == LogType.food && !it.text("pattern").isNullOrBlank() }
            .maxByOrNull { it.at ?: java.time.Instant.MIN }?.text("pattern")

    /** Calories per day for the `days` days ending on `last`, oldest first: (DayKey, kcal). */
    fun dailyKcal(logs: List<LogEntry>, last: LocalDate, days: Int): List<Pair<String, Double>> =
        (days - 1 downTo 0).map { back ->
            val key = DayKey.string(last.minusDays(back.toLong()))
            key to NutritionDay.of(key, logs).kcal
        }

    /** "11 h 20 min", "45 min". */
    fun durationText(duration: Duration): String {
        val minutes = duration.toMinutes().toInt()
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    /** Whole numbers, rounded half up. */
    fun whole(value: Double): Int = value.roundToInt()
}

/** Water and other small sums the Logging page shows. */
object LogbookMaths {
    /** Millilitres of water logged on `date`. */
    fun waterMl(logs: List<LogEntry>, date: String): Int =
        logs.filter { it.type == LogType.water && it.date == date }.sumOf { (it.number("ml") ?: 0.0).roundToInt() }

    /** The last check-in of `kind` with an answer for it, newest by time. */
    fun latest(checkins: List<Checkin>, kind: CheckinKind, date: String? = null): Checkin? =
        checkins.filter { it.kind == kind && (date == null || it.date == date) }
            .maxByOrNull { it.takenAt ?: java.time.Instant.MIN }
}
