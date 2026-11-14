package info.ibtaura.auraclarus.goals

import info.ibtaura.aura.core.Habit
import info.ibtaura.aura.core.HabitDraft
import info.ibtaura.aura.core.HabitLog
import info.ibtaura.aura.core.HabitLogResult
import info.ibtaura.aura.core.HabitStreak
import info.ibtaura.aura.core.HabitTargetType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The Goals page's instant habit saves: the pending row, the server's answer merged in, a failure put back.

class HabitEditsTest {
    private val day = "2030-03-18"
    private val read = Habit(id = "h1", name = "Read", sortOrder = 0)
    private val walk = Habit(id = "h2", name = "Walk", sortOrder = 3, targetType = HabitTargetType.count, targetCount = 3)

    @Test
    fun aNewHabitShowsAsAPendingRowAtTheEnd() {
        val id = HabitEdits.pendingId()
        assertTrue(HabitEdits.isPending(id))
        assertFalse(HabitEdits.isPending("h1"))
        val shown = HabitEdits.preview(HabitDraft(name = "  Stretch ", targetCount = 0), id, listOf(read, walk))
        assertEquals(id, shown.id)
        assertEquals("Stretch", shown.name)
        assertEquals(4, shown.sortOrder)
        assertEquals(1, shown.targetCount)
        assertEquals(0, HabitEdits.preview(HabitDraft(name = "First"), id, emptyList()).sortOrder)
    }

    @Test
    fun replacingAndUndoingTouchOnlyThatRow() {
        val renamed = HabitEdits.applied(HabitDraft(name = "Read more"), read)
        assertEquals("h1", renamed.id)
        assertEquals("Read more", renamed.name)
        val list = listOf(read, walk)
        assertEquals(listOf(renamed, walk), HabitEdits.replacing(list, "h1", renamed))
        assertEquals(list, HabitEdits.undoing(listOf(renamed, walk), "h1", read))

        val pending = HabitEdits.preview(HabitDraft(name = "New"), "pending-x", list)
        val withNew = HabitEdits.replacing(list, "pending-x", pending)
        assertEquals(list + pending, withNew)
        val saved = pending.copy(id = "h3")
        assertEquals(list + saved, HabitEdits.replacing(withNew, "pending-x", saved))
        assertEquals(list, HabitEdits.undoing(withNew, "pending-x", null))
    }

    @Test
    fun aCheckOffIsReplacedByTheServersLog() {
        val minutesHabit = read.copy(targetType = HabitTargetType.minutes, targetMinutes = 25)
        val pending = HabitEdits.pendingLog(minutesHabit, day)
        assertTrue(HabitEdits.isPending(pending.id))
        assertEquals(25, pending.minutes)
        assertEquals(0, HabitEdits.pendingLog(walk, day).minutes)

        val server = HabitLog("log-1", "h1", day, 1, 25)
        val result = HabitLogResult(server, HabitStreak(2, 5, day), dayCount = 1, dayKept = true, created = true)
        val other = HabitLog("log-0", "h2", day)
        assertEquals(listOf(other, server), HabitEdits.confirmLog(listOf(other, pending), pending.id, result))
        // A resend answered with a log already shown keeps one copy.
        assertEquals(listOf(other, server), HabitEdits.confirmLog(listOf(other, server, pending), pending.id, result))
    }

    @Test
    fun undoTakesTheNewestCountOrTheWholeDay() {
        val logs = listOf(
            HabitLog("w1", "h2", day),
            HabitLog("w2", "h2", day),
            HabitLog("w0", "h2", "2030-03-17"),
            HabitLog("r1", "h1", day),
            HabitLog("r2", "h1", day),
        )
        assertEquals(listOf(HabitLog("w2", "h2", day)), HabitEdits.undoLogs(walk, logs, day))
        assertEquals(listOf(HabitLog("r1", "h1", day), HabitLog("r2", "h1", day)), HabitEdits.undoLogs(read, logs, day))
    }
}
