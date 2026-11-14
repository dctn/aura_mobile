package info.ibtaura.auraclarus.home

import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.TaskHorizon
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.aura.core.TaskPriority
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The Tasks page's order and words, without Compose.

class TaskLogicTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val locale = Locale.UK
    private val now: Instant = LocalDate.of(2030, 3, 18).atTime(12, 0).atZone(zone).toInstant()
    private fun at(day: Int, hour: Int = 0): Instant = LocalDate.of(2030, 3, day).atTime(hour, 0).atZone(zone).toInstant()

    @Test
    fun sectionsSplitByListAndOrderOpenTasks() {
        val all = listOf(
            TaskItem("undated-low", "b undated", priority = TaskPriority.low),
            TaskItem("undated-urgent", "z undated", priority = TaskPriority.urgent),
            TaskItem("later", "later", dueAt = at(20)),
            TaskItem("sooner", "sooner", dueAt = at(19, 9), hasTime = true),
            TaskItem("block", "block", scheduledAt = at(18, 15)),
            TaskItem("week", "week", horizon = TaskHorizon.week),
            TaskItem("old-done", "old", done = true, completedAt = at(10)),
            TaskItem("new-done", "new", done = true, completedAt = at(17)),
        )
        val today = TaskSections.of(all, TaskHorizon.today)
        assertEquals(listOf("block", "sooner", "later", "undated-urgent", "undated-low"), today.open.map { it.id })
        assertEquals(listOf("new-done", "old-done"), today.done.map { it.id })
        assertEquals(listOf("week"), TaskSections.of(all, TaskHorizon.week).open.map { it.id })
        assertEquals(mapOf(TaskHorizon.today to 5, TaskHorizon.week to 1, TaskHorizon.month to 0), TaskSections.openCounts(all))
    }

    @Test
    fun dueLines() {
        fun line(task: TaskItem) = TaskText.dueLine(task, now, zone, locale)
        assertEquals(TaskText.DueLine("Due today", false), line(TaskItem("a", "a", dueAt = at(18))))
        assertEquals(TaskText.DueLine("Due yesterday", true), line(TaskItem("a", "a", dueAt = at(17))))
        assertEquals(TaskText.DueLine("Due tomorrow", false), line(TaskItem("a", "a", dueAt = at(19))))
        assertEquals("Due Fri 22 Mar", line(TaskItem("a", "a", dueAt = at(22)))?.text)
        val timedPast = line(TaskItem("a", "a", dueAt = at(18, 9), hasTime = true))
        assertTrue(timedPast?.overdue == true)
        assertTrue(timedPast.text.startsWith("Due today, "))
        assertTrue(line(TaskItem("a", "a", scheduledAt = at(18, 15)))!!.text.startsWith("Scheduled today, "))
        assertEquals("For tomorrow", line(TaskItem("a", "a", plannedFor = "2030-03-19"))?.text)
        assertEquals("Done yesterday", line(TaskItem("a", "a", done = true, completedAt = at(17, 8)))?.text)
        assertNull(line(TaskItem("a", "a")))
    }

    @Test
    fun minutesAndEstimateChoices() {
        assertEquals("45 min", TaskText.minutes(45))
        assertEquals("2 h", TaskText.minutes(120))
        assertEquals("1 h 30 min", TaskText.minutes(90))
        assertEquals(TaskText.estimatePresets, TaskText.estimateChoices(null))
        assertEquals(TaskText.estimatePresets, TaskText.estimateChoices(30))
        assertTrue(50 in TaskText.estimateChoices(50))
        assertEquals(TaskText.estimateChoices(50).sorted(), TaskText.estimateChoices(50))
    }

    @Test
    fun replacingSwapsOrAdds() {
        val list = listOf(TaskItem("a", "A"), TaskItem("b", "B"))
        assertEquals(listOf("A", "B2"), list.replacing(TaskItem("b", "B2")).map { it.title })
        assertEquals(listOf("a", "b", "c"), list.replacing(TaskItem("c", "C")).map { it.id })
    }

    @Test
    fun errorWords() {
        assertTrue(TaskText.errorWords(AuraError.Offline).contains("offline"))
        assertTrue(TaskText.errorWords(AuraError.Server("version_mismatch", "x", 412)).contains("changed somewhere else"))
        assertTrue(TaskText.errorWords(AuraError.Server("not_found", "x", 404)).contains("gone"))
        assertEquals("Couldn't complete it. Try again.", TaskText.errorWords(AuraError.Server("internal", "x", 500), "complete it"))
        assertFalse(TaskText.errorWords(IllegalStateException()).isEmpty())
    }
}
