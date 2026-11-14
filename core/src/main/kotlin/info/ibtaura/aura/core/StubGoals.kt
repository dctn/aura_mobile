package info.ibtaura.aura.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import java.util.UUID

/**
 * Goals, habits and settings without a network, for UI tests and CI. Seeded with believable numbers: a part-done day,
 * three habits (one already ticked today) and metric units. StubApi's `// MARK: Goals` block calls this.
 */
internal class StubGoals(private val now: AuraNow) {
    private val mutex = Mutex()
    private var version = 3
    private var goals = HealthGoals(
        stepsPerDay = 8000, activeKcalPerDay = 500, sleepNeedMinutes = 480, standHoursPerDay = 12,
        bedtime = "23:00", wakeTime = "07:00", waterGoalMl = 2000,
    )
    private val settings = linkedMapOf<String, JsonElement>(
        GoalsProfile.UNITS_KEY to JsonPrimitive("metric"),
        GoalsProfile.FOCUS_GOAL_KEY to JsonPrimitive(120),
    )
    private val habits = LinkedHashMap<String, Habit>()
    private val logs = LinkedHashMap<String, HabitLog>()

    private fun today(): String = DayKey.string(now())

    private fun day(offset: Long): String = DayKey.string(now().atZone(DayKey.zone).toLocalDate().plusDays(offset))

    init {
        val seed = listOf(
            Habit(id = "h-water", name = "Drink a glass of water", cue = "After I wake up", icon = "drop.fill",
                colorHex = "0088FF", streak = HabitStreak(5, 12, null), version = 1, sortOrder = 0),
            Habit(id = "h-walk", name = "Walk after lunch", cue = "After I finish lunch", icon = "figure.walk",
                colorHex = "34C759", targetType = HabitTargetType.minutes, targetMinutes = 15,
                goal = HabitGoal(true, 30, null), streak = HabitStreak(2, 9, null), version = 1, sortOrder = 1),
            Habit(id = "h-read", name = "Read 10 pages", icon = "star.fill", colorHex = "FF9F0A",
                frequency = HabitFrequency.specificDays, specificDays = listOf(0, 2, 4),
                streak = HabitStreak(0, 4, null), version = 1, sortOrder = 2),
        )
        seed.forEach { habits[it.id] = it }
        val ticked = HabitLog(id = "l-water-today", habitId = "h-water", date = today())
        logs[ticked.id] = ticked
        for (offset in 1L..11L) {
            val log = HabitLog(id = "l-walk-$offset", habitId = "h-walk", date = day(-offset), minutes = 15)
            logs[log.id] = log
        }
    }

    suspend fun profile(): GoalsProfile = mutex.withLock { GoalsProfile(goals, settings.toMap(), version) }

    suspend fun putHealthGoals(changes: Map<String, JsonElement?>, version: Int): GoalsProfile = mutex.withLock {
        if (version != this.version) throw AuraError.Server("version_mismatch", "The profile changed since it was read.", 412)
        var next = goals
        for ((key, value) in changes) {
            val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            next = when (key) {
                HealthGoals.STEPS -> next.copy(stepsPerDay = number)
                HealthGoals.ACTIVE_KCAL -> next.copy(activeKcalPerDay = number)
                HealthGoals.SLEEP_NEED -> next.copy(sleepNeedMinutes = number)
                HealthGoals.STAND_HOURS -> next.copy(standHoursPerDay = number)
                HealthGoals.BEDTIME -> next.copy(bedtime = text)
                HealthGoals.WAKE_TIME -> next.copy(wakeTime = text)
                HealthGoals.WATER_ML -> next.copy(waterGoalMl = number)
                else -> throw AuraError.Server("invalid_request", "Unrecognized key: $key", 400)
            }
        }
        goals = next
        this.version += 1
        GoalsProfile(goals, settings.toMap(), this.version)
    }

    suspend fun settings(): Map<String, JsonElement> = mutex.withLock { settings.toMap() }

    suspend fun putSettings(changes: Map<String, JsonElement?>) = mutex.withLock {
        if (changes.isEmpty()) throw AuraError.Server("invalid_request", "Send at least one setting.", 400)
        for ((key, value) in changes) {
            if (value == null || value is JsonNull) settings.remove(key) else settings[key] = value
        }
        version += 1
    }

    /** A day well under way: 6,240 steps, 310 kcal, 7 stand hours, 1,250 ml of water. Percentages from the goals. */
    suspend fun progress(focusMinutes: Int): GoalProgress = mutex.withLock {
        fun pct(value: Double, goal: Int?): Double? = goal?.takeIf { it > 0 }?.let { value / it * 100 }
        GoalProgress(
            date = today(),
            steps = 6240.0, stepsPct = pct(6240.0, goals.stepsPerDay),
            activeKcal = 310.0, activeKcalPct = pct(310.0, goals.activeKcalPerDay),
            standHours = 7.0, standHoursPct = pct(7.0, goals.standHoursPerDay),
            waterMl = 1250.0, focusMinutes = focusMinutes,
        )
    }

    suspend fun habits(includeArchived: Boolean): List<Habit> = mutex.withLock {
        habits.values.filter { includeArchived || !it.isArchived }.map { withStreak(it) }
            .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase(Locale.ROOT) }))
    }

    suspend fun createHabit(draft: HabitDraft): Habit = mutex.withLock {
        val id = UUID.randomUUID().toString().lowercase(Locale.ROOT)
        val habit = apply(Habit(id = id, name = draft.name.trim(), version = 1, sortOrder = habits.size), draft)
        habits[id] = habit
        habit
    }

    suspend fun updateHabit(habit: Habit, to: HabitDraft): Habit = mutex.withLock {
        val stored = habits[habit.id] ?: throw AuraError.Server("not_found", "That habit is gone.", 404)
        if (to.changes(stored).isEmpty()) return@withLock stored
        val next = apply(stored, to).copy(version = (stored.version ?: 0) + 1)
        habits[habit.id] = next
        withStreak(next)
    }

    suspend fun deleteHabit(id: String) = mutex.withLock {
        habits.remove(id)
        logs.values.removeAll { it.habitId == id }
        Unit
    }

    suspend fun logHabit(habitId: String, date: String, count: Int, minutes: Int): HabitLogResult = mutex.withLock {
        val habit = habits[habitId] ?: throw AuraError.Server("not_found", "That habit is gone.", 404)
        if (habit.isPaused) throw AuraError.Server("habit_paused", "This habit is paused.", 409)
        if (date > day(1)) throw AuraError.Server("date_in_future", "That day hasn't come yet.", 400)
        val existing = logs.values.firstOrNull { it.habitId == habitId && it.date == date }
        if (habit.targetType == HabitTargetType.yesNo && existing != null) {
            return@withLock HabitLogResult(existing, withStreak(habit).streak, 1, true, created = false)
        }
        val log = HabitLog(UUID.randomUUID().toString().lowercase(Locale.ROOT), habitId, date, count, minutes)
        logs[log.id] = log
        val dayCount = logs.values.filter { it.habitId == habitId && it.date == date }.sumOf { it.count }
        val kept = if (habit.targetType == HabitTargetType.count) dayCount >= habit.targetCount else true
        HabitLogResult(log, withStreak(habit).streak, dayCount, kept, created = true)
    }

    suspend fun deleteHabitLog(habitId: String, logId: String) = mutex.withLock {
        logs.remove(logId)
        Unit
    }

    suspend fun habitLogs(from: String, to: String): List<HabitLog> = mutex.withLock {
        logs.values.filter { it.date in from..to && habits.containsKey(it.habitId) }
    }

    suspend fun habitStats(id: String): HabitStats = mutex.withLock {
        val habit = habits[id] ?: throw AuraError.Server("not_found", "That habit is gone.", 404)
        val mine = logs.values.filter { it.habitId == id }
        val recent = mine.map { it.date }.filter { it >= day(-29) }.distinct().size
        HabitStats(
            habitId = id,
            streak = withStreak(habit).streak,
            completionRate30d = recent / 30.0,
            totalLogs = mine.size,
            milestones = listOf(7, 21, 30, 66, 100).map { count ->
                HabitMilestone(count, if (mine.size >= count) day(-1) else null)
            },
        )
    }

    /** The seeded best and a current streak that grows by one when today is ticked. */
    private fun withStreak(habit: Habit): Habit {
        val tickedToday = logs.values.any { it.habitId == habit.id && it.date == today() }
        val base = habit.streak
        val current = base.current + if (tickedToday && habit.id != "h-water") 1 else 0
        return habit.copy(streak = HabitStreak(current, maxOf(base.best, current), if (current > 0) today() else null))
    }

    private fun apply(habit: Habit, draft: HabitDraft): Habit = habit.copy(
        name = draft.name.trim(), cue = draft.cue.trim(), icon = draft.icon, colorHex = draft.colorHex,
        frequency = draft.frequency, specificDays = draft.specificDays.distinct().sorted(),
        timesPerWeek = draft.timesPerWeek, weeklyDay = draft.weeklyDay, targetType = draft.targetType,
        targetCount = draft.targetCount, targetMinutes = draft.targetMinutes,
        reminder = HabitReminder(draft.reminderEnabled, draft.reminderTime),
        goal = HabitGoal(draft.goalEnabled, draft.goalTargetCount, draft.goalDeadline),
        isNegative = draft.isNegative, isPaused = draft.isPaused,
    )
}
