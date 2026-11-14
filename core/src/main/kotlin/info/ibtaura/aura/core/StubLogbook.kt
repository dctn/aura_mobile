package info.ibtaura.aura.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// The logbook for StubApi: a week of believable entries made relative to the clock, so CI screenshots always show a
// filled Logging page and Calorie Tracker. Behaves as the server does: a resent id answers with the stored entry,
// check-ins need one of their kind's answers, and nothing can be edited or deleted.

internal class StubLogbook(private val now: AuraNow) {
    private val mutex = Mutex()
    private var seeded = false
    private val logs = LinkedHashMap<String, LogEntry>()
    private val checkins = LinkedHashMap<String, Checkin>()
    private val settings = LinkedHashMap<String, JsonElement>()

    /** The water goal the stub profile has (healthGoals.waterGoalMl). */
    val waterGoal = 2000

    private fun at(day: LocalDate, hour: Int, minute: Int): Instant =
        day.atTime(LocalTime.of(hour, minute)).atZone(DayKey.zone).toInstant()

    private fun seedIfNeeded() {
        if (seeded) return
        seeded = true
        val today = now().atZone(DayKey.zone).toLocalDate()
        fun log(id: String, type: LogType, day: LocalDate, hour: Int, minute: Int, vararg data: Pair<String, Any>) {
            val time = at(day, hour, minute)
            logs[id] = LogEntry(id, type, DayKey.string(day), time, data.associate { (k, v) -> k to primitive(v) }, time)
        }
        fun meal(id: String, day: LocalDate, meal: Meal, hour: Int, minute: Int, name: String, kcal: Int, p: Int, c: Int, f: Int) =
            log(id, LogType.food, day, hour, minute, "meal" to meal.raw, "name" to name, "kcal" to kcal, "proteinG" to p,
                "carbsG" to c, "fatG" to f, "time" to String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute))

        // Today: two glasses of water, breakfast and lunch, a mood log.
        log("stub-water-t1", LogType.water, today, 8, 15, "ml" to 250)
        log("stub-water-t2", LogType.water, today, 11, 0, "ml" to 500)
        meal("stub-food-t1", today, Meal.breakfast, 8, 10, "Oats with berries", 420, 14, 68, 9)
        meal("stub-food-t2", today, Meal.lunch, 13, 5, "Chicken rice bowl", 640, 42, 72, 16)
        log("stub-mood-t", LogType.mood, today, 9, 30, "energy" to 4)
        log("stub-pattern", LogType.food, today.minusDays(5), 9, 0, "pattern" to "Mixed")

        // The six days before: three meals each, a little different every day.
        val names = listOf(
            Triple("Greek yoghurt", "Lentil soup", "Salmon and greens"),
            Triple("Toast and eggs", "Paneer wrap", "Pasta with tomato"),
            Triple("Banana smoothie", "Quinoa salad", "Dal and rice"),
        )
        for (back in 1L..6L) {
            val day = today.minusDays(back)
            val (b, l, d) = names[(back % 3).toInt()]
            val shift = (back * 37 % 120).toInt()
            meal("stub-food-$back-b", day, Meal.breakfast, 8, (back * 7 % 50).toInt(), b, 360 + shift, 18, 45, 11)
            meal("stub-food-$back-l", day, Meal.lunch, 13, 15, l, 580 + shift, 32, 66, 18)
            meal("stub-food-$back-d", day, Meal.dinner, 19, 40, d, 690 - shift / 2, 38, 70, 24)
            if (back % 2 == 0L) meal("stub-food-$back-s", day, Meal.snack, 16, 30, "Apple and almonds", 210, 6, 22, 12)
            log("stub-water-$back", LogType.water, day, 10, 0, "ml" to (1250 + back * 125).toInt())
        }
        log("stub-weight", LogType.measurement, today.minusDays(3), 7, 30, "weightKg" to 68.4, "bodyFatPct" to 21.5)
        log("stub-strength", LogType.strength, today.minusDays(1), 18, 0, "minutes" to 40, "group" to "Legs")
        log("stub-talks", LogType.custom, today.minusDays(1), 20, 0, "meaningfulConversations" to 3)

        fun checkin(id: String, kind: CheckinKind, day: LocalDate, hour: Int, change: (Checkin) -> Checkin) {
            checkins[id] = change(Checkin(id, kind, at(day, hour, 0), DayKey.string(day)))
        }
        checkin("stub-checkin-mood", CheckinKind.mood, today, 9) { it.copy(mood = 4) }
        checkin("stub-checkin-conn", CheckinKind.connection, today.minusDays(1), 21) { it.copy(connection = 3) }
        checkin("stub-checkin-isi", CheckinKind.insomnia, today.minusDays(4), 8) { it.copy(insomniaScore = 9) }
    }

    suspend fun logs(from: String, to: String, type: LogType?): List<LogEntry> = mutex.withLock {
        seedIfNeeded()
        logs.values.filter { it.date in from..to && (type == null || it.type == type) }.sortedBy { it.at }
    }

    suspend fun addLog(id: String, type: LogType, date: String, takenAt: Instant?, data: Map<String, JsonElement>): LogEntry =
        mutex.withLock {
            seedIfNeeded()
            if (DayKey.localDate(date) == null) throw AuraError.Server("invalid_request", "date must be yyyy-MM-dd.", 400)
            logs[id]?.let { return@withLock it }
            val entry = LogEntry(id, type, date, takenAt, LiveLogbook.cleaned(data), now())
            logs[id] = entry
            entry
        }

    suspend fun checkins(from: String, to: String, kind: CheckinKind?): List<Checkin> = mutex.withLock {
        seedIfNeeded()
        checkins.values.filter { it.date in from..to && (kind == null || it.kind == kind) }.sortedBy { it.takenAt }
    }

    suspend fun addCheckin(id: String, kind: CheckinKind, takenAt: Instant, answers: Map<String, JsonElement>): Checkin =
        mutex.withLock {
            seedIfNeeded()
            checkins[id]?.let { return@withLock it }
            val given = answers.filterValues { it !is JsonNull }
            if (kind.needs.none { it in given }) {
                throw AuraError.Server("invalid_request", "A ${kind.raw} check-in needs ${kind.needs.joinToString(" or ")}.", 400)
            }
            fun int(key: String): Int? = (given[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            val insomnia = (given["insomnia"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            val entry = Checkin(
                id = id, kind = kind, takenAt = takenAt, date = DayKey.string(takenAt),
                alertness = int("alertness"), stress = int("stress"), exhaustion = int("exhaustion"),
                insomniaScore = insomnia?.sum(), effort = int("effort"), mood = int("mood"), connection = int("connection"),
            )
            checkins[id] = entry
            entry
        }

    suspend fun meSettings(): Map<String, JsonElement> = mutex.withLock { settings.toMap() }

    suspend fun putMeSettings(changes: Map<String, JsonElement?>) = mutex.withLock {
        for ((key, value) in changes) {
            if (value == null || value is JsonNull) settings.remove(key) else settings[key] = value
        }
    }

    companion object {
        fun primitive(value: Any): JsonElement = when (value) {
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }
}
