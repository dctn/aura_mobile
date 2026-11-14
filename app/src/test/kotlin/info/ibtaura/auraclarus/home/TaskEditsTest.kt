package info.ibtaura.auraclarus.home

import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.TaskDraft
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.aura.core.TaskPriority
import info.ibtaura.aura.core.TaskWorkMode
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The Tasks page's instant saves: what shows while a write is on its way, and how a failed one is put back.

class TaskEditsTest {
    private val a = TaskItem("a", "Alpha")
    private val b = TaskItem("b", "Beta")

    @Test
    fun applyingShowsASavedTaskInPlaceOrAddsIt() {
        val renamed = a.copy(title = "Alpha 2")
        assertEquals(listOf(renamed, b), listOf(a, b).applying(TaskEdit.Saved(renamed)))
        val added = TaskItem("c", "Gamma")
        assertEquals(listOf(a, b, added), listOf(a, b).applying(TaskEdit.Saved(added)))
        assertEquals(listOf(b), listOf(a, b).applying(TaskEdit.Deleted("a")))
    }

    @Test
    fun undoingPutsOnlyThatRowBack() {
        val renamed = a.copy(title = "Alpha 2")
        val ticked = b.copy(done = true)
        // Two saves on their way; the first fails, the second stays shown.
        assertEquals(listOf(a, ticked), listOf(renamed, ticked).undoing(TaskUndo("a", a)))
        // A new task that failed to save is taken out again.
        assertEquals(listOf(a), listOf(a, TaskItem("new", "New")).undoing(TaskUndo("new", null)))
        // A task that failed to delete comes back.
        assertEquals(listOf(b, a), listOf(b).undoing(TaskUndo("a", a)))
    }

    @Test
    fun aReloadKeepsSavesOnTheirWay() {
        val added = TaskItem("new", "New")
        val loaded = listOf(a, b)
        val pending = listOf(TaskEdit.Saved(added), TaskEdit.Deleted("b"))
        assertEquals(listOf(a, added), loaded.withPending(pending))
        assertEquals(loaded, loaded.withPending(emptyList()))
    }

    @Test
    fun previewTaskShowsTheDraftUnderTheIdSent() {
        val draft = TaskDraft(
            title = "  Write report ",
            notes = " ",
            priority = TaskPriority.high,
            dueDay = LocalDate.of(2030, 3, 18),
            dueTime = LocalTime.of(9, 30),
            estimateMinutes = 45,
            demand = 60,
        )
        val task = draft.previewTask("id-1", ZoneOffset.UTC)
        assertEquals("id-1", task.id)
        assertEquals("Write report", task.title)
        assertNull(task.notes)
        assertEquals(TaskPriority.high, task.priority)
        assertEquals(draft.dueAt(ZoneOffset.UTC), task.dueAt)
        assertEquals(task.dueAt, task.at)
        assertTrue(task.hasTime)
        assertTrue(task.autoAllocate)
        assertEquals(45, task.estimateMinutes)
        assertEquals("manual", task.demandSource)
        assertFalse(task.done)

        val meeting = TaskDraft(title = "Sync", workMode = TaskWorkMode.meeting).previewTask("id-2", ZoneOffset.UTC)
        assertFalse(meeting.autoAllocate)
        assertNull(meeting.dueAt)
        assertNull(meeting.at)
        assertNull(meeting.demandSource)
    }

    @Test
    fun onlyStaleListRefusalsAskForAReload() {
        assertTrue(taskNeedsReload(AuraError.Server("not_found", "gone", 404)))
        assertTrue(taskNeedsReload(AuraError.Server("conflict", "changed", 409)))
        assertTrue(taskNeedsReload(AuraError.Server("precondition_failed", "changed", 412)))
        assertFalse(taskNeedsReload(AuraError.Server("validation", "bad", 400)))
        assertFalse(taskNeedsReload(AuraError.Offline))
    }
}
