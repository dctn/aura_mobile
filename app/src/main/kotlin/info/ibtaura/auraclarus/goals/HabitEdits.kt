package info.ibtaura.auraclarus.goals

import info.ibtaura.aura.core.Habit
import info.ibtaura.aura.core.HabitDraft
import info.ibtaura.aura.core.HabitGoal
import info.ibtaura.aura.core.HabitLog
import info.ibtaura.aura.core.HabitLogResult
import info.ibtaura.aura.core.HabitReminder
import info.ibtaura.aura.core.HabitTargetType
import java.util.UUID

// The Goals page's instant habit saves, kept free of Compose so app unit tests can check them: what the page shows
// while a write is on its way, the server's answer merged in, and the list put back when the write fails.

internal object HabitEdits {
    /** The prefix of a habit or check-off shown before the server has answered. */
    const val PENDING = "pending-"

    fun pendingId(): String = PENDING + UUID.randomUUID().toString()

    fun isPending(id: String): Boolean = id.startsWith(PENDING)

    /** `habit` with the editor's fields, as the server will store them (the limits `HabitDraft` sends). */
    fun applied(draft: HabitDraft, habit: Habit): Habit = habit.copy(
        name = draft.name.trim(),
        cue = draft.cue.trim(),
        icon = draft.icon,
        colorHex = draft.colorHex,
        frequency = draft.frequency,
        specificDays = draft.specificDays.distinct().sorted(),
        timesPerWeek = draft.timesPerWeek.coerceIn(1, 7),
        weeklyDay = draft.weeklyDay.coerceIn(0, 6),
        targetType = draft.targetType,
        targetCount = draft.targetCount.coerceIn(1, 1000),
        targetMinutes = draft.targetMinutes.coerceIn(1, 1440),
        reminder = HabitReminder(draft.reminderEnabled, draft.reminderTime),
        goal = HabitGoal(draft.goalEnabled, draft.goalTargetCount.coerceIn(1, 10_000), draft.goalDeadline),
        isNegative = draft.isNegative,
        isPaused = draft.isPaused,
    )

    /** A new habit as the page shows it while the create is on its way: a pending id, at the end of the list. */
    fun preview(draft: HabitDraft, id: String, after: List<Habit>): Habit =
        applied(draft, Habit(id = id, name = draft.name.trim(), sortOrder = (after.maxOfOrNull { it.sortOrder } ?: -1) + 1))

    /** `list` with `habit` in place of the one with `id` (a pending row's id), or added when it isn't there. */
    fun replacing(list: List<Habit>, id: String, habit: Habit): List<Habit> =
        if (list.any { it.id == id }) list.map { if (it.id == id) habit else it } else list + habit

    /** `list` as it was before a save that failed: the row as it was, or a new row taken out again. */
    fun undoing(list: List<Habit>, id: String, before: Habit?): List<Habit> =
        if (before == null) list.filterNot { it.id == id } else replacing(list, id, before)

    /** A check-off as shown before the server answers: one more for a count, the target for minutes. */
    fun pendingLog(habit: Habit, date: String): HabitLog {
        val minutes = if (habit.targetType == HabitTargetType.minutes) habit.targetMinutes else 0
        return HabitLog(pendingId(), habit.id, date, 1, minutes)
    }

    /** The check-offs with the server's log in place of the pending one (and no second copy on a resend). */
    fun confirmLog(logs: List<HabitLog>, pendingId: String, result: HabitLogResult): List<HabitLog> =
        logs.filterNot { it.id == pendingId || it.id == result.log.id } + result.log

    /** The check-offs an undo takes away today: the newest for a count habit, else all of the day's. */
    fun undoLogs(habit: Habit, logs: List<HabitLog>, date: String): List<HabitLog> {
        val mine = logs.filter { it.habitId == habit.id && it.date == date }
        return if (habit.targetType == HabitTargetType.count) mine.takeLast(1) else mine
    }
}
