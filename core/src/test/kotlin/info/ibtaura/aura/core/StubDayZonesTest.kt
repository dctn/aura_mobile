package info.ibtaura.aura.core

import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A port of WorkScheduleRuleTests and DayZonesStubTests (DayZonesTests.swift). */
class StubDayZonesTest {
    private fun changed(change: (WorkSchedule) -> WorkSchedule): WorkSchedule = change(WorkSchedule.suggested)

    // MARK: WorkScheduleRuleTests

    @Test
    fun suggestedIsFine() {
        assertNull(WorkSchedule.suggested.problem)
        assertNull(StubApi.defaultSchedule.problem)
    }

    @Test
    fun everyTimeMustBeAClockTime() {
        assertEquals("Choose every time.", changed { it.copy(workdayStart = "9:00") }.problem)
        assertEquals("Choose every time.", changed { it.copy(workdayEnd = "24:00") }.problem)
        assertEquals("Choose every time.", changed { it.copy(lunchStart = "ab:cd") }.problem)
        assertEquals("Choose every time.", changed { it.copy(lunchStart = "") }.problem)
    }

    @Test
    fun workMustEndAfterItStarts() {
        assertEquals("Work must end after it starts.", changed { it.copy(workdayEnd = "09:00") }.problem)
        assertEquals("Work must end after it starts.", changed { it.copy(workdayEnd = "08:00") }.problem)
    }

    @Test
    fun lunchMustFitInsideWorkHours() {
        assertEquals("Lunch must fit inside your work hours.", changed { it.copy(lunchStart = "08:30") }.problem)
        assertEquals("Lunch must fit inside your work hours.", changed { it.copy(lunchStart = "17:30") }.problem)
        assertNull(changed { it.copy(lunchStart = "17:00") }.problem)
    }

    @Test
    fun lunchLength() {
        assertEquals("Lunch can be at most 4 hours.", changed { it.copy(lunchMinutes = 241) }.problem)
        assertEquals("Lunch can't have a negative length.", changed { it.copy(lunchMinutes = -1) }.problem)
        assertNull(changed { it.copy(lunchMinutes = 0) }.problem)
        assertNull(changed { it.copy(lunchStart = "10:00", lunchMinutes = 240) }.problem)
    }

    @Test
    fun workDays() {
        assertEquals("Choose at least one work day.", changed { it.copy(workDays = emptyList()) }.problem)
        assertEquals("Choose each work day once.", changed { it.copy(workDays = listOf(1, 1)) }.problem)
        assertEquals("Choose each work day once.", changed { it.copy(workDays = listOf(0, 1)) }.problem)
        assertEquals("Choose each work day once.", changed { it.copy(workDays = listOf(8)) }.problem)
    }

    @Test
    fun clockHelpers() {
        assertEquals(570, WorkSchedule.minutes("09:30"))
        assertEquals(1439, WorkSchedule.minutes("23:59"))
        assertNull(WorkSchedule.minutes("+9:30"))
        assertNull(WorkSchedule.minutes("09:30:00"))
        assertEquals("09:30", WorkSchedule.clock(570))
        assertEquals("00:00", WorkSchedule.clock(0))
    }

    // MARK: DayZonesStubTests

    @Test
    fun defaultScheduleGivesAReadyToday() = runTest {
        val stub = stubApi()
        val today = stubTodayKey
        val days = stub.dayZones(from = today, to = today)
        assertEquals(1, days.size)
        val day = days.first()
        assertEquals(today, day.date)
        assertEquals(ZoneStatus.ready, day.status)
        assertEquals(72, day.score)
        assertEquals("steady", day.level)
        assertEquals(120, day.heavyMinutes)
        assertEquals("Your capacity score is 72, so about 2 h of heavy work today, first thing.", day.reason)

        val zones = day.zones
        assertEquals(stubTime(today, 9), zones.first().start)
        assertEquals(stubTime(today, 18), zones.last().end)
        for ((earlier, later) in zones.zipWithNext()) {
            assertEquals(earlier.end, later.start, "Zones touch, with no gap and no overlap")
            assertTrue(earlier.start < earlier.end)
        }
        val heavy = zones.filter { it.kind == ZoneKind.heavyWork }.sumOf { Duration.between(it.start, it.end).seconds }
        assertEquals(120L * 60, heavy)
        assertTrue(zones.contains(Zone(ZoneKind.heavyRecovery, stubTime(today, 13), stubTime(today, 14))))
        assertEquals(
            listOf(
                ZoneKind.heavyWork, ZoneKind.shortRecovery, ZoneKind.heavyWork, ZoneKind.lightWork, ZoneKind.heavyRecovery,
                ZoneKind.lightWork, ZoneKind.shortRecovery, ZoneKind.lightWork,
            ),
            zones.map { it.kind },
        )
        assertEquals(
            listOf(
                stubTime(today, 9), stubTime(today, 10, 30), stubTime(today, 10, 45), stubTime(today, 11, 15),
                stubTime(today, 13), stubTime(today, 14), stubTime(today, 15, 45), stubTime(today, 16),
            ),
            zones.map { it.start },
        )
    }

    /** The stub stands in for Capacity's bands: peak 89+, focused 79, steady 68, shallow 57, depleted under. */
    @Test
    fun stubBandsFollowCapacityCutPoints() {
        val cases = listOf(
            Triple(89, "peak", 240), Triple(88, "focused", 180), Triple(79, "focused", 180), Triple(78, "steady", 120),
            Triple(68, "steady", 120), Triple(67, "shallow", 0), Triple(57, "shallow", 0), Triple(56, "depleted", 0),
        )
        for ((score, level, heavy) in cases) {
            assertEquals(level, StubApi.level(score), "score $score")
            assertEquals(heavy, StubApi.heavyMinutes(score), "score $score")
        }
    }

    @Test
    fun oneDayPerDate() = runTest {
        val days = stubApi().dayZones(from = "2026-11-09", to = "2026-11-15")
        assertEquals(
            listOf("2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13", "2026-11-14", "2026-11-15"),
            days.map { it.date },
        )
        assertTrue(days.all { it.status == ZoneStatus.ready })
    }

    @Test
    fun noScheduleNeedsOne() = runTest {
        val stub = stubApi(workSchedule = null)
        assertNull(stub.workSchedule())
        val today = stubTodayKey
        assertEquals(listOf(ZoneDay(date = today, status = ZoneStatus.needsSchedule)), stub.dayZones(from = today, to = today))
    }

    @Test
    fun weekendIsOffAfterSettingWeekdays() = runTest {
        val stub = stubApi(workSchedule = null)
        stub.setWorkSchedule(WorkSchedule.suggested)
        assertEquals(WorkSchedule.suggested, stub.workSchedule())
        // 13 Nov 2026 is a Friday.
        val days = stub.dayZones(from = "2026-11-13", to = "2026-11-16")
        assertEquals(listOf(ZoneStatus.ready, ZoneStatus.dayOff, ZoneStatus.dayOff, ZoneStatus.ready), days.map { it.status })
        assertEquals(ZoneDay(date = "2026-11-14", status = ZoneStatus.dayOff), days[1])
        assertEquals(ZoneDay(date = "2026-11-15", status = ZoneStatus.dayOff), days[2])
    }

    @Test
    fun noLunchHasNoHeavyRecovery() = runTest {
        val stub = stubApi(
            workSchedule = WorkSchedule(
                workdayStart = "09:00", workdayEnd = "12:00", lunchStart = "12:00", lunchMinutes = 0,
                workDays = listOf(1, 2, 3, 4, 5, 6, 7),
            ),
        )
        val days = stub.dayZones(from = "2026-11-09", to = "2026-11-09")
        val zones = assertNotNull(days.firstOrNull()?.zones)
        assertFalse(zones.any { it.kind == ZoneKind.heavyRecovery })
        assertEquals(stubTime("2026-11-09", 9), zones.first().start)
        assertEquals(stubTime("2026-11-09", 12), zones.last().end)
        assertEquals(120, days.first().heavyMinutes)
    }
}
