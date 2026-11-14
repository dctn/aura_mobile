package info.ibtaura.aura.core

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NutritionTest {
    private fun food(id: String, date: String, vararg data: Pair<String, Any>): LogEntry =
        LogEntry(id, LogType.food, date, null, data.associate { (k, v) -> k to StubLogbook.primitive(v) },
            Instant.parse("2030-03-17T12:00:00Z"))

    private val day = "2030-03-17"
    private val before = "2030-03-16"
    private val logs = listOf(
        food("b", day, "meal" to "breakfast", "name" to "Oats", "kcal" to 400, "proteinG" to 20, "carbsG" to 60, "fatG" to 8, "time" to "08:00"),
        food("l", day, "meal" to "lunch", "kcal" to 600, "proteinG" to 30, "carbsG" to 70, "fatG" to 20, "time" to "13:30"),
        food("p", day, "pattern" to "Vegan"),
        food("d0", before, "meal" to "dinner", "kcal" to 700, "time" to "20:00"),
        LogEntry("w", LogType.water, day, null, mapOf("ml" to JsonPrimitive(250)), null),
    )

    @Test
    fun mealTimesGuessTheMeal() {
        assertEquals(Meal.breakfast, Meal.at(LocalTime.of(10, 59)))
        assertEquals(Meal.lunch, Meal.at(LocalTime.of(11, 0)))
        assertEquals(Meal.snack, Meal.at(LocalTime.of(15, 0)))
        assertEquals(Meal.dinner, Meal.at(LocalTime.of(18, 0)))
    }

    @Test
    fun aDaysTotals() {
        val today = NutritionDay.of(day, logs)
        assertEquals(2, today.meals.size, "The pattern and the water aren't meals")
        assertEquals(1000.0, today.kcal)
        assertEquals(50.0, today.proteinG)
        assertEquals(400.0, today.kcal(Meal.breakfast))
        assertEquals("Lunch", today.meals(Meal.lunch).single().name, "A meal without a name takes the meal's")
        assertEquals(Duration.ofMinutes(330), today.eatingWindow)
        assertEquals(5.0, today.proteinPer100Kcal)
        assertEquals("l", today.largestMeal?.log?.id)
        val shares = assertNotNull(today.macroShares)
        assertEquals(1.0, shares.first + shares.second + shares.third, 1e-9)
    }

    @Test
    fun overnightFastRunsFromYesterdaysLastMeal() {
        val fast = NutritionMaths.overnightFast(NutritionDay.of(day, logs), NutritionDay.of(before, logs))
        assertEquals(Duration.ofHours(12), fast)
        assertNull(NutritionMaths.overnightFast(NutritionDay.of(day, logs), NutritionDay.of("2030-03-15", logs)))
    }

    @Test
    fun aWeekOfCalories() {
        val week = NutritionMaths.dailyKcal(logs, LocalDate.of(2030, 3, 17), 3)
        assertEquals(listOf("2030-03-15" to 0.0, "2030-03-16" to 700.0, "2030-03-17" to 1000.0), week)
    }

    @Test
    fun patternsAndText() {
        assertEquals("Vegan", NutritionMaths.latestPattern(logs))
        assertEquals("11 h 20 min", NutritionMaths.durationText(Duration.ofMinutes(680)))
        assertEquals("45 min", NutritionMaths.durationText(Duration.ofMinutes(45)))
        assertEquals("2 h", NutritionMaths.durationText(Duration.ofMinutes(120)))
        assertEquals(500.0, NutritionMaths.kcalFromMacros(30.0, 50.0, 20.0))
    }

    @Test
    fun insomniaBands() {
        assertEquals("No insomnia", InsomniaScale.band(7))
        assertEquals("Below the line", InsomniaScale.band(8))
        assertEquals("Moderate insomnia", InsomniaScale.band(21))
        assertEquals("Severe insomnia", InsomniaScale.band(22))
    }
}
