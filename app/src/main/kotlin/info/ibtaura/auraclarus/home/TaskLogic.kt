package info.ibtaura.auraclarus.home

import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.TaskHorizon
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.aura.core.TaskPriority
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// The Tasks page's words and order, kept free of Compose so app unit tests can check them.

internal object TaskText {
    val horizonNames: Map<TaskHorizon, String> =
        mapOf(TaskHorizon.today to "Today", TaskHorizon.week to "Week", TaskHorizon.month to "Month")

    val priorityNames: Map<TaskPriority, String> = mapOf(
        TaskPriority.none to "None", TaskPriority.low to "Low", TaskPriority.medium to "Medium",
        TaskPriority.high to "High", TaskPriority.urgent to "Urgent",
    )

    /** The editor's priority chips, from none up. */
    val priorityChoices: List<TaskPriority> =
        listOf(TaskPriority.none, TaskPriority.low, TaskPriority.medium, TaskPriority.high, TaskPriority.urgent)

    /** The editor's estimate chips, in minutes. A task's own other value is added to them. */
    val estimatePresets: List<Int> = listOf(15, 30, 45, 60, 90, 120, 180, 240)

    /** "45 min", "1 h", "1 h 30 min". */
    fun minutes(total: Int): String {
        val hours = total / 60
        val rest = total % 60
        return when {
            hours == 0 -> "$total min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    /** The estimate chips for a task that already has `current` minutes. */
    fun estimateChoices(current: Int?): List<Int> =
        if (current == null || current in estimatePresets) estimatePresets else (estimatePresets + current).sorted()

    /** "Today", "Tomorrow", "Yesterday", else "Tue 18 Mar". */
    fun day(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("EEE d MMM", locale).format(day)
    }

    fun time(at: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at.atZone(zone))

    /** The grey line under a row's title, and whether it's overdue (shown in red). */
    data class DueLine(val text: String, val overdue: Boolean)

    /**
     * Done: when. Open: its calendar block, else its due date (overdue once the time, or the whole day, has
     * passed), else the day it's planned for. Null when it has none of them.
     */
    fun dueLine(task: TaskItem, now: Instant, zone: ZoneId = DayKey.zone, locale: Locale = Locale.getDefault()): DueLine? {
        val today = now.atZone(zone).toLocalDate()
        fun dayOf(at: Instant) = day(at.atZone(zone).toLocalDate(), today, locale)
        if (task.done) {
            val at = task.completedAt ?: return DueLine("Done", false)
            return DueLine("Done ${dayOf(at).lowercaseIfRelative()}", false)
        }
        task.scheduledAt?.let { return DueLine("Scheduled ${dayOf(it).lowercaseIfRelative()}, ${time(it, zone, locale)}", false) }
        task.dueAt?.let { due ->
            return if (task.hasTime) {
                DueLine("Due ${dayOf(due).lowercaseIfRelative()}, ${time(due, zone, locale)}", due < now)
            } else {
                val dueDay = due.atZone(zone).toLocalDate()
                DueLine("Due ${day(dueDay, today, locale).lowercaseIfRelative()}", dueDay < today)
            }
        }
        task.plannedFor?.let { key ->
            val planned = DayKey.localDate(key) ?: return null
            return DueLine("For ${day(planned, today, locale).lowercaseIfRelative()}", false)
        }
        return null
    }

    /** "Due today" reads better than "Due Today"; a date stays as it is. */
    private fun String.lowercaseIfRelative(): String =
        if (this == "Today" || this == "Tomorrow" || this == "Yesterday") lowercase(Locale.ROOT) else this

    /** The words for a failed write on the page or in the editor. */
    fun errorWords(error: Throwable, action: String = "save"): String = when (error) {
        AuraError.Offline -> "You're offline. Try again when you're connected."
        is AuraError.Server -> when (error.status) {
            412 -> "This task changed somewhere else. Pull down to reload, then try again."
            404 -> "This task is gone. It may have been deleted somewhere else."
            400 -> error.message.ifEmpty { "Aura couldn't $action that. Check the fields." }
            else -> "Couldn't $action. Try again."
        }
        else -> "Couldn't $action. Try again."
    }
}

/** What the page shows for one list: its open tasks in order, and its completed ones, newest first. */
internal data class TaskSections(val open: List<TaskItem>, val done: List<TaskItem>) {
    companion object {
        private val rank: Map<TaskPriority, Int> = mapOf(
            TaskPriority.urgent to 0, TaskPriority.high to 1, TaskPriority.medium to 2, TaskPriority.low to 3,
            TaskPriority.none to 4,
        )

        /**
         * Open tasks: those with a date first, soonest on top (a block's time, else the due date), then the rest;
         * ties go by priority, then title.
         */
        fun of(all: List<TaskItem>, horizon: TaskHorizon): TaskSections {
            val mine = all.filter { it.horizon == horizon }
            val open = mine.filter { !it.done }.sortedWith(
                compareBy<TaskItem> { whenOf(it) == null }
                    .thenBy { whenOf(it) }
                    .thenBy { rank[it.priority] ?: 4 }
                    .thenBy { it.title.lowercase(Locale.ROOT) },
            )
            val done = mine.filter { it.done }.sortedByDescending { it.completedAt ?: Instant.MIN }
            return TaskSections(open, done)
        }

        /** The open tasks on each list, for the counts on the switch. */
        fun openCounts(all: List<TaskItem>): Map<TaskHorizon, Int> =
            TaskHorizon.entries.associateWith { h -> all.count { it.horizon == h && !it.done } }

        private fun whenOf(task: TaskItem): Instant? = task.scheduledAt ?: task.dueAt
    }
}

/** `list` with `task` in place of the one with its id, or added when it isn't there. */
internal fun List<TaskItem>.replacing(task: TaskItem): List<TaskItem> =
    if (any { it.id == task.id }) map { if (it.id == task.id) task else it } else this + task
