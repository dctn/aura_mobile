package info.ibtaura.aura.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.BreakIterator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

// The Tasks page's types: the Task contract's enums (packages/contracts/src/task.ts in aws_docs), the editor's fields
// and the fields one PATCH /v1/tasks/{id} changes. `TaskItem` itself is in Models.kt.

/** Task's `status`. A value this app doesn't know reads as pending. */
enum class TaskStatus(val raw: String) {
    pending("pending"), working("working"), completed("completed");

    companion object {
        fun from(raw: String?): TaskStatus = entries.firstOrNull { it.raw == raw } ?: pending
    }
}

/** Task's `priority`, most urgent first. A value this app doesn't know reads as none. */
enum class TaskPriority(val raw: String) {
    urgent("urgent"), high("high"), medium("medium"), low("low"), none("none");

    companion object {
        fun from(raw: String?): TaskPriority = entries.firstOrNull { it.raw == raw } ?: none
    }
}

/** Task's `horizon`: which of the page's three lists the task sits on. Today is the default. */
enum class TaskHorizon(val raw: String) {
    today("today"), week("week"), month("month");

    companion object {
        fun from(raw: String?): TaskHorizon = entries.firstOrNull { it.raw == raw } ?: today
    }
}

/** One field of a PATCH: wrapped so that a null `value` (clear it) differs from no change at all (no `Change`). */
data class Change<out T>(val value: T)

/** The answer to POST /v1/tasks/{id}/complete: the task, and the next copy when it repeats. */
data class TaskCompletion(val task: TaskItem, val next: TaskItem? = null)

/**
 * What one PATCH /v1/tasks/{id} sets. A null property is left out (unchanged); a `Change` holding null clears the
 * field. Empty when nothing changes, and then nothing is sent.
 */
data class TaskChanges(
    val title: String? = null,
    val notes: Change<String?>? = null,
    val priority: TaskPriority? = null,
    val dueAt: Change<Instant?>? = null,
    val hasTime: Boolean? = null,
    val estimateMinutes: Change<Int?>? = null,
    val horizon: TaskHorizon? = null,
    val flagged: Boolean? = null,
    val status: TaskStatus? = null,
    val plannedFor: Change<String?>? = null,
) {
    val isEmpty: Boolean get() = this == TaskChanges()

    /** The names of the fields sent, for tests and logs. */
    val fieldNames: Set<String> get() = json().keys

    /** The PATCH body: only the fields that change; a cleared one as JSON null. */
    internal fun json(): JsonObject = buildJsonObject {
        title?.let { put("title", it) }
        notes?.let { put("notes", it.value?.let { v -> JsonPrimitive(v) } ?: JsonNull) }
        priority?.let { put("priority", it.raw) }
        dueAt?.let { put("dueAt", it.value?.let { v -> JsonPrimitive(WireDate.string(v)) } ?: JsonNull) }
        hasTime?.let { put("hasTime", it) }
        estimateMinutes?.let { put("estimateMinutes", it.value?.let { v -> JsonPrimitive(v) } ?: JsonNull) }
        horizon?.let { put("horizon", it.raw) }
        flagged?.let { put("flagged", it) }
        status?.let { put("status", it.raw) }
        plannedFor?.let { put("plannedFor", it.value?.let { v -> JsonPrimitive(v) } ?: JsonNull) }
    }

    /** `task` as the server will store it after this change (the stub, and the page while the save is on its way). */
    fun appliedTo(task: TaskItem, now: Instant = Instant.now()): TaskItem {
        var next = task
        title?.let { next = next.copy(title = it) }
        notes?.let { next = next.copy(notes = it.value) }
        priority?.let { next = next.copy(priority = it) }
        dueAt?.let { next = next.copy(dueAt = it.value) }
        hasTime?.let { next = next.copy(hasTime = it) }
        estimateMinutes?.let { next = next.copy(estimateMinutes = it.value) }
        horizon?.let { next = next.copy(horizon = it) }
        flagged?.let { next = next.copy(flagged = it) }
        plannedFor?.let { next = next.copy(plannedFor = it.value) }
        status?.let { next = next.withStatus(it, now) }
        return next.copy(at = next.scheduledAt ?: next.dueAt?.takeIf { next.hasTime })
    }
}

/** The task in `status`, with completedAt set or cleared as Task does (status.ts). The same status changes nothing. */
fun TaskItem.withStatus(status: TaskStatus, now: Instant = Instant.now()): TaskItem {
    if (status == this.status) return this
    return copy(
        status = status,
        done = status == TaskStatus.completed,
        completedAt = if (status == TaskStatus.completed) now else null,
    )
}

/**
 * The task editor's fields, with the Task contract's limits. The due date is a day and an optional time in the
 * phone's zone: a day alone goes as that day's midnight with `hasTime` false.
 */
data class TaskDraft(
    val title: String = "",
    val notes: String = "",
    val priority: TaskPriority = TaskPriority.none,
    /** The day it's due, or null for no due date. */
    val dueDay: LocalDate? = null,
    /** The time on `dueDay`, or null when only the day matters. Ignored without a day. */
    val dueTime: LocalTime? = null,
    /** 1 to 1440 minutes, or null for no estimate. */
    val estimateMinutes: Int? = null,
    val horizon: TaskHorizon = TaskHorizon.today,
    val flagged: Boolean = false,
) {
    /** The fields of a task as it is now. */
    constructor(task: TaskItem, zone: ZoneId = DayKey.zone) : this(
        title = task.title,
        notes = task.notes ?: "",
        priority = task.priority,
        dueDay = task.dueAt?.atZone(zone)?.toLocalDate(),
        dueTime = task.dueAt?.takeIf { task.hasTime }?.atZone(zone)?.toLocalTime()?.truncatedTo(ChronoUnit.MINUTES),
        estimateMinutes = task.estimateMinutes,
        horizon = task.horizon,
        flagged = task.flagged,
    )

    private val trimmedTitle: String get() = title.trim()

    /** True when a time of day is set (and a day to go with it). */
    val hasTime: Boolean get() = dueDay != null && dueTime != null

    /** The instant sent as `dueAt`: the time on the day, or the day's midnight when only the day is set. */
    fun dueAt(zone: ZoneId = DayKey.zone): Instant? {
        val day = dueDay ?: return null
        return day.atTime(dueTime ?: LocalTime.MIDNIGHT).atZone(zone).toInstant()
    }

    /** What stops it being saved, in words, or null when it can be. */
    val problem: String?
        get() {
            if (trimmedTitle.isEmpty()) return "Give it a title."
            if (trimmedTitle.graphemeCount() > maxTitle) return "The title is too long."
            if (notes.graphemeCount() > maxNotes) return "The notes are too long."
            val estimate = estimateMinutes
            if (estimate != null && (estimate < minEstimate || estimate > maxEstimate)) {
                return "The estimate has to be 1 to 1440 minutes."
            }
            return null
        }

    /** The body of POST /v1/tasks, without its id. Empty notes, no estimate and no due date are left out. */
    internal fun createBody(zone: ZoneId = DayKey.zone): JsonObject = buildJsonObject {
        put("title", trimmedTitle)
        text(notes)?.let { put("notes", it) }
        put("priority", priority.raw)
        dueAt(zone)?.let {
            put("dueAt", WireDate.string(it))
            put("hasTime", hasTime)
        }
        estimateMinutes?.let { put("estimateMinutes", it) }
        put("horizon", horizon.raw)
        put("flagged", flagged)
    }

    /** Only what differs from `task`. Clearing notes, the due date or the estimate sends null. */
    fun changes(from: TaskItem, zone: ZoneId = DayKey.zone): TaskChanges {
        val before = TaskDraft(from, zone)
        val newDue = dueAt(zone)
        val dueChanged = newDue != before.dueAt(zone) || hasTime != before.hasTime
        return TaskChanges(
            title = trimmedTitle.takeIf { it != from.title },
            notes = if (text(notes) != text(before.notes)) Change(text(notes)) else null,
            priority = priority.takeIf { it != from.priority },
            dueAt = if (dueChanged) Change(newDue) else null,
            hasTime = if (dueChanged) hasTime else null,
            estimateMinutes = if (estimateMinutes != from.estimateMinutes) Change(estimateMinutes) else null,
            horizon = horizon.takeIf { it != from.horizon },
            flagged = flagged.takeIf { it != from.flagged },
        )
    }

    companion object {
        internal const val maxTitle = 500
        internal const val maxNotes = 20_000
        const val minEstimate = 1
        const val maxEstimate = 1440

        /** A new task's id: a lowercase UUID, chosen once so a resent create gets the same task back. */
        fun newId(): String = UUID.randomUUID().toString().lowercase(Locale.ROOT)

        /** Trimmed text, or null when there is none. */
        private fun text(value: String?): String? = value?.trim()?.ifEmpty { null }
    }
}

/** Swift's `String.count`: user-perceived characters, not UTF-16 units. */
private fun String.graphemeCount(): Int {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(this)
    var count = 0
    while (iterator.next() != BreakIterator.DONE) count++
    return count
}
