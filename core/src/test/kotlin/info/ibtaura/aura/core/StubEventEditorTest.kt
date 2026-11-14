package info.ibtaura.aura.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A port of StubEventEditorTests (EventEditorTests.swift): the editor's three routes against the stub. */
class StubEventEditorTest {
    @Test
    fun createChangeAndDelete() = runTest {
        val api = stubApi()
        val now = stubNoon
        val from = stubTime(stubTodayKey, 0)
        val to = from.plusSeconds(8 * 86_400L)
        val start = now.plusSeconds(3 * 3600L)
        api.createEvent(EventDraft(title = "Walk with Ana", start = start, end = start.plusSeconds(1800), kind = "recovery"))
        val listed = api.events(from, to)
        var made = assertNotNull(listed.firstOrNull { it.title == "Walk with Ana" })
        assertEquals("recovery", made.kind)

        val draft = EventDraft(made).copy(title = "Walk with Ana and Sam")
        api.updateEvent(made, draft)
        val relisted = api.events(from, to)
        val madeId = made.id
        made = assertNotNull(relisted.firstOrNull { it.id == madeId })
        assertEquals("Walk with Ana and Sam", made.title)

        api.deleteEvent(made.id)
        val after = api.events(from, to)
        assertFalse(after.any { it.id == madeId })
    }

    @Test
    fun aFixedEventCanBeDeleted() = runTest {
        val api = stubApi()
        val now = stubNoon
        api.deleteEvent("e1")
        val events = api.events(now, now.plusSeconds(86_400))
        assertFalse(events.any { it.id == "e1" })
        assertTrue(events.any { it.id == "e2" })
    }

    // MARK: Events (editor round 2)

    @Test
    fun moveToTomorrowKeepsTheTimeAndBumpsTheVersion() = runTest {
        val api = stubApi()
        val before = assertNotNull(api.event("e2"))
        val moved = api.moveEvent("e2", to = EventMoveTargets.TOMORROW)
        assertEquals(before.start.atZone(DayKey.zone).plusDays(1).toInstant(), moved.start)
        assertEquals(before.end.epochSecond - before.start.epochSecond, moved.end.epochSecond - moved.start.epochSecond)
        assertEquals((before.version ?: 1) + 1, moved.version)
        // It leaves today and shows tomorrow.
        val today = api.events(stubTime(stubTodayKey, 0), stubTime(stubDayKey(1), 0))
        assertFalse(today.any { it.id == "e2" })
        val tomorrow = api.events(stubTime(stubDayKey(1), 0), stubTime(stubDayKey(2), 0))
        assertEquals(moved.start, tomorrow.first { it.id == "e2" }.start)
    }

    @Test
    fun moveToAStartKeepsTheLength() = runTest {
        val api = stubApi()
        val start = stubToday(16, 0)
        val moved = api.moveEvent("e1", start = start)
        assertEquals(start, moved.start)
        assertEquals(start.plusSeconds(15 * 60L), moved.end)
    }

    @Test
    fun nextFreeSkipsBusyTimeInsideTheWorkWindow() = runTest {
        val api = stubApi()
        // e1 12:15-12:30 and e2 12:30-13:30 on the stub's noon clock. A 2-hour block can't start before 13:30.
        val start = stubToday(10, 0)
        api.createEvent(EventDraft(title = "Long", start = start, end = start.plusSeconds(7200)))
        val made = api.events(stubTime(stubTodayKey, 0), stubTime(stubDayKey(1), 0)).first { it.title == "Long" }
        val moved = api.moveEvent(made.id, to = EventMoveTargets.NEXT_FREE)
        assertEquals(stubToday(13, 30), moved.start)
        assertEquals(stubToday(15, 30), moved.end)
    }

    @Test
    fun nextWorkdayKeepsTheTime() = runTest {
        val api = stubApi(workSchedule = StubApi.defaultSchedule.copy(workDays = listOf(1, 2, 3, 4, 5)))
        val before = assertNotNull(api.event("e1"))
        val moved = api.moveEvent("e1", to = EventMoveTargets.NEXT_WORKDAY)
        val day = moved.start.atZone(DayKey.zone)
        assertTrue(day.dayOfWeek.value in 1..5)
        assertEquals(before.start.atZone(DayKey.zone).toLocalTime(), day.toLocalTime())
        assertTrue(day.toLocalDate() > before.start.atZone(DayKey.zone).toLocalDate())
    }

    @Test
    fun planBlocksArePlannerEventsWithTheirTask() = runTest {
        val api = stubApi()
        api.allocate(stubTodayKey, dryRun = false, skipCheck = true, taskIds = null)
        val events = api.events(stubTime(stubTodayKey, 0), stubTime(stubDayKey(1), 0))
        val block = events.first { it.isPlannerBlock && it.taskId != null }
        assertEquals("planner", block.source)
        assertEquals(StubApi.taskTitle(block.taskId), assertNotNull(api.task(assertNotNull(block.taskId))).title)
        assertTrue(block.canMove)
    }

    @Test
    fun unpinTurnsOffAutoAllocate() = runTest {
        val api = stubApi()
        assertTrue(assertNotNull(api.task("t1")).autoAllocate)
        api.unpinTaskFromPlanner("t1")
        val task = assertNotNull(api.task("t1"))
        assertFalse(task.autoAllocate)
        assertEquals(2, task.version)
        // A task that isn't there is left alone.
        api.unpinTaskFromPlanner("nope")
    }

    @Test
    fun workTypeAndHighStakesAreSaved() = runTest {
        val api = stubApi()
        val event = assertNotNull(api.event("e2"))
        assertEquals("deepFocus", event.workType)
        api.updateEvent(event, EventDraft(event).copy(workType = "ideas", highStakes = true))
        val saved = assertNotNull(api.event("e2"))
        assertEquals("ideas", saved.workType)
        assertTrue(saved.highStakes)
    }
}
