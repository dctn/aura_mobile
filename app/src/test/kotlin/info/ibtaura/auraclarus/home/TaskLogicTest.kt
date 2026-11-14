package info.ibtaura.auraclarus.home

import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.DayPlan
import info.ibtaura.aura.core.PlanBlock
import info.ibtaura.aura.core.TaskDraft
import info.ibtaura.aura.core.TaskHorizon
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.aura.core.TaskPriority
import info.ibtaura.aura.core.TaskWorkMode
import info.ibtaura.aura.core.UnplacedTask
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
        assertNull(line(TaskItem("a", "a", scheduledAt = at(18, 15))))
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

    // MARK: Tasks (auto-allocation)

    private fun auto(id: String, title: String = id, horizon: TaskHorizon = TaskHorizon.today) =
        TaskItem(id, title, autoAllocate = true, horizon = horizon)

    private fun plan(date: String, placed: List<PlanBlock> = emptyList(), unplaced: List<UnplacedTask> = emptyList()) =
        DayPlan(date, now, placed = placed, unplaced = unplaced)

    private fun block(taskId: String, start: Instant) =
        PlanBlock(taskId, "plan-$taskId", start, start.plusSeconds(1800), 50.0, reason = "free")

    @Test
    fun candidatesAreOpenAutoFocusTasksTodayFirst() {
        val all = listOf(
            auto("w", "a week", TaskHorizon.week),
            auto("b", "Beta"),
            auto("a", "alpha"),
            TaskItem("off", "off"),
            TaskItem("meet", "meet", autoAllocate = true, workMode = TaskWorkMode.meeting),
            TaskItem("done", "done", autoAllocate = true, done = true),
        )
        assertEquals(listOf("a", "b", "w"), TaskPlanning.candidates(all).map { it.id })
        assertEquals(listOf("a", "b", "w"), TaskPlanning.planMyDayIds(all))
        assertTrue(TaskPlanning.planMyDayIds(listOf(TaskItem("off", "off"))).isEmpty())
    }

    @Test
    fun initialSelectionUsesTheAskedTasksElseAll() {
        val candidates = listOf(auto("a"), auto("b"), auto("c"))
        assertEquals(setOf("a", "b", "c"), TaskPlanning.initialSelection(candidates, null))
        assertEquals(setOf("a", "b", "c"), TaskPlanning.initialSelection(candidates, emptyList()))
        assertEquals(setOf("b"), TaskPlanning.initialSelection(candidates, listOf("b")))
        assertEquals(setOf("a", "b", "c"), TaskPlanning.initialSelection(candidates, listOf("gone")))
        assertEquals(listOf("a", "c"), TaskPlanning.requestIds(candidates, setOf("c", "a")))
        assertNull(TaskPlanning.requestIds(candidates, setOf("a", "b", "c")))
        assertEquals(emptyList<String>(), TaskPlanning.requestIds(candidates, emptySet()))
        assertTrue(TaskPlanning.initialSelection(emptyList(), listOf("a")).isEmpty())
    }

    @Test
    fun unplacedReasonsReadAsPlainWords() {
        assertEquals("No free time left that day", TaskPlanning.unplacedWords("noFreeTime"))
        assertEquals("Too demanding for your energy that day", TaskPlanning.unplacedWords("overCapacity"))
        assertEquals("Over the day's work limit", TaskPlanning.unplacedWords("workLimit"))
        assertEquals("Planned for a later day", TaskPlanning.unplacedWords("dueLater"))
        assertEquals("Longer than any free gap", TaskPlanning.unplacedWords("tooLong"))
        assertEquals("Needs a length", TaskPlanning.unplacedWords("missingEstimate"))
        assertEquals("Something new", TaskPlanning.unplacedWords("brandNew", "Something new."))
        assertEquals("Didn't fit", TaskPlanning.unplacedWords("brandNew"))
    }

    @Test
    fun scheduleLines() {
        fun line(task: TaskItem, plans: List<DayPlan> = emptyList()) = TaskPlanning.scheduleLine(task, plans, now, zone, locale)
        val block = line(TaskItem("a", "a", scheduledAt = at(18, 15)))!!
        assertTrue(block.text.startsWith("Scheduled today, "))
        assertEquals(ScheduleLine.Kind.scheduled, block.kind)
        assertTrue(line(TaskItem("a", "a", scheduledAt = at(19, 9)))!!.text.startsWith("Scheduled tomorrow, "))
        assertTrue(line(TaskItem("a", "a", scheduledAt = at(22, 9)))!!.text.startsWith("Scheduled Fri 22 Mar, "))
        val placed = line(auto("a"), listOf(plan("2030-03-19", placed = listOf(block("a", at(19, 10))))))!!
        assertTrue(placed.text.startsWith("Scheduled tomorrow, "))
        val didnt = line(auto("a"), listOf(plan("2030-03-18", unplaced = listOf(UnplacedTask("a", "tooLong", "x")))))!!
        assertEquals(ScheduleLine("Didn't fit: Longer than any free gap", ScheduleLine.Kind.didntFit), didnt)
        assertEquals(ScheduleLine(TaskPlanning.waiting, ScheduleLine.Kind.waiting), line(auto("a")))
        assertNull(line(TaskItem("a", "a")))
        assertNull(line(TaskItem("a", "a", autoAllocate = true, workMode = TaskWorkMode.meeting)))
        assertNull(line(TaskItem("a", "a", autoAllocate = true, done = true, scheduledAt = at(18, 15))))
    }

    @Test
    fun editorCaptions() {
        assertEquals(listOf(TaskPlanning.autoCaption, TaskPlanning.defaultLengthCaption), TaskPlanning.captions(TaskDraft()))
        assertEquals(listOf(TaskPlanning.autoCaption), TaskPlanning.captions(TaskDraft(estimateMinutes = 45)))
        assertEquals(listOf(TaskPlanning.meetingCaption), TaskPlanning.captions(TaskDraft(workMode = TaskWorkMode.meeting)))
        assertEquals(listOf(TaskPlanning.offCaption), TaskPlanning.captions(TaskDraft(autoAllocate = false)))
        assertEquals("Aura plans 30 min if you don't set a length.", TaskPlanning.defaultLengthCaption)
    }
}
