package info.ibtaura.aura.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

// Goals (Search > Goals) and Units: Identity's healthGoals and settings, Health Data's goal progress and the Habit
// service. Shapes follow packages/contracts/src/identity.ts and habit.ts in aws_docs.

/** The `units` setting. Values always arrive metric; the phone converts for showing. */
enum class UnitSystem(val raw: String) {
    metric("metric"), imperial("imperial");

    companion object {
        /** Unknown or missing reads as metric, Identity's default. */
        fun fromRaw(raw: String?): UnitSystem = entries.firstOrNull { it.raw == raw } ?: metric
    }
}

/**
 * Identity's O30 goals and sleep schedule. Null where the person hasn't set one; the screen shows the default then.
 * Times are "HH:mm".
 */
data class HealthGoals(
    val stepsPerDay: Int? = null,
    val activeKcalPerDay: Int? = null,
    val sleepNeedMinutes: Int? = null,
    val standHoursPerDay: Int? = null,
    val bedtime: String? = null,
    val wakeTime: String? = null,
    val waterGoalMl: Int? = null,
) {
    companion object {
        // The body keys of PUT /v1/me/profile {healthGoals: {...}}.
        const val STEPS = "stepsPerDay"
        const val ACTIVE_KCAL = "activeKcalPerDay"
        const val SLEEP_NEED = "sleepNeedMinutes"
        const val STAND_HOURS = "standHoursPerDay"
        const val BEDTIME = "bedtime"
        const val WAKE_TIME = "wakeTime"
        const val WATER_ML = "waterGoalMl"

        /** What the app shows before a goal is set. Sleep need is Identity's own default (SLEEP_NEED in identity.ts). */
        const val defaultSteps = 8000
        const val defaultActiveKcal = 500
        const val defaultStandHours = 12
        const val defaultWaterMl = 2000
        const val defaultSleepNeedMinutes = 480
        const val minSleepNeedMinutes = 420
        const val maxSleepNeedMinutes = 540
    }
}

/** GET /v1/me as Goals and Units read it: the goals, every setting, and the version a profile write must name. */
data class GoalsProfile(
    /** Null when Identity doesn't send `healthGoals` yet (the field is optional until it ships). */
    val healthGoals: HealthGoals?,
    val settings: Map<String, JsonElement>,
    val version: Int,
) {
    val units: UnitSystem get() = UnitSystem.fromRaw((settings[UNITS_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content)

    /** `focus.dailyGoalMinutes`, or null when unset (the app shows 120). */
    val focusGoalMinutes: Int?
        get() = (settings[FOCUS_GOAL_KEY] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toInt()

    companion object {
        const val UNITS_KEY = "units"
        const val FOCUS_GOAL_KEY = "focus.dailyGoalMinutes"
        const val defaultFocusGoalMinutes = 120
        const val minFocusGoalMinutes = 15
        const val maxFocusGoalMinutes = 240
    }
}

/** Today's numbers for the daily goals. Each is null when there's nothing to show for it yet. */
data class GoalProgress(
    val date: String,
    val steps: Double? = null,
    /** Health Data's own percentage of the steps goal (stepGoalProgressPct). */
    val stepsPct: Double? = null,
    val activeKcal: Double? = null,
    val activeKcalPct: Double? = null,
    val standHours: Double? = null,
    val standHoursPct: Double? = null,
    /** The sum of today's water logs' `ml`; null when the logs couldn't be read. */
    val waterMl: Double? = null,
    /** Focus minutes from today's sessions; null when they couldn't be read. */
    val focusMinutes: Int? = null,
)

// MARK: Habits

data class HabitReminder(val enabled: Boolean, val time: String)

/** A total-check-offs goal: `targetCount` logs, by `deadline` (a DayKey) when set. */
data class HabitGoal(val enabled: Boolean, val targetCount: Int, val deadline: String?)

/** Kept periods in a row. A period is a day for daily and specificDays habits, a week for weekly and perWeek ones. */
data class HabitStreak(val current: Int, val best: Int, val aliveThrough: String?) {
    /** `current`, or 0 once `today` (a DayKey) is past `aliveThrough`, as the contract asks of a cached habit. */
    fun currentOn(today: String): Int {
        val through = aliveThrough ?: return current
        return if (today > through) 0 else current
    }

    companion object {
        val none = HabitStreak(0, 0, null)
    }
}

object HabitFrequency {
    const val daily = "daily"
    const val specificDays = "specificDays"
    const val perWeek = "perWeek"
    const val weekly = "weekly"
    val all = listOf(daily, specificDays, perWeek, weekly)
}

object HabitTargetType {
    const val yesNo = "yesNo"
    const val count = "count"
    const val minutes = "minutes"
    const val measurement = "measurement"
}

data class Habit(
    val id: String,
    val name: String,
    val cue: String = "",
    val icon: String = "star.fill",
    val colorHex: String = "34C759",
    val frequency: String = HabitFrequency.daily,
    /** 0 = Monday ... 6 = Sunday. */
    val specificDays: List<Int> = emptyList(),
    val timesPerWeek: Int = 3,
    val weeklyDay: Int = 0,
    val targetType: String = HabitTargetType.yesNo,
    val targetCount: Int = 1,
    val targetMinutes: Int = 20,
    val targetUnit: String = "",
    val reminder: HabitReminder = HabitReminder(false, "08:00"),
    val goal: HabitGoal = HabitGoal(false, 30, null),
    val isPaused: Boolean = false,
    val isArchived: Boolean = false,
    /** A habit to break: logging it means "avoided it". */
    val isNegative: Boolean = false,
    val showStreak: Boolean = true,
    val sortOrder: Int = 0,
    val streak: HabitStreak = HabitStreak.none,
    /** For PATCH's If-Match. Null when the answer had none. */
    val version: Int? = null,
) {
    /** The streak counts weeks for weekly and perWeek habits. */
    val streakUnit: String get() = if (frequency == HabitFrequency.weekly || frequency == HabitFrequency.perWeek) "week" else "day"

    /** "Every day", "Mon, Wed, Fri", "3 times a week", "Weekly on Sunday". */
    val scheduleText: String
        get() = when (frequency) {
            HabitFrequency.specificDays ->
                if (specificDays.isEmpty()) "No days picked" else specificDays.sorted().joinToString(", ") { weekdayShort(it) }
            HabitFrequency.perWeek -> if (timesPerWeek == 1) "Once a week" else "$timesPerWeek times a week"
            HabitFrequency.weekly -> "Weekly on ${weekdayLong(weeklyDay)}"
            else -> "Every day"
        }

    companion object {
        private val shortDays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        private val longDays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        fun weekdayShort(day: Int): String = shortDays.getOrElse(day) { "?" }
        fun weekdayLong(day: Int): String = longDays.getOrElse(day) { "?" }
    }
}

/** One check-off. */
data class HabitLog(
    val id: String,
    val habitId: String,
    val date: String,
    val count: Int = 1,
    val minutes: Int = 0,
)

/** POST /v1/habits/{id}/logs: the log, the streak after it, and the day's totals. `created` false on a resend. */
data class HabitLogResult(
    val log: HabitLog,
    val streak: HabitStreak,
    val dayCount: Int,
    val dayKept: Boolean,
    val created: Boolean,
)

data class HabitMilestone(val count: Int, val reachedOn: String?)

data class HabitStats(
    val habitId: String,
    val streak: HabitStreak,
    /** 0...1 */
    val completionRate30d: Double,
    val totalLogs: Int,
    val milestones: List<HabitMilestone>,
)

/** The habit editor's fields. `createBody` and `changes` build the request bodies (the routes are strict objects). */
data class HabitDraft(
    val name: String = "",
    val cue: String = "",
    val icon: String = "star.fill",
    val colorHex: String = "34C759",
    val frequency: String = HabitFrequency.daily,
    val specificDays: List<Int> = emptyList(),
    val timesPerWeek: Int = 3,
    val weeklyDay: Int = 0,
    val targetType: String = HabitTargetType.yesNo,
    val targetCount: Int = 1,
    val targetMinutes: Int = 20,
    val reminderEnabled: Boolean = false,
    val reminderTime: String = "08:00",
    val goalEnabled: Boolean = false,
    val goalTargetCount: Int = 30,
    val goalDeadline: String? = null,
    val isNegative: Boolean = false,
    val isPaused: Boolean = false,
) {
    constructor(habit: Habit) : this(
        name = habit.name, cue = habit.cue, icon = habit.icon, colorHex = habit.colorHex, frequency = habit.frequency,
        specificDays = habit.specificDays, timesPerWeek = habit.timesPerWeek, weeklyDay = habit.weeklyDay,
        targetType = habit.targetType, targetCount = habit.targetCount, targetMinutes = habit.targetMinutes,
        reminderEnabled = habit.reminder.enabled, reminderTime = habit.reminder.time, goalEnabled = habit.goal.enabled,
        goalTargetCount = habit.goal.targetCount, goalDeadline = habit.goal.deadline, isNegative = habit.isNegative,
        isPaused = habit.isPaused,
    )

    /** Why it can't be saved yet, or null. */
    val problem: String?
        get() = when {
            name.trim().isEmpty() -> "Give the habit a name."
            name.trim().length > 80 -> "Keep the name to 80 characters."
            cue.trim().length > 120 -> "Keep the cue to 120 characters."
            frequency == HabitFrequency.specificDays && specificDays.isEmpty() -> "Pick at least one day."
            else -> null
        }

    private fun fields(): Map<String, JsonElement> = linkedMapOf(
        "name" to JsonPrimitive(name.trim()),
        "cue" to JsonPrimitive(cue.trim()),
        "icon" to JsonPrimitive(icon),
        "colorHex" to JsonPrimitive(colorHex.uppercase(Locale.ROOT)),
        "frequency" to JsonPrimitive(frequency),
        "specificDays" to JsonArray(specificDays.distinct().sorted().map { JsonPrimitive(it) }),
        "timesPerWeek" to JsonPrimitive(timesPerWeek.coerceIn(1, 7)),
        "weeklyDay" to JsonPrimitive(weeklyDay.coerceIn(0, 6)),
        "targetType" to JsonPrimitive(targetType),
        "targetCount" to JsonPrimitive(targetCount.coerceIn(1, 1000)),
        "targetMinutes" to JsonPrimitive(targetMinutes.coerceIn(1, 1440)),
        "reminder" to JsonObject(mapOf("enabled" to JsonPrimitive(reminderEnabled), "time" to JsonPrimitive(reminderTime))),
        "goal" to JsonObject(mapOf(
            "enabled" to JsonPrimitive(goalEnabled),
            "targetCount" to JsonPrimitive(goalTargetCount.coerceIn(1, 10_000)),
            "deadline" to (goalDeadline?.let { JsonPrimitive(it) } ?: JsonNull),
        )),
        "isNegative" to JsonPrimitive(isNegative),
        "isPaused" to JsonPrimitive(isPaused),
    )

    /** POST /v1/habits: every field, the caller's id, and the streak shown (the service's default hides it). */
    fun createBody(id: String): JsonObject =
        JsonObject(linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id)) + fields() + ("showStreak" to JsonPrimitive(true)))

    /** PATCH /v1/habits/{id}: only the fields that differ from `habit`. Empty when nothing changed. */
    fun changes(habit: Habit): JsonObject {
        val before = HabitDraft(habit).fields()
        return JsonObject(fields().filter { (key, value) -> before[key] != value })
    }
}

/** A setting's value as a plain number, or null. */
fun JsonElement?.settingNumber(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** A setting's value as text, or null. */
fun JsonElement?.settingText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A setting's value as a yes/no, or null. */
fun JsonElement?.settingFlag(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
