package info.ibtaura.auraclarus.home

import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.DayPlan
import info.ibtaura.aura.core.PLANNER_DEFAULT_MINUTES
import info.ibtaura.aura.core.TaskDemand
import info.ibtaura.aura.core.TaskDraft
import info.ibtaura.aura.core.TaskHorizon
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.aura.core.TaskPriority
import info.ibtaura.aura.core.TaskWorkMode
import info.ibtaura.aura.core.TaskWorkType
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
     * Done: when. Open: its due date (overdue once the time, or the whole day, has passed), else the day it's
     * planned for. Null when it has neither. Its calendar block is `scheduleLine`'s.
     */
    fun dueLine(task: TaskItem, now: Instant, zone: ZoneId = DayKey.zone, locale: Locale = Locale.getDefault()): DueLine? {
        val today = now.atZone(zone).toLocalDate()
        fun dayOf(at: Instant) = day(at.atZone(zone).toLocalDate(), today, locale)
        if (task.done) {
            val at = task.completedAt ?: return DueLine("Done", false)
            return DueLine("Done ${dayOf(at).lowercaseIfRelative()}", false)
        }
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

// MARK: Tasks (auto-allocation)

/** Where a task stands with Find a time, for the line under its row. */
internal data class ScheduleLine(val text: String, val kind: Kind) {
    enum class Kind { scheduled, didntFit, waiting }
}

/** The words and choices for planning tasks, kept free of Compose so app unit tests can check them. */
internal object TaskPlanning {
    val workModeNames: Map<TaskWorkMode, String> = mapOf(TaskWorkMode.focus to "Focus", TaskWorkMode.meeting to "Meeting")

    val demandNames: Map<TaskDemand, String> = mapOf(
        TaskDemand.light to "Light", TaskDemand.medium to "Medium", TaskDemand.hard to "Hard", TaskDemand.auto to "Auto",
    )

    val workTypeNames: Map<TaskWorkType, String> = mapOf(
        TaskWorkType.deepFocus to "Deep focus", TaskWorkType.detailHeavy to "Detail", TaskWorkType.ideas to "Ideas",
        TaskWorkType.routine to "Routine", TaskWorkType.learning to "Learning",
    )

    const val autoCaption = "Aura finds a time for it when you plan your day."
    const val defaultLengthCaption = "Aura plans $PLANNER_DEFAULT_MINUTES min if you don't set a length."
    const val meetingCaption = "Meetings aren't placed automatically."
    const val offCaption = "Off: Aura leaves this task where you put it. Turn it on to plan it with your day."
    const val waiting = "Auto-schedule · waiting for Plan my day"

    /** The editor's captions under the Auto-schedule switch. */
    fun captions(draft: TaskDraft): List<String> = when {
        draft.workMode == TaskWorkMode.meeting -> listOf(meetingCaption)
        !draft.autoAllocate -> listOf(offCaption)
        draft.estimateMinutes == null -> listOf(autoCaption, defaultLengthCaption)
        else -> listOf(autoCaption)
    }

    /** The Planner's reason for leaving a task out, in plain words. A code this app doesn't know uses `detail`. */
    fun unplacedWords(reason: String, detail: String = ""): String = when (reason) {
        "noFreeTime" -> "No free time left that day"
        "overCapacity" -> "Too demanding for your energy that day"
        "workLimit" -> "Over the day's work limit"
        "dueLater" -> "Planned for a later day"
        "tooLong" -> "Longer than any free gap"
        "missingEstimate" -> "Needs a length"
        else -> detail.trim().removeSuffix(".").ifEmpty { "Didn't fit" }
    }

    /** True for a task Find a time may place: open, marked Auto-schedule, and focus work. */
    fun isCandidate(task: TaskItem): Boolean = !task.done && task.autoAllocate && task.workMode == TaskWorkMode.focus

    /** The tasks Find a time lists: Today's first, then Week's and Month's, each by title. */
    fun candidates(tasks: List<TaskItem>): List<TaskItem> = tasks.filter { isCandidate(it) }
        .distinctBy { it.id }
        .sortedWith(compareBy<TaskItem> { it.horizon.ordinal }.thenBy { it.title.lowercase(Locale.ROOT) })

    /**
     * The tasks ticked when Find a time opens: those asked for (`asked`, from a task's Schedule) that are still
     * candidates, else every candidate. An empty `asked` (Plan my day with nothing marked) means every candidate.
     */
    fun initialSelection(candidates: List<TaskItem>, asked: List<String>?): Set<String> {
        val ids = candidates.map { it.id }
        val wanted = asked?.filter { it in ids }.orEmpty()
        return if (wanted.isNotEmpty()) wanted.toSet() else ids.toSet()
    }

    /**
     * The `taskIds` sent with both the preview and Add to calendar: the ticked ids in the list's order, or null when
     * every task is ticked, so the Planner places all of them (and any added on another device since).
     */
    fun requestIds(candidates: List<TaskItem>, selected: Set<String>): List<String>? {
        val ids = candidates.map { it.id }.filter { it in selected }
        return if (ids.size == candidates.size) null else ids
    }

    /** The ids Plan my day opens Find a time with: every open auto-scheduled task. */
    fun planMyDayIds(tasks: List<TaskItem>): List<String> = candidates(tasks).map { it.id }

    /**
     * Done: nothing. Else its calendar block (`scheduledAt`, which Task mirrors a moment after Find a time), else
     * the stored plans (today's, tomorrow's): where it was placed, or why it didn't fit. Else, when it's marked
     * Auto-schedule, that it waits for Plan my day. Null when none of these.
     */
    fun scheduleLine(
        task: TaskItem,
        plans: List<DayPlan>,
        now: Instant,
        zone: ZoneId = DayKey.zone,
        locale: Locale = Locale.getDefault(),
    ): ScheduleLine? {
        if (task.done) return null
        fun scheduled(at: Instant): ScheduleLine {
            val today = now.atZone(zone).toLocalDate()
            val day = TaskText.day(at.atZone(zone).toLocalDate(), today, locale)
            val words = if (day == "Today" || day == "Tomorrow" || day == "Yesterday") day.lowercase(Locale.ROOT) else day
            return ScheduleLine("Scheduled $words, ${TaskText.time(at, zone, locale)}", ScheduleLine.Kind.scheduled)
        }
        task.scheduledAt?.let { return scheduled(it) }
        val ordered = plans.sortedBy { it.date }
        ordered.flatMap { it.placed }.filter { it.taskId == task.id }.minByOrNull { it.start }?.let { return scheduled(it.start) }
        if (isCandidate(task)) {
            ordered.firstNotNullOfOrNull { plan -> plan.unplaced.firstOrNull { it.taskId == task.id } }?.let {
                return ScheduleLine("Didn't fit: ${unplacedWords(it.reason, it.detail)}", ScheduleLine.Kind.didntFit)
            }
            return ScheduleLine(waiting, ScheduleLine.Kind.waiting)
        }
        return null
    }
}

// MARK: Instant saves

/** What the editor did, for the page to show at once. */
internal sealed class TaskEdit {
    abstract val id: String

    data class Saved(val task: TaskItem) : TaskEdit() {
        override val id: String get() = task.id
    }

    data class Deleted(override val id: String) : TaskEdit()
}

/** How to put the list back when a background save fails: the row as it was, or null to take a new row out again. */
internal data class TaskUndo(val id: String, val before: TaskItem?)

/** A background save that failed: what to put back, the words to show, and whether to load the list again. */
internal data class TaskSaveFailure(val undo: TaskUndo, val words: String, val reload: Boolean)

/** `list` with `edit` shown: a saved task in place (or added), a deleted one gone. */
internal fun List<TaskItem>.applying(edit: TaskEdit): List<TaskItem> = when (edit) {
    is TaskEdit.Saved -> replacing(edit.task)
    is TaskEdit.Deleted -> filterNot { it.id == edit.id }
}

/** `list` as it was before a save that failed. Only that row changes, so other saves on their way stay. */
internal fun List<TaskItem>.undoing(undo: TaskUndo): List<TaskItem> =
    undo.before?.let { replacing(it) } ?: filterNot { it.id == undo.id }

/**
 * A freshly loaded list with the saves still on their way shown over it, so a reload during a save doesn't make a
 * new task blink out or a ticked one come back.
 */
internal fun List<TaskItem>.withPending(pending: Collection<TaskEdit>): List<TaskItem> =
    pending.fold(this) { list, edit -> list.applying(edit) }

/**
 * A new task as the page shows it while the create is on its way. The id is the one sent with the create (chosen
 * on the phone), so the server's answer replaces this row in place.
 */
internal fun TaskDraft.previewTask(id: String, zone: ZoneId = DayKey.zone): TaskItem {
    val due = dueAt(zone)
    return TaskItem(
        id = id,
        title = title.trim(),
        at = due?.takeIf { hasTime },
        autoAllocate = placesItself,
        priority = priority,
        dueAt = due,
        hasTime = hasTime,
        notes = notes.trim().ifEmpty { null },
        horizon = horizon,
        flagged = flagged,
        estimateMinutes = estimateMinutes,
        workMode = workMode,
        workType = workType,
        demand = demand,
        demandSource = demand?.let { "manual" },
    )
}

/** True when a failed save means the list on screen is out of date (changed or deleted elsewhere). */
internal fun taskNeedsReload(error: Throwable): Boolean =
    error is AuraError.Server && (error.status == 404 || error.status == 409 || error.status == 412)
