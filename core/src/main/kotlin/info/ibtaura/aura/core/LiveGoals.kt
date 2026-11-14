package info.ibtaura.aura.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import java.util.UUID

// Goals and habits on the live backend: Identity's healthGoals (GET /v1/me, PUT /v1/me/profile), Health Data's goal
// columns and water logs, and the Habit service. LiveApi's `// MARK: Goals` block calls this.

// MARK: Wire: GET /v1/me as Goals reads it

@Serializable
internal data class WireGoalsMe(
    /** Lossy `WireGoalValues`: one the app can't read counts as none. */
    val healthGoals: JsonElement? = null,
    val settings: JsonElement? = null,
    val version: Int,
) {
    val model: GoalsProfile
        get() = GoalsProfile(
            healthGoals = healthGoals.lossy(WireGoalValues.serializer())?.model,
            settings = (settings as? JsonObject)?.toMap() ?: emptyMap(),
            version = version,
        )
}

@Serializable
internal data class WireGoalValues(
    val stepsPerDay: JsonElement? = null,
    val activeKcalPerDay: JsonElement? = null,
    val sleepNeedMinutes: JsonElement? = null,
    val standHoursPerDay: JsonElement? = null,
    val bedtime: String? = null,
    val wakeTime: String? = null,
    val waterGoalMl: JsonElement? = null,
) {
    val model: HealthGoals
        get() = HealthGoals(
            stepsPerDay = WireValue.number(stepsPerDay)?.toInt(),
            activeKcalPerDay = WireValue.number(activeKcalPerDay)?.toInt(),
            sleepNeedMinutes = WireValue.number(sleepNeedMinutes)?.toInt(),
            standHoursPerDay = WireValue.number(standHoursPerDay)?.toInt(),
            bedtime = bedtime?.takeIf { it.isNotEmpty() },
            wakeTime = wakeTime?.takeIf { it.isNotEmpty() },
            waterGoalMl = WireValue.number(waterGoalMl)?.toInt(),
        )
}

// MARK: Wire: goal progress

@Serializable
internal data class WireGoalDay(val date: String, val metrics: JsonObject? = null) {
    fun number(key: String): Double? = WireValue.number(metrics?.get(key))
}

@Serializable
internal data class WireGoalDayPage(
    val days: List<WireGoalDay>? = null,
    override val cursor: String? = null,
) : CursorPage<WireGoalDay> {
    override val pageItems: List<WireGoalDay> get() = days.orEmpty()
}

@Serializable
internal data class WireWaterLog(val type: String? = null, val date: String? = null, val data: JsonObject? = null) {
    val ml: Double? get() = WireValue.number(data?.get("ml"))
}

@Serializable
internal data class WireWaterLogs(val logs: List<JsonElement>? = null) {
    val items: List<WireWaterLog> get() = logs.lossyItems(WireWaterLog.serializer())
}

// MARK: Wire: habits

@Serializable
internal data class WireStreak(val current: JsonElement? = null, val best: JsonElement? = null, val aliveThrough: String? = null) {
    val model: HabitStreak
        get() = HabitStreak(current = wireInt(current) ?: 0, best = wireInt(best) ?: 0,
            aliveThrough = aliveThrough?.takeIf { it.isNotEmpty() })
}

@Serializable
internal data class WireReminder(val enabled: Boolean? = null, val time: String? = null)

@Serializable
internal data class WireHabitGoal(val enabled: Boolean? = null, val targetCount: JsonElement? = null, val deadline: String? = null)

@Serializable
internal data class WireHabit(
    val id: String,
    val name: String? = null,
    val cue: String? = null,
    val icon: String? = null,
    val colorHex: String? = null,
    val frequency: String? = null,
    val specificDays: List<JsonElement>? = null,
    val timesPerWeek: JsonElement? = null,
    val weeklyDay: JsonElement? = null,
    val targetType: String? = null,
    val targetCount: JsonElement? = null,
    val targetMinutes: JsonElement? = null,
    val targetUnit: String? = null,
    val reminder: JsonElement? = null,
    val goal: JsonElement? = null,
    val isPaused: Boolean? = null,
    val isArchived: Boolean? = null,
    val isNegative: Boolean? = null,
    val showStreak: Boolean? = null,
    val sortOrder: JsonElement? = null,
    val streak: JsonElement? = null,
    val version: JsonElement? = null,
) {
    val model: Habit
        get() {
            val reminder = reminder.lossy(WireReminder.serializer())
            val goal = goal.lossy(WireHabitGoal.serializer())
            return Habit(
                id = id,
                name = name ?: "",
                cue = cue ?: "",
                icon = icon ?: "star.fill",
                colorHex = colorHex ?: "34C759",
                frequency = frequency ?: HabitFrequency.daily,
                specificDays = specificDays.orEmpty().mapNotNull { wireInt(it) },
                timesPerWeek = wireInt(timesPerWeek) ?: 3,
                weeklyDay = wireInt(weeklyDay) ?: 0,
                targetType = targetType ?: HabitTargetType.yesNo,
                targetCount = wireInt(targetCount) ?: 1,
                targetMinutes = wireInt(targetMinutes) ?: 20,
                targetUnit = targetUnit ?: "",
                reminder = HabitReminder(reminder?.enabled ?: false, reminder?.time ?: "08:00"),
                goal = HabitGoal(goal?.enabled ?: false, wireInt(goal?.targetCount) ?: 30, goal?.deadline),
                isPaused = isPaused ?: false,
                isArchived = isArchived ?: false,
                isNegative = isNegative ?: false,
                showStreak = showStreak ?: true,
                sortOrder = wireInt(sortOrder) ?: 0,
                streak = streak.lossy(WireStreak.serializer())?.model ?: HabitStreak.none,
                version = wireInt(version),
            )
        }
}

@Serializable
internal data class WireHabitPage(
    val habits: List<JsonElement>? = null,
    override val cursor: String? = null,
) : CursorPage<WireHabit> {
    override val pageItems: List<WireHabit> get() = habits.lossyItems(WireHabit.serializer())
}

@Serializable
internal data class WireHabitAnswer(val habit: WireHabit)

@Serializable
internal data class WireHabitLog(
    val id: String,
    val habitId: String? = null,
    val date: String? = null,
    val count: JsonElement? = null,
    val minutes: JsonElement? = null,
) {
    fun model(fallbackHabit: String = ""): HabitLog = HabitLog(id = id, habitId = habitId ?: fallbackHabit, date = date ?: "",
        count = wireInt(count) ?: 1, minutes = wireInt(minutes) ?: 0)
}

@Serializable
internal data class WireHabitLogPage(
    val logs: List<JsonElement>? = null,
    override val cursor: String? = null,
) : CursorPage<WireHabitLog> {
    override val pageItems: List<WireHabitLog> get() = logs.lossyItems(WireHabitLog.serializer())
}

@Serializable
internal data class WireHabitDay(val count: JsonElement? = null, val kept: Boolean? = null)

@Serializable
internal data class WireHabitLogAnswer(
    val log: WireHabitLog,
    val streak: JsonElement? = null,
    val day: JsonElement? = null,
    val created: Boolean? = null,
)

@Serializable
internal data class WireMilestone(val count: JsonElement? = null, val reachedOn: String? = null)

@Serializable
internal data class WireHabitStats(
    val habitId: String? = null,
    val streak: JsonElement? = null,
    val completionRate30d: JsonElement? = null,
    val totalLogs: JsonElement? = null,
    val milestones: List<JsonElement>? = null,
)

/** The Goals routes. `client` is LiveApi's, so tokens, retries and the call limit are shared. */
internal class LiveGoals(private val client: ServiceClient, private val now: AuraNow) {
    companion object {
        /** Most pages the habit lists are followed for. */
        private const val maxPages = 10

        private val badResponse: AuraError
            get() = AuraError.Server("bad_response", "Aura sent an answer this app can't read.", 200)

        /** What a refused healthGoals write becomes (dev Identity refused the field until it was redeployed). */
        const val goalsRefusedCode = "health_goals_refused"

        private fun <T> decode(serializer: KSerializer<T>, text: String): T =
            try {
                wireJson.decodeFromString(serializer, text)
            } catch (_: Exception) {
                throw badResponse
            }

        private fun esc(text: String): String = URLEscape.component(text)

        private fun newId(): String = UUID.randomUUID().toString().lowercase(Locale.ROOT)
    }

    private fun today(): String = DayKey.string(now())

    // MARK: Identity

    suspend fun profile(): GoalsProfile =
        decode(WireGoalsMe.serializer(), client.send(ServiceName.identity, "GET", "/v1/me")).model

    /**
     * PUT /v1/me/profile {healthGoals: changes} with If-Match "v<version>". A write in between (412) reads the profile
     * again and sends once more: goals merge field by field, so nothing else is lost. A 400 that names healthGoals, or
     * any 400 invalid_request here, is Identity not taking the field yet: `health_goals_refused`.
     */
    suspend fun putHealthGoals(changes: Map<String, JsonElement?>, version: Int): GoalsProfile {
        val goals = JsonObject(changes.mapValues { it.value ?: JsonNull })
        val body = JsonObject(mapOf("healthGoals" to goals)).toString()
        var current = version
        var retried = false
        while (true) {
            try {
                val text = client.send(ServiceName.identity, "PUT", "/v1/me/profile", body = body,
                    headers = mapOf("If-Match" to "\"v$current\""))
                return decode(WireGoalsMe.serializer(), text).model
            } catch (error: AuraError.Server) {
                if (error.status == 412 && !retried) {
                    retried = true
                    current = profile().version
                    continue
                }
                if (error.status == 400 && (error.code == "invalid_request" || error.message.contains("healthGoals"))) {
                    throw AuraError.Server(goalsRefusedCode,
                        "Aura's profile service isn't taking goals yet. Nothing was changed.", 400)
                }
                throw error
            }
        }
    }

    // MARK: Health Data

    /** Today's goal columns from GET /v1/metrics/daily. Null fields when the day isn't computed yet. */
    suspend fun metricsToday(): GoalProgress {
        val day = today()
        val fields = "stepCount,activeKcal,standHours,stepGoalProgressPct,activeKcalGoalProgressPct,standHoursGoalProgressPct"
        val query = mapOf("from" to day, "to" to day, "fields" to fields)
        val text = client.send(ServiceName.healthData, "GET", "/v1/metrics/daily", query = query)
        val row = decode(WireGoalDayPage.serializer(), text).pageItems.lastOrNull { it.date.take(10) == day }
        return GoalProgress(
            date = day,
            steps = row?.number("stepCount"),
            stepsPct = row?.number("stepGoalProgressPct"),
            activeKcal = row?.number("activeKcal"),
            activeKcalPct = row?.number("activeKcalGoalProgressPct"),
            standHours = row?.number("standHours"),
            standHoursPct = row?.number("standHoursGoalProgressPct"),
        )
    }

    /** The sum of today's water logs' ml (GET /v1/logs?type=water). Zero with none. */
    suspend fun waterToday(): Double {
        val day = today()
        val text = client.send(ServiceName.healthData, "GET", "/v1/logs",
            query = mapOf("from" to day, "to" to day, "type" to "water"))
        return decode(WireWaterLogs.serializer(), text).items.filter { it.type == null || it.type == "water" }
            .sumOf { it.ml ?: 0.0 }
    }

    // MARK: Habit service

    suspend fun habits(includeArchived: Boolean): List<Habit> {
        val habits = mutableListOf<Habit>()
        var cursor: String? = null
        var pages = 0
        do {
            val query = mutableMapOf<String, String>()
            if (includeArchived) query["includeArchived"] = "true"
            cursor?.let { query["cursor"] = it }
            val page = decode(WireHabitPage.serializer(), client.send(ServiceName.habit, "GET", "/v1/habits", query = query))
            habits.addAll(page.pageItems.map { it.model })
            pages += 1
            cursor = page.cursor?.takeIf { it.isNotEmpty() }
        } while (cursor != null && pages < maxPages)
        return habits.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase(Locale.ROOT) }))
    }

    suspend fun createHabit(draft: HabitDraft): Habit {
        val text = client.send(ServiceName.habit, "POST", "/v1/habits", body = draft.createBody(newId()).toString())
        return decode(WireHabitAnswer.serializer(), text).habit.model
    }

    /** Sends only what changed, with the habit's version (read first when the habit has none). Nothing changed: no call. */
    suspend fun updateHabit(habit: Habit, to: HabitDraft): Habit {
        val changes = to.changes(habit)
        if (changes.isEmpty()) return habit
        val path = "/v1/habits/${esc(habit.id)}"
        val version = habit.version
            ?: decode(WireHabitAnswer.serializer(), client.send(ServiceName.habit, "GET", path)).habit.model.version
            ?: throw badResponse
        val text = client.send(ServiceName.habit, "PATCH", path, body = changes.toString(),
            headers = mapOf("If-Match" to "\"v$version\""))
        return decode(WireHabitAnswer.serializer(), text).habit.model
    }

    suspend fun deleteHabit(id: String) {
        client.send(ServiceName.habit, "DELETE", "/v1/habits/${esc(id)}")
    }

    /** Errors come through as Habit sends them: 409 habit_paused, 400 date_in_future, 400 date_too_old. */
    suspend fun logHabit(habitId: String, date: String, count: Int, minutes: Int): HabitLogResult {
        val body = JsonObject(mapOf(
            "id" to JsonPrimitive(newId()),
            "date" to JsonPrimitive(date),
            "count" to JsonPrimitive(count.coerceIn(1, 1000)),
            "minutes" to JsonPrimitive(minutes.coerceIn(0, 1440)),
            "source" to JsonPrimitive("phone"),
        ))
        val text = client.send(ServiceName.habit, "POST", "/v1/habits/${esc(habitId)}/logs", body = body.toString())
        val answer = decode(WireHabitLogAnswer.serializer(), text)
        val day = answer.day.lossy(WireHabitDay.serializer())
        val log = answer.log.model(habitId)
        return HabitLogResult(
            log = log,
            streak = answer.streak.lossy(WireStreak.serializer())?.model ?: HabitStreak.none,
            dayCount = wireInt(day?.count) ?: log.count,
            dayKept = day?.kept ?: true,
            created = answer.created ?: true,
        )
    }

    suspend fun deleteHabitLog(habitId: String, logId: String) {
        client.send(ServiceName.habit, "DELETE", "/v1/habits/${esc(habitId)}/logs/${esc(logId)}")
    }

    /** Every check-off from `from` to `to` (DayKeys, at most 400 days), paged. */
    suspend fun habitLogs(from: String, to: String): List<HabitLog> {
        val logs = mutableListOf<HabitLog>()
        var cursor: String? = null
        var pages = 0
        do {
            val query = mutableMapOf("from" to from, "to" to to)
            cursor?.let { query["cursor"] = it }
            val page = decode(WireHabitLogPage.serializer(), client.send(ServiceName.habit, "GET", "/v1/habit-logs", query = query))
            logs.addAll(page.pageItems.map { it.model() })
            pages += 1
            cursor = page.cursor?.takeIf { it.isNotEmpty() }
        } while (cursor != null && pages < maxPages)
        return logs
    }

    suspend fun habitStats(id: String): HabitStats {
        val text = client.send(ServiceName.habit, "GET", "/v1/habits/${esc(id)}/stats")
        val stats = decode(WireHabitStats.serializer(), text)
        return HabitStats(
            habitId = stats.habitId ?: id,
            streak = stats.streak.lossy(WireStreak.serializer())?.model ?: HabitStreak.none,
            completionRate30d = WireValue.number(stats.completionRate30d) ?: 0.0,
            totalLogs = wireInt(stats.totalLogs) ?: 0,
            milestones = stats.milestones.lossyItems(WireMilestone.serializer()).mapNotNull { item ->
                wireInt(item.count)?.let { HabitMilestone(it, item.reachedOn) }
            },
        )
    }
}

/** A whole number from JSON, or null when it isn't one (text, null, missing). */
private fun wireInt(value: JsonElement?): Int? = WireWhole.from(WireValue.number(value))
