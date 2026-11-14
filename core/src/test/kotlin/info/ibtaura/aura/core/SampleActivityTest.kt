package info.ibtaura.aura.core

import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** StubApi's sample mode (SampleActivity): rich seeds, the same for the same clock, still editable; the default unchanged. */
class SampleActivityTest {
    private val today: LocalDate get() = requireNotNull(DayKey.localDate(stubTodayKey))

    private fun sampleApi(
        now: Instant = stubNoon,
        needsQuickCheck: Boolean = false,
        zoneScoreFor: ((LocalDate) -> Int)? = null,
    ): StubApi = StubApi(
        workSchedule = StubApi.sampleSchedule, needsQuickCheck = needsQuickCheck, now = { now }, sample = true,
        zoneScoreFor = zoneScoreFor,
    )

    private fun dayStart(day: LocalDate): Instant = day.atStartOfDay(DayKey.zone).toInstant()

    @Test
    fun theDefaultStubIsUnchanged() = runTest {
        val api = stubApi()
        assertEquals(listOf("t1", "t2", "t3", "t4"), api.tasks().map { it.id })
        val events = api.events(stubNoon.minusSeconds(86_400L), stubNoon.plusSeconds(2 * 86_400L))
        assertEquals(listOf("e1", "e2"), events.map { it.id })
        assertEquals(listOf("s-deep"), api.sessions(stubTodayKey, stubTodayKey).map { it.id })
        assertEquals(0, api.scoresToday().attention.checksCounted)
        assertEquals(listOf("h-water", "h-walk", "h-read"), api.habits(false).map { it.id })
        assertEquals(listOf("b-sync", "e1-review"), api.reviewInbox(stubTodayKey, 7).map { it.blockId })
        assertEquals(72, api.dayZones(stubTodayKey, stubTodayKey).single().score)
        assertEquals(2000, api.waterGoalMl())
        val week = DayKey.string(today.minusDays(6))
        assertTrue(api.logs(week, stubTodayKey, null).any { it.id == "stub-food-t1" })
    }

    /** Noon on each of the 7 days from today, so every weekday is checked whatever day the tests run. */
    private val weekOfNoons: List<Instant> get() = (0L..6L).map { stubNoon.atZone(DayKey.zone).plusDays(it).toInstant() }

    @Test
    fun sampleModeSeedsEveryStore() = runTest {
        for (noon in weekOfNoons) checkSeeds(noon)
    }

    private suspend fun checkSeeds(noon: Instant) {
        val today = noon.atZone(DayKey.zone).toLocalDate()
        val todayKey = DayKey.string(today)
        val api = sampleApi(noon)
        val tasks = api.tasks()
        assertTrue(tasks.size >= 8)
        assertTrue(tasks.count { it.done } in 3..4)
        assertTrue(tasks.any { it.workMode == TaskWorkMode.meeting })
        assertEquals(setOf(TaskHorizon.today, TaskHorizon.week, TaskHorizon.month), tasks.map { it.horizon }.toSet())

        for (offset in listOf(-7L, -1L, 0L, 1L, 7L)) {
            val day = today.plusDays(offset)
            assertTrue(api.events(dayStart(day), dayStart(day.plusDays(1))).isNotEmpty(), "events on $day")
        }
        val todays = api.events(dayStart(today), dayStart(today.plusDays(1)))
        assertTrue(todays.any { it.end <= noon } && todays.any { it.start >= noon })

        val from = DayKey.string(today.minusDays(30))
        val history = api.sessionPage(from, todayKey, null, null, 500).sessions
        assertTrue(history.size >= 25, "history has ${history.size}")
        assertTrue(history.count { it.record.isFocus && it.record.difficulty != null } > history.count { it.record.isFocus } / 2)
        assertTrue(history.any { it.record.outcome == "stopped" })
        assertTrue(history.any { it.record.kind == "recovery" })
        val rewards = api.rewards()
        assertTrue(rewards.badges.all { it.unlocked })
        assertNotNull(api.session(history.last().id))
        val live = api.sessions(DayKey.string(today.minusDays(1)), todayKey).first { it.id.startsWith("sm-") }
        assertEquals("ready", api.sessionTrace(live.id).status)

        val logs = api.logs(DayKey.string(today.minusDays(13)), todayKey, null)
        assertTrue(logs.map { it.date }.distinct().size >= 10)
        assertTrue(logs.mapNotNull { FoodEntry.of(it) }.size >= 25)
        assertTrue(logs.all { (it.at ?: Instant.MIN) <= noon })
        assertTrue(api.checkins(DayKey.string(today.minusDays(13)), todayKey, CheckinKind.morning).size >= 6)
        assertEquals(2500, api.waterGoalMl())

        val habits = api.habits(false)
        assertTrue(habits.size >= 4)
        assertTrue(habits.any { it.streak.current >= 5 })
        for (habit in habits) {
            val stats = api.habitStats(habit.id)
            assertTrue(stats.totalLogs > 0 && stats.completionRate30d > 0.3, "${habit.name}: $stats")
        }
        val progress = api.goalProgressToday()
        assertTrue((progress.waterMl ?: 0.0) > 0.0 && (progress.focusMinutes ?: 0) >= 50 && (progress.steps ?: 0.0) > 0.0)

        assertEquals(12, api.scoresToday().attention.checksCounted)
        assertTrue(api.reviewInbox(todayKey, 7).isNotEmpty())
    }

    @Test
    fun findATimeTodayNeedsNoQuickCheckAndAvoidsTheCalendar() = runTest {
        for (noon in weekOfNoons) checkFindATime(noon)
    }

    private suspend fun checkFindATime(noon: Instant) {
        val today = noon.atZone(DayKey.zone).toLocalDate()
        val api = sampleApi(noon, needsQuickCheck = true)
        val plan = api.allocate(DayKey.string(today), dryRun = true, skipCheck = false, taskIds = null)
        assertNotNull(plan.basis.quickCheckAt)
        assertTrue(plan.placed.isNotEmpty())
        val busy = api.events(dayStart(today), dayStart(today.plusDays(1))).filter { !it.allDay }
        for (block in plan.placed) {
            assertTrue(block.start >= noon)
            assertFalse(busy.any { it.start < block.end && it.end > block.start }, "${block.taskId} clashes")
        }
    }

    @Test
    fun theSameClockGivesTheSameSample() = runTest {
        val a = sampleApi()
        val b = sampleApi()
        val from = dayStart(today.minusDays(7))
        val to = dayStart(today.plusDays(8))
        assertEquals(a.tasks(), b.tasks())
        assertEquals(a.events(from, to), b.events(from, to))
        val first = DayKey.string(today.minusDays(30))
        assertEquals(a.sessionPage(first, stubTodayKey, null, null, 500), b.sessionPage(first, stubTodayKey, null, null, 500))
        assertEquals(a.logs(first, stubTodayKey, null), b.logs(first, stubTodayKey, null))
        assertEquals(a.habitLogs(first, stubTodayKey), b.habitLogs(first, stubTodayKey))
        assertEquals(a.habits(false), b.habits(false))
    }

    @Test
    fun aSeededEventAndTaskCanBeEdited() = runTest {
        val api = sampleApi()
        val day = today.plusDays(2)
        val from = dayStart(day)
        val to = dayStart(day.plusDays(1))
        val event = api.events(from, to).first { !it.allDay && !it.isSeries }
        assertEquals(event, api.event(event.id))

        api.updateEvent(event, EventDraft(event).copy(title = "Moved and renamed"))
        assertEquals("Moved and renamed", api.events(from, to).first { it.id == event.id }.title)
        val moved = api.moveEvent(event.id, EventMoveTargets.TOMORROW, null, null)
        assertEquals(event.start.plusSeconds(86_400L), moved.start)
        assertFalse(api.events(from, to).any { it.id == event.id })
        assertTrue(api.events(to, dayStart(day.plusDays(2))).any { it.id == event.id })

        api.deleteEvent(event.id)
        assertNull(api.event(event.id))

        val far = today.minusDays(7)
        val old = api.events(dayStart(far), dayStart(far.plusDays(1))).first()
        assertEquals(old, api.event(old.id))

        val task = api.tasks().first { !it.done }
        val renamed = assertNotNull(api.updateTask(task.id, task.version, TaskChanges(title = "Renamed task")))
        assertEquals("Renamed task", renamed.title)
        assertTrue(api.completeTask(task.id).task.done)
        api.deleteTask(task.id)
        assertNull(api.task(task.id))
    }

    @Test
    fun zoneScoreForSetsEachDaysScore() = runTest {
        val api = sampleApi(zoneScoreFor = { if (it == today) 91 else 60 })
        val days = api.dayZones(stubTodayKey, DayKey.string(today.plusDays(6))).filter { it.status == ZoneStatus.ready }
        assertTrue(days.isNotEmpty())
        for (zoneDay in days) assertEquals(if (zoneDay.date == stubTodayKey) 91 else 60, zoneDay.score)
        val workDay = days.first { it.date != stubTodayKey }
        val plan = api.allocate(workDay.date, dryRun = true, skipCheck = true, taskIds = null)
        assertEquals(60.0, plan.basis.capacityOpening)
        assertTrue(plan.unplaced.any { it.reason == "overCapacity" })

        val plain = stubApi()
        assertEquals(72, plain.dayZones(stubTodayKey, stubTodayKey).single().score)
        val scored = StubApi(now = { stubNoon }, zoneScoreFor = { 85 })
        assertEquals(85, scored.dayZones(stubTodayKey, stubTodayKey).single().score)
    }
}
