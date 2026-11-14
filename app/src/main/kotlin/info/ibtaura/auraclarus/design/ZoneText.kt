package info.ibtaura.auraclarus.design

import info.ibtaura.aura.core.WorkSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Words for times and work hours, as `ZoneText` in Home/CalendarZones.swift. Shared by Calendar and Settings. */
object ZoneText {
    private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** "09:00" on the phone's clock, always 24-hour. */
    fun clock(date: Instant, zone: ZoneId = ZoneId.systemDefault()): String = formatter.format(date.atZone(zone))

    /** "09:00–10:30". */
    fun range(start: Instant, end: Instant): String = "${clock(start)}–${clock(end)}"

    /** "2 h", "1 h 30 min", "45 min". */
    fun hours(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        if (h == 0) return "$m min"
        return if (m == 0) "$h h" else "$h h $m min"
    }

    /** "Every day", "Mon–Fri", "Mon, Wed, Fri". Three or more days in a row become a range. */
    fun days(workDays: List<Int>): String {
        val sorted = workDays.filter { it in 1..7 }.toSortedSet().toList()
        if (sorted.size == 7) return "Every day"
        if (sorted.isEmpty()) return "No work days"
        val runs = mutableListOf<MutableList<Int>>()
        for (day in sorted) {
            val last = runs.lastOrNull()?.lastOrNull()
            if (last != null && last == day - 1) runs.last().add(day) else runs.add(mutableListOf(day))
        }
        return runs.joinToString(", ") { run ->
            if (run.size >= 3) "${dayNames[run.first() - 1]}–${dayNames[run.last() - 1]}"
            else run.joinToString(", ") { dayNames[it - 1] }
        }
    }

    /** "09:00–18:00 · Mon–Fri", or "Not set". */
    fun schedule(schedule: WorkSchedule?): String {
        if (schedule == null) return "Not set"
        return "${schedule.workdayStart}–${schedule.workdayEnd} · ${days(schedule.workDays)}"
    }
}
