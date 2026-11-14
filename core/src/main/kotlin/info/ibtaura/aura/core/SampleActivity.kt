package info.ibtaura.aura.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.Random
import kotlin.math.roundToInt

// Sample mode's activity for StubApi(sample = true): weeks of believable use, made from the clock. Each day is a pure
// function of its date (a java.util.Random seeded with it), so the same day always looks the same and ids stay
// stable while the app runs. Made up: no real people, places or data.

internal object SampleActivity {
    private const val SEED = 0x5A3D1EL

    /** How many days either side of today have calendar events. */
    const val eventDays = 14

    /** How many days back the logbook goes (today included). */
    const val logDays = 14

    /** Mon-Fri 09:00-18:00 with lunch at 13:00: the week the sample calendar follows. */
    val schedule = WorkSchedule(
        workdayStart = "09:00", workdayEnd = "18:00", lunchStart = "13:00", lunchMinutes = 60, workDays = listOf(1, 2, 3, 4, 5),
    )

    /** The repeating standup: one occurrence a work day, `s-standup@yyyy-MM-dd`. */
    const val standupSeries = "s-standup"

    private const val eventPrefix = "s-ev-"

    /** The task the calendar has a block for today. */
    const val linkedTaskId = "s-task-roadmap"

    /** A Random for one day and one use, so adding a use never changes another's numbers. */
    fun random(day: LocalDate, salt: Int): Random = Random(SEED * 31 + day.toEpochDay() * 1_000 + salt)

    private fun at(day: LocalDate, hour: Int, minute: Int, zone: ZoneId): Instant =
        day.atTime(hour, minute).atZone(zone).toInstant()

    private fun clock(hour: Int, minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

    private fun <T> Random.pick(list: List<T>): T = list[nextInt(list.size)]

    // MARK: Calendar

    private val deepTitles = listOf("Architecture write-up", "Prototype the search API", "Data model for reports")
    private val detailTitles = listOf("Code review batch", "QA pass on the release", "Tidy the test suite")
    private val learningTitles = listOf("Read up on caching", "Course: statistics module", "Study the payments docs")

    /**
     * One day's events: on work days a deep-work hour, the standup, the weekday's meetings, a focus block, lunch and
     * sometimes a walk; evenings a gym hour or a walk and reading; weekends a run and errands. Some days have an
     * all-day item. `today` adds the block linked to `linkedTaskId` when it is that day.
     */
    fun eventsOn(day: LocalDate, today: LocalDate?, zone: ZoneId): List<CalendarEvent> {
        val r = random(day, 1)
        val key = DayKey.string(day)
        val list = mutableListOf<CalendarEvent>()
        fun free(h1: Int, m1: Int, h2: Int, m2: Int): Boolean {
            val start = at(day, h1, m1, zone)
            val end = at(day, h2, m2, zone)
            return list.none { !it.allDay && it.start < end && it.end > start }
        }
        fun add(slug: String, title: String, h1: Int, m1: Int, h2: Int, m2: Int, kind: String, workType: String? = null,
                location: String? = null, notes: String? = null) {
            list += CalendarEvent(
                id = "$eventPrefix$key-$slug", title = title, start = at(day, h1, m1, zone), end = at(day, h2, m2, zone),
                kind = kind, notes = notes, location = location, version = 1, workType = workType, kindSource = "user",
            )
        }
        val weekday = day.dayOfWeek.value
        if (Math.floorMod(day.toEpochDay(), 9L) == 4L) {
            val title = if (weekday <= 5) "Release day" else "Family visit"
            list += CalendarEvent(
                id = "$eventPrefix$key-allday", title = title, start = day.atStartOfDay(zone).toInstant(),
                end = day.plusDays(1).atStartOfDay(zone).toInstant(), kind = if (weekday <= 5) "focus" else "recovery",
                version = 1, allDay = true,
            )
        }
        if (weekday <= 5) {
            add("deep", "Deep work", 9, 0, 9, 50, "focus", "deepFocus")
            list += CalendarEvent(
                id = "$standupSeries@$key", title = "Daily standup", start = at(day, 10, 0, zone), end = at(day, 10, 15, zone),
                kind = "meeting", location = "Video call", seriesId = standupSeries, version = 1, kindSource = "user",
            )
            when (weekday) {
                1 -> {
                    add("planning", "Sprint planning", 11, 0, 12, 0, "meeting", location = "Room 3")
                    add("roadmap", "Roadmap sync", 16, 0, 16, 30, "meeting")
                }
                2 -> {
                    add("one-on-one", "1:1 with manager", 11, 30, 12, 0, "meeting")
                    add("focus", r.pick(detailTitles), 14, 0, 15, 0, "focus", "detailHeavy")
                    add("design", "Design review", 15, 0, 15, 45, "meeting", location = "Room 1")
                }
                3 -> {
                    add("focus", r.pick(deepTitles), 10, 30, 12, 0, "focus", "deepFocus")
                    add("customer", "Customer call", 14, 0, 15, 0, "meeting", location = "Video call",
                        notes = "Walk through the new export screen.")
                }
                4 -> {
                    add("hiring", "Hiring sync", 11, 0, 11, 30, "meeting")
                    add("focus", r.pick(learningTitles), 14, 0, 15, 30, "focus", "learning")
                    add("retro", "Team retro", 16, 0, 17, 0, "meeting", location = "Room 3")
                }
                else -> {
                    add("admin", "Inbox and admin", 11, 0, 11, 45, "focus", "routine")
                    add("demo", "Demo hour", 15, 0, 16, 0, "meeting")
                }
            }
            add("lunch", "Lunch", 13, 0, 14, 0, "recovery")
            if (r.nextInt(3) != 0 && free(15, 30, 15, 45)) add("walk", "Afternoon walk", 15, 30, 15, 45, "recovery")
            if (weekday == 1 || weekday == 3) {
                add("gym", "Gym", 18, 30, 19, 30, "recovery", location = "Gym")
            } else {
                add("evening-walk", "Evening walk", 19, 0, 19, 30, "recovery")
            }
        } else if (weekday == 6) {
            add("run", "Long run", 10, 0, 11, 0, "recovery", location = "Riverside path")
            add("lunch-out", "Lunch with friends", 12, 30, 13, 30, "recovery")
        } else {
            add("errands", "Groceries and meal prep", 11, 0, 12, 0, "recovery")
            add("plan-week", "Plan the week", 18, 0, 18, 30, "focus", "routine")
        }
        add("read", "Read", 21, 30, 22, 0, "recovery")
        if (day == today) linkedBlock(day, list, zone)?.let { list += it }
        return list.sortedBy { it.start }
    }

    /** The first free hour from 14:00 for the roadmap task, in 15-minute steps, or null when the afternoon is full. */
    private fun linkedBlock(day: LocalDate, taken: List<CalendarEvent>, zone: ZoneId): CalendarEvent? {
        var minute = 14 * 60
        while (minute + 60 <= 18 * 60) {
            val start = at(day, minute / 60, minute % 60, zone)
            val end = start.plusSeconds(3600)
            if (taken.none { !it.allDay && it.start < end && it.end > start }) {
                return CalendarEvent(
                    id = "$eventPrefix${DayKey.string(day)}-task", title = "Draft the Q4 roadmap", start = start, end = end,
                    kind = "focus", version = 1, taskId = linkedTaskId, workType = "deepFocus", kindSource = "user",
                )
            }
            minute += 15
        }
        return null
    }

    /** Every event of the days within `eventDays` of `today` that overlaps [from, to), soonest first. */
    fun events(today: LocalDate, from: Instant, to: Instant, zone: ZoneId): List<CalendarEvent> {
        if (to <= from) return emptyList()
        var first = from.atZone(zone).toLocalDate().minusDays(1)
        var last = to.atZone(zone).toLocalDate()
        if (first < today.minusDays(eventDays.toLong())) first = today.minusDays(eventDays.toLong())
        if (last > today.plusDays(eventDays.toLong())) last = today.plusDays(eventDays.toLong())
        val result = mutableListOf<CalendarEvent>()
        var day = first
        while (day <= last) {
            result += eventsOn(day, today, zone).filter { it.start < to && it.end > from }
            day = day.plusDays(1)
        }
        return result.sortedBy { it.start }
    }

    /** A sample event by id, or null when the id isn't one or its day is out of range. */
    fun event(id: String, today: LocalDate, zone: ZoneId): CalendarEvent? {
        val dateText = when {
            id.startsWith(eventPrefix) -> id.removePrefix(eventPrefix).take(10)
            id.startsWith("$standupSeries@") -> id.removePrefix("$standupSeries@")
            else -> return null
        }
        val day = DayKey.localDate(dateText) ?: return null
        if (Math.abs(ChronoUnit.DAYS.between(today, day)) > eventDays) return null
        return eventsOn(day, today, zone).firstOrNull { it.id == id }
    }

    // MARK: Tasks

    /** About ten work tasks over the three lists, four of them done in the last days. Made once, from `now`. */
    fun tasks(now: Instant, zone: ZoneId): List<TaskItem> {
        val today = now.atZone(zone).toLocalDate()
        fun day(offset: Long, hour: Int = 0, minute: Int = 0): Instant = at(today.plusDays(offset), hour, minute, zone)
        fun doneAt(offset: Long, hour: Int, minute: Int): Instant = minOf(day(offset, hour, minute), now.minusSeconds(20 * 60))
        val linked = eventsOn(today, today, zone).firstOrNull { it.taskId == linkedTaskId }
        return listOf(
            TaskItem(id = linkedTaskId, title = "Draft the Q4 roadmap", at = linked?.start, version = 2,
                priority = TaskPriority.high, horizon = TaskHorizon.today, flagged = true, estimateMinutes = 60,
                scheduledAt = linked?.start, blockId = linked?.id, workType = TaskWorkType.deepFocus, demand = 80,
                demandSource = "manual", costPoints = 1.0, notes = "Three themes, one page each. Share before Friday."),
            TaskItem(id = "s-task-incident", title = "Write the incident follow-up", at = day(0, 17, 0), autoAllocate = true,
                version = 1, priority = TaskPriority.urgent, dueAt = day(0, 17, 0), hasTime = true, estimateMinutes = 30,
                workType = TaskWorkType.routine, demand = 40, demandSource = "auto"),
            TaskItem(id = "s-task-review", title = "Review the onboarding pull request", autoAllocate = true, version = 1,
                priority = TaskPriority.medium, estimateMinutes = 45, workType = TaskWorkType.detailHeavy, demand = 60,
                demandSource = "auto", costPoints = 0.5),
            TaskItem(id = "s-task-feedback", title = "Customer feedback call", version = 1, priority = TaskPriority.medium,
                estimateMinutes = 30, workMode = TaskWorkMode.meeting, notes = "Questions are in the shared doc."),
            TaskItem(id = "s-task-demo", title = "Prepare the sprint demo", autoAllocate = true, version = 1,
                priority = TaskPriority.high, dueAt = day(3), horizon = TaskHorizon.week, flagged = true,
                estimateMinutes = 90, workType = TaskWorkType.deepFocus, demand = 75, demandSource = "manual"),
            TaskItem(id = "s-task-dashboard", title = "Update the analytics dashboard", autoAllocate = true, version = 1,
                priority = TaskPriority.medium, horizon = TaskHorizon.week, estimateMinutes = 60,
                workType = TaskWorkType.detailHeavy, demand = 55, demandSource = "auto"),
            TaskItem(id = "s-task-caching", title = "Read the caching design notes", version = 1, priority = TaskPriority.low,
                horizon = TaskHorizon.week, estimateMinutes = 30, workType = TaskWorkType.learning),
            TaskItem(id = "s-task-offsite", title = "Plan the team offsite agenda", version = 1, priority = TaskPriority.medium,
                horizon = TaskHorizon.month, flagged = true, estimateMinutes = 45, workType = TaskWorkType.ideas),
            TaskItem(id = "s-task-ticket", title = "Renew the conference ticket", version = 1, priority = TaskPriority.low,
                dueAt = day(12), horizon = TaskHorizon.month, estimateMinutes = 15, workType = TaskWorkType.routine),
            TaskItem(id = "s-task-flags", title = "Clean up old feature flags", autoAllocate = true, version = 1,
                horizon = TaskHorizon.month, estimateMinutes = 120, workType = TaskWorkType.routine, demand = 35,
                demandSource = "auto"),
            TaskItem(id = "s-task-status", title = "Send the weekly status update", done = true, version = 3,
                priority = TaskPriority.medium, estimateMinutes = 20, completedAt = doneAt(-1, 17, 30)),
            TaskItem(id = "s-task-security", title = "Answer the security questionnaire", done = true, version = 2,
                priority = TaskPriority.high, estimateMinutes = 40, completedAt = doneAt(0, 10, 40)),
            TaskItem(id = "s-task-login", title = "Fix the login redirect bug", done = true, version = 4,
                priority = TaskPriority.urgent, horizon = TaskHorizon.week, estimateMinutes = 90,
                workType = TaskWorkType.deepFocus, completedAt = doneAt(-2, 15, 10)),
            TaskItem(id = "s-task-travel", title = "Book travel for the offsite", done = true, version = 2,
                horizon = TaskHorizon.month, estimateMinutes = 15, completedAt = doneAt(-4, 12, 20)),
        )
    }

    // MARK: Sessions

    private val ratingsByWeight = listOf(
        Difficulty.easy, Difficulty.light, Difficulty.light, Difficulty.normal, Difficulty.normal, Difficulty.normal,
        Difficulty.hard, Difficulty.hard, Difficulty.veryHard,
    )

    private val brainstormNotes = listOf(
        listOf("Try a shorter onboarding: three screens, not five.", "Group the export options by file type."),
        listOf("A weekly digest might replace three emails.", "Check the empty states on the reports page."),
    )

    /**
     * A day's sessions: one for each focus block, most meetings, the afternoon walk, and breathing, meditation, naps
     * and brainstorms on some days. Most focus sessions are rated; about one in nine was stopped early.
     */
    fun sessionPlans(day: LocalDate, zone: ZoneId): List<StubSessionHistory.Plan> {
        val r = random(day, 3)
        val events = eventsOn(day, null, zone).filter { !it.allDay }
        val plans = mutableListOf<StubSessionHistory.Plan>()
        fun clashes(hour: Int, minute: Int, minutes: Int): Boolean {
            val start = at(day, hour, minute, zone)
            val end = start.plusSeconds(minutes * 60L)
            return events.any { it.start < end && it.end > start } ||
                plans.any { p -> val s = at(day, p.hour, p.minute, zone); s < end && s.plusSeconds(p.minutes * 60L) > start }
        }
        for (event in events) {
            val local = event.start.atZone(zone)
            val minutes = ((event.end.epochSecond - event.start.epochSecond) / 60).toInt()
            when {
                event.kind == "focus" -> {
                    val stopped = r.nextInt(9) == 0
                    val interruptions = r.nextInt(4)
                    val active = if (stopped) (minutes * 0.6).roundToInt() else minutes - interruptions * 2
                    val rated = r.nextInt(7) != 0
                    plans += StubSessionHistory.Plan(
                        local.hour, local.minute, "focus", event.title, minutes, if (r.nextInt(3) == 0) "flowtime" else "timer",
                        outcome = if (stopped) "stopped" else "completed", rating = if (rated) r.pick(ratingsByWeight) else null,
                        rated = true, interruptions = interruptions, active = active,
                    )
                }
                event.kind == "meeting" && event.seriesId == null && r.nextInt(4) != 0 ->
                    plans += StubSessionHistory.Plan(local.hour, local.minute, "meeting", event.title, minutes, "meeting",
                        rated = true, interruptions = 0)
                event.title == "Afternoon walk" || event.title == "Evening walk" ->
                    plans += StubSessionHistory.Plan(local.hour, local.minute, "recovery", "Walk", minutes, "walk",
                        RecoveryType.walk, rated = true, interruptions = 0)
                event.title == "Long run" ->
                    plans += StubSessionHistory.Plan(local.hour, local.minute, "recovery", "Time outside", minutes, "walk",
                        RecoveryType.outside, rated = true, interruptions = 0)
            }
        }
        val weekend = day.dayOfWeek.value >= 6
        if (r.nextInt(5) < 2 && !clashes(8, 20, 10)) {
            plans += StubSessionHistory.Plan(8, 20, "recovery", "Morning meditation", 10, "timer", RecoveryType.rest,
                rated = true, interruptions = 0)
        }
        if (r.nextInt(5) < 3 && !clashes(12, 40, 5)) {
            plans += StubSessionHistory.Plan(12, 40, "recovery", "Box breathing", 5, "timer", RecoveryType.rest,
                rated = true, interruptions = 0)
        }
        if (weekend && r.nextInt(2) == 0 && !clashes(15, 0, 20)) {
            plans += StubSessionHistory.Plan(15, 0, "recovery", "Nap", 20, "timer", RecoveryType.nap, rated = true,
                interruptions = 0)
        }
        if (!weekend && r.nextInt(4) == 0 && !clashes(12, 10, 20)) {
            plans += StubSessionHistory.Plan(12, 10, "recovery", "Time outside", 20, "walk", RecoveryType.outside,
                rated = true, interruptions = 0)
        }
        if (Math.floorMod(day.toEpochDay(), 5L) == 0L && !clashes(16, 45, 20)) {
            plans += StubSessionHistory.Plan(16, 45, "brainstorm", "Walking brainstorm", 20, "walk", rated = true,
                interruptions = 0, notes = r.pick(brainstormNotes))
        }
        return plans.sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    /**
     * Yesterday's and today's sessions as ended records (ids `sm-<date>-<n>`), for StubApi to keep as its own; today's
     * only when they ended by `now` and don't overlap `taken` (the seeded 09:00 session). Rated ones carry their rating.
     */
    fun liveSessions(now: Instant, zone: ZoneId, taken: Pair<Instant, Instant>): List<SessionRecord> {
        val today = now.atZone(zone).toLocalDate()
        val records = mutableListOf<SessionRecord>()
        for (back in 1 downTo 0) {
            val day = today.minusDays(back.toLong())
            sessionPlans(day, zone).forEachIndexed { index, plan ->
                val start = at(day, plan.hour, plan.minute, zone)
                val end = start.plusSeconds(plan.minutes * 60L)
                if (back == 0 && (end > now || (start < taken.second && end > taken.first))) return@forEachIndexed
                val focus = plan.kind == "focus"
                val difficulty = if (focus) plan.rating else null
                records += SessionRecord(
                    id = "sm-${DayKey.string(day)}-$index", kind = plan.kind, title = plan.title, startedAt = start,
                    endedAt = end, minutes = plan.active ?: plan.minutes, outcome = plan.outcome ?: "completed",
                    difficulty = difficulty, recoveryType = plan.recoveryType,
                    capacityCost = if (focus) StubApi.charge(difficulty).cost else null,
                )
            }
        }
        return records
    }

    /** Yesterday's and today's ended meetings, focus blocks and breaks (not the standup or lunch), latest five. */
    fun inbox(now: Instant, zone: ZoneId): List<ReviewItem> {
        val today = now.atZone(zone).toLocalDate()
        val events = eventsOn(today.minusDays(1), today, zone) + eventsOn(today, today, zone)
        return events.filter { !it.allDay && it.end <= now && it.seriesId == null && it.title != "Lunch" && it.title != "Read" }
            .sortedByDescending { it.end }.take(5).sortedBy { it.start }
            .map {
                ReviewItem(
                    blockId = it.id, date = DayKey.string(it.start, zone), title = it.title, kind = it.kind, start = it.start,
                    end = it.end, minutes = ((it.end.epochSecond - it.start.epochSecond) / 60).toInt(),
                )
            }
    }

    // MARK: Logbook

    private class Dish(val name: String, val protein: Int, val carbs: Int, val fat: Int)

    private val breakfasts = listOf(
        Dish("Oats with berries", 14, 62, 9), Dish("Greek yoghurt and granola", 22, 48, 10), Dish("Eggs on toast", 21, 32, 16),
        Dish("Banana smoothie", 12, 55, 6), Dish("Idli and sambar", 11, 58, 5), Dish("Avocado toast", 10, 38, 18),
    )
    private val lunches = listOf(
        Dish("Chicken rice bowl", 42, 72, 16), Dish("Lentil soup and bread", 22, 65, 10), Dish("Paneer wrap", 26, 54, 22),
        Dish("Quinoa salad", 18, 52, 17), Dish("Tuna sandwich", 30, 44, 14), Dish("Vegetable biryani", 14, 80, 18),
    )
    private val dinners = listOf(
        Dish("Salmon and greens", 38, 24, 26), Dish("Pasta with tomato", 20, 88, 14), Dish("Dal, rice and salad", 22, 84, 12),
        Dish("Tofu stir-fry with noodles", 26, 70, 18), Dish("Chicken curry and roti", 36, 60, 22), Dish("Bean chilli", 24, 66, 12),
    )
    private val snacks = listOf(
        Dish("Apple and almonds", 6, 22, 12), Dish("Protein bar", 20, 22, 8), Dish("Hummus and carrots", 6, 18, 9),
        Dish("Dark chocolate", 2, 14, 12),
    )
    private val muscleGroups = listOf("Upper body", "Legs", "Core", "Full body")

    private fun round1(value: Double): Double = Math.round(value * 10) / 10.0

    /**
     * `logDays` days of logs and check-ins, today's only up to `now`: three or four meals with calories and macros,
     * water through the day, energy, weight every third day, blood pressure weekly, strength on gym days, TV time and
     * conversations, and morning, mood, connection, block, weekly and insomnia check-ins.
     */
    fun logbook(now: Instant, zone: ZoneId): Pair<List<LogEntry>, List<Checkin>> {
        val today = now.atZone(zone).toLocalDate()
        val logs = mutableListOf<LogEntry>()
        val checkins = mutableListOf<Checkin>()
        for (back in (logDays - 1) downTo 0) {
            val day = today.minusDays(back.toLong())
            val key = DayKey.string(day)
            val r = random(day, 2)
            var n = 0
            fun log(type: LogType, minuteOfDay: Int, vararg data: Pair<String, Any>) {
                val time = at(day, minuteOfDay / 60, minuteOfDay % 60, zone)
                n += 1
                if (time > now) return
                logs += LogEntry("s-log-$key-$n", type, key, time, data.associate { (k, v) -> k to StubLogbook.primitive(v) }, time)
            }
            fun meal(meal: Meal, minuteOfDay: Int, dish: Dish) {
                val portion = 0.9 + r.nextInt(26) / 100.0
                val p = (dish.protein * portion).roundToInt()
                val c = (dish.carbs * portion).roundToInt()
                val f = (dish.fat * portion).roundToInt()
                log(LogType.food, minuteOfDay, "meal" to meal.raw, "name" to dish.name, "kcal" to (4 * p + 4 * c + 9 * f),
                    "proteinG" to p, "carbsG" to c, "fatG" to f, "time" to clock(minuteOfDay / 60, minuteOfDay % 60))
            }
            fun checkin(kind: CheckinKind, minuteOfDay: Int, change: (Checkin) -> Checkin) {
                val time = at(day, minuteOfDay / 60, minuteOfDay % 60, zone)
                n += 1
                if (time > now) return
                checkins += change(Checkin("s-checkin-$key-$n", kind, time, key))
            }
            val weekday = day.dayOfWeek.value
            checkin(CheckinKind.morning, 7 * 60 + 10 + r.nextInt(30)) {
                it.copy(alertness = 5 + r.nextInt(4), stress = 2 + r.nextInt(4),
                    fellAsleepAt = clock(23, 10 + r.nextInt(45)), wokeAt = clock(6, 45 + r.nextInt(15)))
            }
            if (r.nextInt(10) != 0) meal(Meal.breakfast, 7 * 60 + 40 + r.nextInt(50), r.pick(breakfasts))
            var water = 8 * 60 + 15 + r.nextInt(30)
            repeat(4 + r.nextInt(4)) {
                log(LogType.water, water, "ml" to r.pick(listOf(250, 250, 330, 500)))
                water += 100 + r.nextInt(80)
            }
            if (back % 3 == 0) {
                val weight = round1(72.6 - (logDays - 1 - back) * 0.05 + (r.nextInt(7) - 3) / 10.0)
                if (back % 6 == 0) {
                    log(LogType.measurement, 7 * 60 + 5, "weightKg" to weight, "bodyFatPct" to round1(19.8 + (r.nextInt(5) - 2) / 10.0),
                        "muscleMassKg" to round1(33.4 + r.nextInt(4) / 10.0))
                } else {
                    log(LogType.measurement, 7 * 60 + 5, "weightKg" to weight)
                }
            }
            if (back % 7 == 3) {
                log(LogType.measurement, 7 * 60 + 15, "bloodPressureSystolic" to 114 + r.nextInt(9),
                    "bloodPressureDiastolic" to 72 + r.nextInt(8))
            }
            if (weekday <= 5 && r.nextInt(2) == 0) {
                checkin(CheckinKind.block, 11 * 60 + 30) { it.copy(effort = 2 + r.nextInt(3), stress = 3 + r.nextInt(4)) }
            }
            meal(Meal.lunch, 13 * 60 + 5 + r.nextInt(25), r.pick(lunches))
            if (r.nextInt(4) != 0) {
                val energy = 2 + r.nextInt(4)
                if (r.nextInt(3) == 0) {
                    log(LogType.mood, 15 * 60 + r.nextInt(60), "energy" to energy, "onEdge" to 1 + r.nextInt(3), "worry" to 1 + r.nextInt(3))
                } else {
                    log(LogType.mood, 15 * 60 + r.nextInt(60), "energy" to energy)
                }
            }
            if (r.nextInt(5) < 3) meal(Meal.snack, 16 * 60 + r.nextInt(40), r.pick(snacks))
            if (weekday == 1 || weekday == 3) {
                log(LogType.strength, 19 * 60 + 35, "minutes" to 45 + 5 * r.nextInt(3),
                    "group" to muscleGroups[Math.floorMod(day.toEpochDay(), muscleGroups.size.toLong()).toInt()])
            }
            meal(Meal.dinner, 19 * 60 + 40 + r.nextInt(50), r.pick(dinners))
            if (r.nextInt(3) == 0) log(LogType.custom, 20 * 60 + 45, "meaningfulConversations" to 1 + r.nextInt(4))
            if (r.nextInt(3) == 0) log(LogType.custom, 21 * 60 + 15, "tvTimeMin" to r.pick(listOf(30, 45, 60, 90)))
            when (back) {
                6 -> log(LogType.custom, 18 * 60, "supportAtWork" to 4, "workChallenge" to 3)
                10 -> log(LogType.custom, 18 * 60, "stressfulMonths" to 2, "challengeOrHindrance" to 1)
                12 -> log(LogType.food, 9 * 60, "pattern" to "Mixed")
            }
            if (back == 9) checkin(CheckinKind.insomnia, 8 * 60) { it.copy(insomniaScore = 6) }
            if (weekday == 7) checkin(CheckinKind.weekly, 20 * 60) { it.copy(exhaustion = 3 + r.nextInt(3)) }
            if (r.nextInt(5) != 0) checkin(CheckinKind.mood, 21 * 60) { it.copy(mood = if (r.nextInt(8) == 0) 2 else 3 + r.nextInt(3)) }
            if (r.nextInt(3) == 0) checkin(CheckinKind.connection, 21 * 60 + 10) { it.copy(connection = 3 + r.nextInt(3)) }
        }
        return logs to checkins
    }

    // MARK: Habits

    /** One sample habit and how its month went: kept on about `rate` of its days, the last `streak` of them in a row. */
    private class HabitSeed(val habit: Habit, val rate: Double, val streak: Int)

    private val habitSeeds = listOf(
        HabitSeed(Habit(id = "s-habit-read", name = "Read 20 minutes", cue = "Before bed", icon = "star.fill", colorHex = "FF9F0A",
            targetType = HabitTargetType.minutes, targetMinutes = 20, reminder = HabitReminder(true, "21:30"),
            goal = HabitGoal(true, 66, null), version = 1, sortOrder = 0), 0.8, 12),
        HabitSeed(Habit(id = "s-habit-walk", name = "Walk after lunch", cue = "After I finish lunch", icon = "figure.walk",
            colorHex = "34C759", targetType = HabitTargetType.minutes, targetMinutes = 15, version = 1, sortOrder = 1), 0.7, 4),
        HabitSeed(Habit(id = "s-habit-screens", name = "No screens after 23:00", cue = "When the alarm goes at 22:45",
            icon = "moon.fill", colorHex = "5856D6", isNegative = true, version = 1, sortOrder = 2), 0.65, 6),
        HabitSeed(Habit(id = "s-habit-stretch", name = "Stretch", cue = "After I brush my teeth", icon = "leaf.fill",
            colorHex = "AF52DE", frequency = HabitFrequency.specificDays, specificDays = listOf(0, 2, 4),
            reminder = HabitReminder(true, "07:30"), version = 1, sortOrder = 3), 0.85, 5),
        HabitSeed(Habit(id = "s-habit-water", name = "Drink 8 glasses of water", icon = "drop.fill", colorHex = "0088FF",
            targetType = HabitTargetType.count, targetCount = 8, version = 1, sortOrder = 4), 0.6, 3),
    )

    /** How far back the habit logs go. */
    private const val habitDays = 45

    /**
     * Five habits with `habitDays` days of logs (ids `s-hl-<habit>-<date>`), streaks and misses made from each day's
     * Random. Today has only what fits the hour: stretch and water in the morning, the walk after lunch.
     */
    fun habits(now: Instant, zone: ZoneId): Pair<List<Habit>, List<HabitLog>> {
        val today = now.atZone(zone).toLocalDate()
        val local = now.atZone(zone)
        val minuteNow = local.hour * 60 + local.minute
        val logs = mutableListOf<HabitLog>()
        habitSeeds.forEachIndexed { index, seed ->
            val habit = seed.habit
            var position = 0
            for (back in 1..habitDays) {
                val day = today.minusDays(back.toLong())
                if (!scheduled(habit, day)) continue
                position += 1
                val r = random(day, 10 + index)
                val kept = position <= seed.streak || (position != seed.streak + 1 && r.nextDouble() < seed.rate)
                logFor(habit, day, kept, r)?.let { logs += it }
            }
            val r = random(today, 10 + index)
            val fitsNow = when (habit.id) {
                "s-habit-stretch" -> minuteNow >= 7 * 60 + 45
                "s-habit-walk" -> minuteNow >= 14 * 60 + 15
                "s-habit-water" -> minuteNow >= 9 * 60
                "s-habit-read" -> minuteNow >= 22 * 60
                else -> false
            }
            if (fitsNow && scheduled(habit, today)) {
                val log = if (habit.id == "s-habit-water") {
                    HabitLog("s-hl-${habit.id}-${DayKey.string(today)}", habit.id, DayKey.string(today),
                        count = minOf(8, (minuteNow - 7 * 60) / 90))
                } else {
                    logFor(habit, today, true, r)
                }
                log?.takeIf { it.count > 0 }?.let { logs += it }
            }
        }
        return habitSeeds.map { it.habit } to logs
    }

    /** One day's log: kept, or for a count habit also a short day (logged, but under the target). Null when not logged. */
    private fun logFor(habit: Habit, day: LocalDate, kept: Boolean, r: Random): HabitLog? {
        val id = "s-hl-${habit.id}-${DayKey.string(day)}"
        val key = DayKey.string(day)
        return when (habit.targetType) {
            HabitTargetType.count -> HabitLog(id, habit.id, key, count = if (kept) habit.targetCount + r.nextInt(3) else 4 + r.nextInt(4))
            HabitTargetType.minutes -> if (kept) HabitLog(id, habit.id, key, minutes = habit.targetMinutes + 5 * r.nextInt(4)) else null
            else -> if (kept) HabitLog(id, habit.id, key) else null
        }
    }

    /** True when the habit is due on `day`: a specificDays habit only on its days (0 = Monday). */
    fun scheduled(habit: Habit, day: LocalDate): Boolean =
        habit.frequency != HabitFrequency.specificDays || (day.dayOfWeek.value - 1) in habit.specificDays

    private fun keptOn(habit: Habit, dayLogs: List<HabitLog>?): Boolean {
        if (dayLogs.isNullOrEmpty()) return false
        return if (habit.targetType == HabitTargetType.count) dayLogs.sumOf { it.count } >= habit.targetCount else true
    }

    /**
     * The streak worked out from the logs: kept periods in a row up to today, where today (or this week) not kept yet
     * doesn't break it. Days for daily and specificDays habits (only their days count), weeks for weekly and perWeek.
     */
    fun streak(habit: Habit, logs: Collection<HabitLog>, today: LocalDate): HabitStreak {
        val byDay = logs.filter { it.habitId == habit.id }.groupBy { it.date }
        val earliest = byDay.keys.mapNotNull { DayKey.localDate(it) }.minOrNull() ?: return HabitStreak.none
        var best = 0
        var run = 0
        if (habit.frequency == HabitFrequency.weekly || habit.frequency == HabitFrequency.perWeek) {
            val need = if (habit.frequency == HabitFrequency.perWeek) habit.timesPerWeek.coerceAtLeast(1) else 1
            val thisWeek = today.minusDays((today.dayOfWeek.value - 1).toLong())
            var week = earliest.minusDays((earliest.dayOfWeek.value - 1).toLong())
            while (week <= thisWeek) {
                val kept = (0L..6L).count { keptOn(habit, byDay[DayKey.string(week.plusDays(it))]) } >= need
                if (kept) run += 1 else if (week != thisWeek) run = 0
                best = maxOf(best, run)
                week = week.plusDays(7)
            }
        } else {
            var day = earliest
            while (day <= today) {
                if (scheduled(habit, day)) {
                    if (keptOn(habit, byDay[DayKey.string(day)])) run += 1 else if (day != today) run = 0
                    best = maxOf(best, run)
                }
                day = day.plusDays(1)
            }
        }
        return HabitStreak(run, best, if (run > 0) DayKey.string(today) else null)
    }

    /** Stats from the logs: the share of the last 30 due days kept (today only once kept) and the milestone days. */
    fun stats(habit: Habit, logs: Collection<HabitLog>, today: LocalDate): HabitStats {
        val mine = logs.filter { it.habitId == habit.id }
        val byDay = mine.groupBy { it.date }
        var due = 0
        var kept = 0
        for (back in 0L until 30L) {
            val day = today.minusDays(back)
            if (!scheduled(habit, day)) continue
            val done = keptOn(habit, byDay[DayKey.string(day)])
            if (back == 0L && !done) continue
            due += 1
            if (done) kept += 1
        }
        val sorted = mine.sortedBy { it.date }
        return HabitStats(
            habitId = habit.id,
            streak = streak(habit, logs, today),
            completionRate30d = if (due == 0) 0.0 else kept.toDouble() / due,
            totalLogs = mine.size,
            milestones = listOf(7, 21, 30, 66, 100).map { HabitMilestone(it, sorted.getOrNull(it - 1)?.date) },
        )
    }

    // MARK: Goals

    /** Goals of a normal adult: 9,000 steps, 500 kcal, 7 h 45 sleep, 23:15-07:00, 2.5 l of water. */
    val goals = HealthGoals(
        stepsPerDay = 9000, activeKcalPerDay = 500, sleepNeedMinutes = 465, standHoursPerDay = 10,
        bedtime = "23:15", wakeTime = "07:00", waterGoalMl = 2500,
    )

    const val waterGoalMl = 2500
    const val focusGoalMinutes = 150
    const val calorieGoal = 2200

    /**
     * Today's steps, active calories and stand hours so far: nothing before 07:00, a full day's worth by 22:00, with a
     * little of the day's own variation.
     */
    fun progressSoFar(now: Instant, zone: ZoneId): Triple<Double, Double, Double> {
        val local = now.atZone(zone)
        val minute = local.hour * 60 + local.minute
        val share = ((minute - 7 * 60) / (15.0 * 60)).coerceIn(0.0, 1.0)
        val r = random(local.toLocalDate(), 4)
        val steps = Math.round((8_400 + r.nextInt(3_000)) * share).toDouble()
        val kcal = Math.round((420 + r.nextInt(180)) * share).toDouble()
        val stand = Math.floor(11 * share)
        return Triple(steps, kcal, stand)
    }
}
