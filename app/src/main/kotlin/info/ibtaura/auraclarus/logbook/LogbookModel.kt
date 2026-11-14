package info.ibtaura.auraclarus.logbook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.Checkin
import info.ibtaura.aura.core.CheckinKind
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.LogEntry
import info.ibtaura.aura.core.LogIds
import info.ibtaura.aura.core.LogType
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.LocalDate

/**
 * What the Logging page, its category pages and the Calorie Tracker share: the last [days] days of logs and
 * check-ins, the water and calorie goals, and optimistic writes. A write shows at once and is taken back with a
 * message when the server refuses it. Entries written here are kept through a reload, so one the server's list
 * doesn't carry yet doesn't blink out.
 */
@Stable
internal class LogbookModel(private val app: AppState) {
    var logs by mutableStateOf<List<LogEntry>>(emptyList())
        private set
    var checkins by mutableStateOf<List<Checkin>>(emptyList())
        private set

    /** healthGoals.waterGoalMl, or 2,000 ml when it isn't set. */
    var waterGoal by mutableIntStateOf(DEFAULT_WATER_GOAL)
        private set

    /** The setting `nutrition.calorieGoal`, or 2,000 kcal when it isn't set. */
    var calorieGoal by mutableIntStateOf(DEFAULT_CALORIE_GOAL)
        private set

    var loaded by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var settingsLoaded by mutableStateOf(false)
        private set

    /** Entries written on this page, by id, kept through a reload until the server lists them. */
    private val written = LinkedHashMap<String, LogEntry>()
    private val writtenCheckins = LinkedHashMap<String, Checkin>()

    val today: LocalDate get() = LocalDate.now(DayKey.zone)
    val todayKey: String get() = DayKey.string(today)

    /**
     * Loads the logs (and check-ins and the water goal when asked) of the last [days] days. A failed load keeps what
     * was there and sets `failed`. Calorie Tracker needs 8 days: 7 bars and the night before the first.
     */
    suspend fun load(withCheckins: Boolean = true, withWaterGoal: Boolean = true, days: Long = DAYS) {
        val from = DayKey.string(today.minusDays(days - 1))
        val to = todayKey
        try {
            coroutineScope {
                val logsCall = async { app.api.logs(from, to) }
                val checkinsCall = if (withCheckins) async { app.api.checkins(from, to) } else null
                val goalCall = if (withWaterGoal) async { runCatching { app.api.waterGoalMl() }.getOrNull() } else null
                val loadedLogs = logsCall.await()
                val loadedCheckins = checkinsCall?.await()
                val goal = goalCall?.await()
                logs = merge(loadedLogs, written) { it.id }
                if (loadedCheckins != null) checkins = merge(loadedCheckins, writtenCheckins) { it.id }
                if (goal != null) waterGoal = goal
            }
            loaded = true
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = true
        }
    }

    /** Reads `nutrition.calorieGoal`. A failure keeps the default and is quiet: the page still works. */
    suspend fun loadSettings() {
        try {
            val settings = app.api.meSettings()
            goalFrom(settings[CALORIE_GOAL_KEY])?.let { calorieGoal = it }
            settingsLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
        }
    }

    /** Saves a new calorie goal at once; the old one comes back with a message when the server refuses. */
    suspend fun saveCalorieGoal(goal: Int): String? {
        val before = calorieGoal
        calorieGoal = goal
        return try {
            app.api.putMeSettings(mapOf(CALORIE_GOAL_KEY to JsonPrimitive(goal)))
            app.dataVersion += 1
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            calorieGoal = before
            app.note(e)
            message(e)
        }
    }

    /**
     * Adds a log at once and sends it. Returns null when saved, else the words to show (the entry is taken back).
     * `date` defaults to the local day of `takenAt`.
     */
    suspend fun addLog(
        type: LogType,
        data: Map<String, JsonElement>,
        takenAt: Instant = Instant.now(),
        date: String = DayKey.string(takenAt),
    ): String? {
        val id = LogIds.new()
        val local = LogEntry(id, type, date, takenAt, data, Instant.now())
        written[id] = local
        logs = logs + local
        return try {
            val saved = app.api.addLog(type, date, takenAt, data, id = id)
            written[id] = saved
            logs = logs.map { if (it.id == id) saved else it }
            app.dataVersion += 1
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            written.remove(id)
            logs = logs.filterNot { it.id == id }
            app.note(e)
            message(e)
        }
    }

    /** Adds a check-in at once and sends it, as [addLog]. */
    suspend fun addCheckin(kind: CheckinKind, answers: Map<String, JsonElement>, takenAt: Instant = Instant.now()): String? {
        val id = LogIds.new()
        val local = localCheckin(id, kind, takenAt, answers)
        writtenCheckins[id] = local
        checkins = checkins + local
        return try {
            val saved = app.api.addCheckin(kind, takenAt, answers, id = id)
            writtenCheckins[id] = saved
            checkins = checkins.map { if (it.id == id) saved else it }
            app.dataVersion += 1
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            writtenCheckins.remove(id)
            checkins = checkins.filterNot { it.id == id }
            app.note(e)
            message(e)
        }
    }

    companion object {
        const val DAYS = 8L
        const val DEFAULT_WATER_GOAL = 2000
        const val DEFAULT_CALORIE_GOAL = 2000
        const val CALORIE_GOAL_KEY = "nutrition.calorieGoal"
        val calorieGoalRange = 1000..5000

        private fun <T> merge(loaded: List<T>, written: Map<String, T>, id: (T) -> String): List<T> {
            val ids = loaded.map(id).toSet()
            return loaded + written.values.filter { id(it) !in ids }
        }

        private fun goalFrom(value: JsonElement?): Int? {
            val primitive = value as? JsonPrimitive ?: return null
            val number = primitive.doubleOrNull ?: primitive.content.toDoubleOrNull() ?: return null
            return Math.round(number).toInt().takeIf { it in calorieGoalRange }
        }

        private fun localCheckin(id: String, kind: CheckinKind, takenAt: Instant, answers: Map<String, JsonElement>): Checkin {
            fun int(key: String): Int? = (answers[key] as? JsonPrimitive)?.intOrNull
            val insomnia = (answers["insomnia"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            return Checkin(
                id = id, kind = kind, takenAt = takenAt, date = DayKey.string(takenAt), alertness = int("alertness"),
                stress = int("stress"), exhaustion = int("exhaustion"), insomniaScore = insomnia?.sum(),
                effort = int("effort"), mood = int("mood"), connection = int("connection"),
            )
        }

        /** A refusal in plain words. Consent and sign-out are handled by `app.note`. */
        fun message(error: Throwable): String = when (error) {
            AuraError.Offline -> "You're offline. Try again when you're connected."
            AuraError.ConsentRequired -> "Aura needs your consent first."
            is AuraError.Server -> when (error.status) {
                400 -> "Aura didn't take that entry."
                else -> "Couldn't save. Try again."
            }
            else -> "Couldn't save. Try again."
        }
    }
}
