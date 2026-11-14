package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// StubApi's logbook: seeded relative to the clock, idempotent on id, and refusing what the server refuses.

class StubLogbookTest {
    private val now: Instant = Instant.parse("2030-03-17T15:00:00Z")
    private val api = StubApi(now = { now })
    private val today: LocalDate = now.atZone(DayKey.zone).toLocalDate()
    private val todayKey = DayKey.string(today)
    private val weekAgo = DayKey.string(today.minusDays(6))

    @Test
    fun aWeekOfSeededEntries() = runBlocking<Unit> {
        val logs = api.logs(weekAgo, todayKey)
        assertTrue(LogbookMaths.waterMl(logs, todayKey) >= 500, "Today has water")
        val food = api.logs(weekAgo, todayKey, LogType.food)
        assertTrue(food.all { it.type == LogType.food })
        val days = NutritionMaths.dailyKcal(food, today, 7)
        assertTrue(days.all { it.second > 0 }, "Every day of the week has meals: $days")
        assertEquals("Mixed", NutritionMaths.latestPattern(food))
        assertTrue(api.checkins(weekAgo, todayKey, CheckinKind.mood).isNotEmpty())
        assertEquals(2000, api.waterGoalMl())
    }

    @Test
    fun aResentLogAnswersWithTheStoredOne() = runBlocking<Unit> {
        val first = api.addLog(LogType.water, todayKey, now, mapOf("ml" to JsonPrimitive(250)), id = "w")
        val again = api.addLog(LogType.water, todayKey, now, mapOf("ml" to JsonPrimitive(999)), id = "w")
        assertEquals(first, again)
        assertEquals(1, api.logs(todayKey, todayKey, LogType.water).count { it.id == "w" })
    }

    @Test
    fun aCheckinWithoutItsAnswerIsRefused() = runBlocking<Unit> {
        val error = netCaught { api.addCheckin(CheckinKind.connection, now, mapOf("mood" to JsonPrimitive(3))) }
        assertEquals("invalid_request", (error as? AuraError)?.serverCode)
    }

    @Test
    fun theInsomniaCheckKeepsItsTotal() = runBlocking<Unit> {
        val items = JsonArray(listOf(2, 1, 0, 3, 1, 2, 1).map { JsonPrimitive(it) })
        val saved = api.addCheckin(CheckinKind.insomnia, now, mapOf("insomnia" to items))
        assertEquals(10, saved.insomniaScore)
        assertTrue(api.checkins(todayKey, todayKey, CheckinKind.insomnia).any { it.id == saved.id })
    }

    @Test
    fun settingsMergeAndRemove() = runBlocking<Unit> {
        api.putMeSettings(mapOf("nutrition.calorieGoal" to JsonPrimitive(2200), "units" to JsonPrimitive("metric")))
        api.putMeSettings(mapOf("units" to null))
        val settings = api.meSettings()
        assertEquals(JsonPrimitive(2200), settings["nutrition.calorieGoal"])
        assertNull(settings["units"])
    }
}
