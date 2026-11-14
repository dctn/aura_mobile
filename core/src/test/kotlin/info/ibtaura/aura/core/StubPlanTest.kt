package info.ibtaura.aura.core

import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Helpers shared by the Stub*Test files. Swift reads `Date()`; these tests give the stub a fixed clock instead:
// noon today on the phone's zone, so a run near midnight can't flip "today" between two calls.

/** Noon today in `DayKey.zone`. */
internal val stubNoon: Instant = LocalDate.now(DayKey.zone).atTime(12, 0).atZone(DayKey.zone).toInstant()

/** A stub on the fixed clock. */
internal fun stubApi(
    workSchedule: WorkSchedule? = StubApi.defaultSchedule,
    joinsRunningSession: Boolean = false,
    needsQuickCheck: Boolean = false,
    shortChecksCount: Boolean = false,
): StubApi = StubApi(workSchedule, joinsRunningSession, needsQuickCheck, shortChecksCount, now = { stubNoon })

/** That day at that time on the phone's zone. */
internal fun stubTime(key: String, hour: Int, minute: Int = 0): Instant =
    requireNotNull(DayKey.localDate(key)).atTime(hour, minute).atZone(DayKey.zone).toInstant()

/** Today (of the fixed clock) at that time. */
internal fun stubToday(hour: Int, minute: Int): Instant = stubTime(stubTodayKey, hour, minute)

internal val stubTodayKey: String get() = DayKey.string(stubNoon)

internal fun stubDayKey(offset: Long): String = DayKey.string(LocalDate.ofInstant(stubNoon, DayKey.zone).plusDays(offset))

/** The error a call threw, or null. */
internal suspend fun caught(block: suspend () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (error: Throwable) {
        error
    }

/** A port of PlanStubTests.swift. */
class StubPlanTest {
    /** A Monday well ahead of the clock, so the plan never hits the past-date rule. */
    private val monday = "2030-03-18"

    private fun nextDay(key: String): String = DayKey.string(requireNotNull(DayKey.localDate(key)).plusDays(1))

    private fun titles(plan: DayPlan, tasks: List<TaskItem>): List<String> =
        plan.placed.map { block -> block.title(tasks.firstOrNull { it.id == block.taskId }?.title ?: "") }

    private fun assertInOrder(plan: DayPlan) {
        for ((earlier, later) in plan.placed.zipWithNext()) {
            assertTrue(earlier.end <= later.start, "Blocks are in time order with no overlap")
        }
    }

    @Test
    fun todaysDryRunFollowsTheRecoveryRhythm() = runTest {
        val stub = stubApi()
        val today = stubTodayKey
        val plan = stub.allocate(date = today, dryRun = true, skipCheck = false, taskIds = null)
        val tasks = stub.tasks()

        assertEquals(today, plan.date)
        assertEquals(1, plan.version)
        assertEquals(listOf("Write the review (1/2)", "Write the review (2/2)", "Recovery", "Reply to Ana"), titles(plan, tasks))
        assertEquals(listOf("t3", "t3", null, "t1"), plan.placed.map { it.taskId })
        assertEquals(
            listOf("plan-$today-t3-p1", "plan-$today-t3-p2", "plan-$today-t3-rest", "plan-$today-t1"),
            plan.placed.map { it.blockId },
        )
        assertEquals(
            listOf(stubTime(today, 9), stubTime(today, 10, 45), stubTime(today, 11, 15), stubTime(today, 14)),
            plan.placed.map { it.start },
        )
        assertEquals(
            listOf(stubTime(today, 10, 30), stubTime(today, 11, 15), stubTime(today, 11, 25), stubTime(today, 14, 30)),
            plan.placed.map { it.end },
        )
        assertInOrder(plan)
        assertEquals(listOf(PlanPart(1, 2), PlanPart(2, 2), null, null), plan.placed.map { it.part })
        assertEquals(listOf("focus", "focus", "recovery", "focus"), plan.placed.map { it.kind })

        val rest = plan.placed[2]
        assertTrue(rest.isRecovery)
        assertNull(rest.taskId)
        assertNull(rest.workType)
        assertEquals(0.0, rest.demand)
        assertEquals("A short rest after deep work.", rest.reason)
        assertEquals(78.0, plan.placed[0].demand)
        assertEquals("deepFocus", plan.placed[0].workType)
        assertEquals("routine", plan.placed[3].workType)

        val tomorrow = nextDay(today)
        assertEquals(listOf(UnplacedTask("t2", "dueLater", "Planned for $tomorrow.")), plan.unplaced)
        assertEquals(PlanWarnings(committedOnRecovery = 1, recoveryMoved = 1), plan.warnings)
        assertEquals(stubTime(today, 8, 40), plan.basis.quickCheckAt)
        assertEquals(71.0, plan.basis.attention)
        assertEquals(432.0, plan.basis.sleepMinutes)
        assertNull(plan.basis.sharpestHours)
        assertEquals(72.0, plan.basis.capacityOpening)
        assertNull(plan.basis.workLimitMinutes)

        // A dry run writes nothing.
        val from = stubTime(today, 0)
        val to = stubTime(tomorrow, 0)
        val events = stub.events(from, to)
        assertFalse(events.any { it.id.startsWith("plan-") })
        assertFalse(events.any { it.title.startsWith("Write the review") || it.title == "Reply to Ana" })
        assertNull(stub.plan(today))
    }

    @Test
    fun aRealRunIsKeptAndShownOnTheCalendar() = runTest {
        val stub = stubApi()
        val first = stub.allocate(date = monday, dryRun = false, skipCheck = false, taskIds = null)
        assertEquals(first, stub.plan(monday))

        val from = stubTime(monday, 0)
        val to = stubTime(nextDay(monday), 0)
        val events = stub.events(from, to)
        val planned = events.filter { it.id.startsWith("plan-") }
        // A later day also places "Book the dentist".
        assertEquals(
            listOf("Write the review (1/2)", "Write the review (2/2)", "Recovery", "Reply to Ana", "Book the dentist"),
            planned.map { it.title },
        )
        assertEquals(listOf("focus", "focus", "recovery", "focus", "focus"), planned.map { it.kind })
        assertEquals(
            listOf("plan-$monday-t3-p1", "plan-$monday-t3-p2", "plan-$monday-t3-rest", "plan-$monday-t1", "plan-$monday-t2"),
            planned.map { it.id },
        )
        assertEquals(first.placed.map { it.start }, planned.map { it.start })
        assertEquals(first.placed.map { it.end }, planned.map { it.end })
        // The fixed events are still there.
        assertTrue(events.any { it.id == "e1" })
        assertTrue(events.any { it.id == "e2" })

        // Another day's window doesn't show them.
        val dayAfter = stubTime("2030-03-19", 0)
        val later = stub.events(dayAfter, dayAfter.plusSeconds(24 * 60 * 60))
        assertFalse(later.any { it.id.startsWith("plan-") })

        val second = stub.allocate(date = monday, dryRun = false, skipCheck = false, taskIds = null)
        assertEquals(2, second.version)
        assertEquals(2, stub.plan(monday)?.version)

        // A dry run after it answers the next version but keeps the stored one.
        val preview = stub.allocate(date = monday, dryRun = true, skipCheck = false, taskIds = null)
        assertEquals(3, preview.version)
        assertEquals(2, stub.plan(monday)?.version)
    }

    @Test
    fun todayNeedsTheQuickCheckUnlessSkipped() = runTest {
        val stub = stubApi(needsQuickCheck = true)
        val today = stubTodayKey
        val error = assertFailsWith<AuraError.Server> {
            stub.allocate(date = today, dryRun = true, skipCheck = false, taskIds = null)
        }
        assertEquals("quick_check_required", error.serverCode)
        assertEquals(
            AuraError.Server(
                code = "quick_check_required",
                message = "Take today's Quick check first, or send skipCheck to plan without it.", status = 409,
            ),
            error,
        )

        val plan = stub.allocate(date = today, dryRun = true, skipCheck = true, taskIds = null)
        assertNull(plan.basis.quickCheckAt)
        assertEquals(4, plan.placed.size)

        // Only today asks for it.
        val later = stub.allocate(date = monday, dryRun = true, skipCheck = false, taskIds = null)
        assertEquals(5, later.placed.size)
        assertNull(later.basis.quickCheckAt)
    }

    @Test
    fun aLaterDayHasNoQuickCheckGate() = runTest {
        val stub = stubApi(needsQuickCheck = true)
        val tomorrow = stubDayKey(1)
        val plan = stub.allocate(date = tomorrow, dryRun = true, skipCheck = false, taskIds = null)
        assertEquals(tomorrow, plan.date)
        assertNull(plan.basis.quickCheckAt, "A later day is planned without the Quick check")
        assertFalse(plan.placed.isEmpty())

        val skipped = stub.allocate(date = tomorrow, dryRun = true, skipCheck = true, taskIds = null)
        assertNull(skipped.basis.quickCheckAt)
    }

    @Test
    fun aLaterDayPlacesTheDentist() = runTest {
        val stub = stubApi()
        val tomorrow = stubDayKey(1)
        val plan = stub.allocate(date = tomorrow, dryRun = true, skipCheck = false, taskIds = null)
        val tasks = stub.tasks()

        val dentist = requireNotNull(plan.placed.firstOrNull { it.taskId == "t2" })
        assertEquals("plan-$tomorrow-t2", dentist.blockId)
        assertEquals(stubTime(tomorrow, 15), dentist.start)
        assertEquals(stubTime(tomorrow, 15, 30), dentist.end)
        assertEquals(20.0, dentist.demand)
        assertEquals("routine", dentist.workType)
        assertEquals("focus", dentist.kind)
        assertNull(dentist.part)
        assertEquals("Due soon: 15:00 is a light hour for a short call.", dentist.reason)
        assertEquals("Book the dentist", titles(plan, tasks).last())
        assertEquals(emptyList(), plan.unplaced)
        assertInOrder(plan)

        // Today keeps it unplaced, due later.
        val todays = stub.allocate(date = stubTodayKey, dryRun = true, skipCheck = false, taskIds = null)
        assertFalse(todays.placed.any { it.taskId == "t2" })
        assertEquals(listOf(UnplacedTask("t2", "dueLater", "Planned for $tomorrow.")), todays.unplaced)
    }

    @Test
    fun aRealRunForALaterDayShowsOnThatDay() = runTest {
        val stub = stubApi()
        val tomorrow = stubDayKey(1)
        stub.allocate(date = tomorrow, dryRun = false, skipCheck = false, taskIds = null)
        assertEquals(tomorrow, stub.plan(tomorrow)?.date)

        val from = stubTime(tomorrow, 0)
        val to = stubTime(stubDayKey(2), 0)
        val events = stub.events(from, to)
        assertTrue(events.any { it.title == "Book the dentist" && it.id == "plan-$tomorrow-t2" })

        val today = stubTime(stubTodayKey, 0)
        val todays = stub.events(today, from)
        assertFalse(todays.any { it.title == "Book the dentist" })
        assertFalse(todays.any { it.id.startsWith("plan-") })
    }

    @Test
    fun taskIdsNarrowALaterDayToTheDentist() = runTest {
        val stub = stubApi()
        val tomorrow = stubDayKey(1)
        val plan = stub.allocate(date = tomorrow, dryRun = true, skipCheck = false, taskIds = listOf("t2"))
        val tasks = stub.tasks()
        assertEquals(listOf("Book the dentist"), titles(plan, tasks))
        assertEquals(listOf("plan-$tomorrow-t2"), plan.placed.map { it.blockId })
        assertEquals(emptyList(), plan.unplaced)
    }

    @Test
    fun aPastDateIsRefused() = runTest {
        val stub = stubApi()
        val error = assertFailsWith<AuraError.Server> {
            stub.allocate(date = stubDayKey(-1), dryRun = true, skipCheck = true, taskIds = null)
        }
        assertEquals("date_in_past", error.serverCode)
        assertEquals(AuraError.Server(code = "date_in_past", message = "Plan today or a later day.", status = 400), error)
    }

    @Test
    fun noScheduleGivesThePlainPlan() = runTest {
        val stub = stubApi(workSchedule = null)
        val plan = stub.allocate(date = monday, dryRun = true, skipCheck = false, taskIds = null)
        val tasks = stub.tasks()
        // A later day, so "Book the dentist" is placed too.
        assertEquals(listOf("Write the review", "Reply to Ana", "Book the dentist"), titles(plan, tasks))
        assertFalse(plan.placed.any { it.isRecovery })
        assertTrue(plan.placed.all { it.part == null })
        assertEquals(listOf(stubTime(monday, 9), stubTime(monday, 11, 15), stubTime(monday, 15)), plan.placed.map { it.start })
        assertEquals(listOf(stubTime(monday, 11), stubTime(monday, 11, 45), stubTime(monday, 15, 30)), plan.placed.map { it.end })
        assertEquals(listOf("plan-$monday-t3", "plan-$monday-t1", "plan-$monday-t2"), plan.placed.map { it.blockId })
        assertEquals(PlanWarnings(), plan.warnings)
        assertNull(plan.warnings.note)
        assertEquals(emptyList(), plan.unplaced)

        // Today's plain plan leaves it unplaced, due later.
        val todays = stub.allocate(date = stubTodayKey, dryRun = true, skipCheck = false, taskIds = null)
        assertEquals(listOf("Write the review", "Reply to Ana"), titles(todays, tasks))
        assertEquals(listOf(UnplacedTask("t2", "dueLater", "Planned for ${stubDayKey(1)}.")), todays.unplaced)
    }

    @Test
    fun aDayOffGivesThePlainPlan() = runTest {
        val stub = stubApi(workSchedule = WorkSchedule.suggested)
        // 16 Mar 2030 is a Saturday.
        val plan = stub.allocate(date = "2030-03-16", dryRun = true, skipCheck = false, taskIds = null)
        assertEquals(listOf("t3", "t1", "t2"), plan.placed.map { it.taskId })
        assertFalse(plan.placed.any { it.isRecovery })
        assertEquals(PlanWarnings(), plan.warnings)
        assertEquals(emptyList(), plan.unplaced)
        assertNull(plan.basis.quickCheckAt)
    }

    @Test
    fun taskIdsNarrowThePlan() = runTest {
        val stub = stubApi()
        val plan = stub.allocate(date = monday, dryRun = true, skipCheck = false, taskIds = listOf("t1"))
        val tasks = stub.tasks()
        assertEquals(listOf("Reply to Ana"), titles(plan, tasks))
        assertEquals(listOf("t1"), plan.placed.map { it.taskId })
        assertFalse(plan.placed.any { it.isRecovery })
        assertEquals(emptyList(), plan.unplaced)
    }

    @Test
    fun allTasksAreForFindATime() = runTest {
        val tasks = stubApi().tasks()
        assertEquals(listOf("t1", "t2", "t3"), tasks.map { it.id })
        assertEquals(listOf("Reply to Ana", "Book the dentist", "Write the review"), tasks.map { it.title })
        assertTrue(tasks.all { it.autoAllocate })
    }

    @Test
    fun blockTitles() {
        val now = stubNoon
        val focus = PlanBlock(taskId = "t3", blockId = "b1", start = now, end = now, demand = 78.0, reason = "")
        assertEquals("Write the review", focus.title("Write the review"))
        val piece = PlanBlock(taskId = "t3", blockId = "b2", start = now, end = now, demand = 78.0, reason = "", part = PlanPart(2, 3))
        assertEquals("Write the review (2/3)", piece.title("Write the review"))
        val rest = PlanBlock(taskId = null, blockId = "b3", start = now, end = now, demand = 0.0, reason = "", kind = "recovery")
        assertTrue(rest.isRecovery)
        assertEquals("Recovery", rest.title(""))
        assertFalse(focus.isRecovery)
    }

    @Test
    fun warningNotes() {
        assertNull(PlanWarnings(committedOnRecovery = 0, recoveryMoved = 0).note)
        assertEquals(
            "1 of your blocks was on recovery time; the recovery moved to free time.",
            PlanWarnings(committedOnRecovery = 1, recoveryMoved = 1).note,
        )
        assertEquals(
            "2 of your blocks were on recovery time; 1 moved to free time.",
            PlanWarnings(committedOnRecovery = 2, recoveryMoved = 1).note,
        )
        assertEquals(
            "2 of your blocks were on recovery time; no free time to move the recovery.",
            PlanWarnings(committedOnRecovery = 2, recoveryMoved = 0).note,
        )
        assertEquals(
            "2 of your blocks were on recovery time; the recoveries moved to free time.",
            PlanWarnings(committedOnRecovery = 2, recoveryMoved = 2).note,
        )
    }
}
