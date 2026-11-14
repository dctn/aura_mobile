package info.ibtaura.aura.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// A port of AuraKit/StubAPI.swift (StubAPI, StubCheck, StubPlan, StubZones, StubAuth).

/**
 * Answers without a network, with the Figma's numbers, for UI tests and CI. Holds no real data.
 *
 * Null `workSchedule` starts with no work hours set, as a new account does (`--uitesting-no-schedule`).
 * `joinsRunningSession` starts with a session already running on the desktop, which the first start joins until it is
 * stopped (`--uitesting-joined-session`). `needsQuickCheck` makes Find a time for today answer 409
 * `quick_check_required` unless `skipCheck` is sent or a valid Quick check was taken today (`--uitesting-needs-check`).
 * `shortChecksCount` makes any Quick check with at least one reaction valid, whatever its length
 * (`--uitesting-short-check`). `now` is the clock (Android only; Swift reads `Date()`). Days are in `DayKey.zone`.
 */
class StubApi(
    workSchedule: WorkSchedule? = defaultSchedule,
    joinsRunningSession: Boolean = false,
    private val needsQuickCheck: Boolean = false,
    private val shortChecksCount: Boolean = false,
    private val now: AuraNow = realNow,
) : AuraApi {
    /** Swift's actor/lock: every piece of state below is read and written under it. */
    private val mutex = Mutex()
    private var held: WorkSchedule? = workSchedule
    /** True while the other device's session is still running and a start joins it. */
    private var otherRunning: Boolean = joinsRunningSession
    /** Find a time's real runs, one per date (a `DayKey`). */
    private val savedPlans = HashMap<String, DayPlan>()
    /** Quick checks by id, as first answered, so a resend gets the same answer. */
    private val savedChecks = HashMap<String, QuickCheckResult>()
    /** The first valid Quick check of each day (a `DayKey`): it clears the gate and is the plan's `quickCheckAt`. */
    private val firstValidCheck = HashMap<String, Instant>()
    /** Valid checks taken here, added to Attention's `checksCounted`. */
    private var countedChecks = 0
    /** Sessions started here (not joined), by id; ended ones are listed by `sessions(from, to)`. */
    private val startedSessions = HashMap<String, SessionRecord>()
    /** Ratings by session id, the seeded session's included. */
    private val ratings = HashMap<String, Difficulty>()
    /** Breaks logged afterwards, by id. */
    private val loggedBreaks = LinkedHashMap<String, SessionRecord>()
    /** Review answers by block id. */
    private val reviews = HashMap<String, ReviewAnswer>()
    /** Sleep nights by date. */
    private val nights = HashMap<String, SleepNight>()
    /** Health Connect days by date (Android only). */
    private val healthDays = HashMap<String, HealthDay>()
    /** The last morning check-in, for tests. */
    private var morning: StubMorningCheckin? = null
    /** Events made or changed in the editor, by id; they replace a fixed or planned event with the same id. */
    private val editedEvents = LinkedHashMap<String, CalendarEvent>()
    /** Ids deleted in the editor. */
    private val deletedEvents = HashSet<String>()

    private val zone: ZoneId get() = DayKey.zone

    private fun day(offset: Long): String = DayKey.string(now().atZone(zone).toLocalDate().plusDays(offset))

    // MARK: Home

    /** The Figma's numbers. Each valid Quick check taken here adds one to Attention's `checksCounted`. */
    override suspend fun scoresToday(): ScoresToday = scores(mutex.withLock { countedChecks })

    /**
     * Always ahead of the clock, so Upcoming shows them whenever CI runs. Then the blocks of Find a time's real runs
     * that start in [from, to), as the Planner writes them to the calendar. A dry run writes nothing.
     */
    override suspend fun events(from: Instant, to: Instant): List<CalendarEvent> {
        val now = now()
        val nap = now.plusSeconds(15 * 60)
        val building = nap.plusSeconds(15 * 60)
        val fixed = listOf(
            CalendarEvent(id = "e1", title = "10 Min nap", start = nap, end = building, kind = "recovery"),
            CalendarEvent(id = "e2", title = "Xcode Building", start = building, end = building.plusSeconds(3600), kind = "focus"),
        )
        return mutex.withLock {
            val blocks = savedPlans.values.flatMap { it.placed }.filter { it.start >= from && it.start < to }
                .sortedBy { it.start }
            val planned = blocks.map { block ->
                CalendarEvent(
                    id = block.blockId ?: UUID.randomUUID().toString().uppercase(Locale.ROOT),
                    title = block.title(taskTitle(block.taskId)), start = block.start, end = block.end, kind = block.kind,
                )
            }
            applyEdits(fixed + planned, editedEvents.toMap(), deletedEvents.toSet(), from, to)
        }
    }

    override suspend fun tasks(): List<TaskItem> = stubTasks

    override suspend fun alerts(from: String, to: String): List<AlertItem> =
        listOf(AlertItem(id = "a1", featureId = "DX-04", title = "Take a 10 minutes nap", date = day(0), actionRef = "nap-10"))

    override suspend fun markAlertDone(id: String) {}
    override suspend fun logMood(mood: Int, at: Instant) {}

    override suspend fun dailyMetrics(from: String, to: String): List<DailyMetrics> =
        listOf(DailyMetrics(date = day(0), burnoutIndexScore = 72.0, burnoutIndexRisk = "high"))

    // MARK: Search and metrics

    private fun row(name: String, field: String?, unit: String): CatalogueParameter =
        CatalogueParameter(name = name, field = field, read = if (field == null) "none" else "series", unit = unit)

    override suspend fun catalogue(): List<CatalogueSection> {
        val titles = listOf(
            "Running", "Cycling", "Gym and Strength", "Swimming", "Winter Sports", "Body Metrics", "Heart", "Stress",
            "Sleep", "Recovery", "Focus", "Screen Wellness", "Meetings", "Mental Wellness", "Respiration", "Mobility",
            "Nutrition", "Cycle Tracking", "Hearing", "Symptoms", "Burnout", "Habits", "Progress", "Other",
        )
        val movement = CatalogueSection(
            id = "movement", title = "Movement",
            parameters = listOf(
                row("Step Count", "stepCount", "steps"), row("Walk Distance", "walkDistanceKm", "km"),
                row("Active Calories", "activeCalories", "kcal"), row("Resting Calories", "restingCalories", "kcal"),
                row("Total Calories", "totalCalories", "kcal"), row("Exercise Duration", "exerciseMinutes", "min"),
                row("Run Distance", null, "km"), row("Active Minutes", null, "min"), row("Standing Hours", null, "h"),
                row("Stairs Climbed", null, "floors"), row("Physical Effort Score", null, ""),
                row("Workout Effort Rating", null, ""), row("Movement Time", null, "min"), row("Push Count", null, ""),
                row("Wheelchair Distance", null, "km"), row("Daily Step Goal Progress", null, "%"),
                row("Active Calorie Goal Progress", null, "%"), row("Stand Hours Goal Progress", null, "%"),
                row("Inactivity Minutes", null, "min"), row("Move Reminder Count", null, ""),
            ),
        )
        return listOf(movement) + titles.map {
            CatalogueSection(id = it.lowercase(Locale.ROOT), title = it, parameters = emptyList())
        }
    }

    override suspend fun availability(from: String, to: String): List<FieldAvailability> {
        val latest = listOf(
            "stepCount" to 8200.0, "walkDistanceKm" to 3.4, "activeCalories" to 420.0,
            "restingCalories" to 1500.0, "totalCalories" to 1920.0, "exerciseMinutes" to 32.0,
        )
        return latest.map { FieldAvailability(field = it.first, latestDate = day(0), latestNumber = it.second) }
    }

    /** The Walk Distance bars from the Figma, repeated across the range. */
    override suspend fun series(field: String, from: String, to: String): MetricSeries {
        val bars = listOf(2.3, 3.8, 3.1, 4.5, 3.6, 2.2, 3.0, 4.2, 5.0, 3.5, 3.1, 4.0, 4.5, 3.4)
        val today = now().atZone(zone).toLocalDate()
        val start = DayKey.localDate(from) ?: today
        val end = DayKey.localDate(to) ?: today
        val count = max(1, ChronoUnit.DAYS.between(start, end).toInt() + 1)
        val points = (0 until count).map { i ->
            MetricPoint(date = DayKey.string(start.plusDays(i.toLong())), value = bars[(bars.size - count % bars.size + i) % bars.size])
        }
        return MetricSeries(field = field, unit = "km", points = points)
    }

    // MARK: Sessions

    /**
     * Grants what was asked, open-ended for flowtime as the backend does. While the other device's session runs,
     * answers with that one instead, joined, as the backend's one-running-session rule does. A session of its own is
     * kept, so `sessions(from, to)` lists it once it ends.
     */
    override suspend fun startSession(
        id: String,
        kind: String,
        mode: String,
        plannedMinutes: Int?,
        title: String,
        recoveryType: RecoveryType?,
    ): StartedSession {
        val now = now()
        return mutex.withLock {
            if (otherRunning) {
                return@withLock StartedSession(
                    id = runningElsewhereId, joined = true, plannedMinutes = 50, title = "Deep work",
                    kind = "focus", device = "desktop", endsAt = now.plusSeconds(25 * 60),
                )
            }
            val granted: Int? = if (mode == "flowtime") null else plannedMinutes
            val type: RecoveryType? = if (kind == "recovery") recoveryType else null
            // A resent start keeps the record already there.
            startedSessions.getOrPut(id) {
                SessionRecord(id = id, kind = kind, title = title, startedAt = now, minutes = granted ?: 0, recoveryType = type)
            }
            StartedSession(id = id, plannedMinutes = granted)
        }
    }

    /**
     * Stopping or completing the other device's session lets the next start go through. Ending one of its own records
     * its outcome and end.
     */
    override suspend fun sessionAction(id: String, action: SessionAction) {
        val ends = action == SessionAction.stop || action == SessionAction.complete
        if (!ends) return
        val end = now()
        mutex.withLock {
            if (id == runningElsewhereId) otherRunning = false
            // The first end counts. An open-ended run gets the minutes it ran; a focus session gets its cost.
            val record = startedSessions[id] ?: return@withLock
            if (record.outcome != null) return@withLock
            var minutes = record.minutes
            val start = record.startedAt
            if (minutes <= 0 && start != null) {
                minutes = max(1, roundHalfAway((end.toEpochMilli() - start.toEpochMilli()) / 60_000.0).toInt())
            }
            startedSessions[id] = record.copy(
                outcome = if (action == SessionAction.complete) "completed" else "stopped",
                endedAt = end,
                minutes = minutes,
                capacityCost = if (record.isFocus) charge(ratings[id]).cost else record.capacityCost,
            )
        }
    }

    override suspend fun sessionTrace(id: String): SessionTrace = SessionTrace(status = "noData")
    override suspend fun addEvent(title: String, start: Instant, end: Instant, kind: String) {}
    override suspend fun addTask(title: String, plannedFor: String) {}

    // MARK: Event editor

    override suspend fun createEvent(draft: EventDraft) {
        draft.problem?.let { throw AuraError.Server(code = "validation_failed", message = it, status = 400) }
        val event = CalendarEvent(
            id = UUID.randomUUID().toString().lowercase(Locale.ROOT), title = trimmed(draft.title) ?: "",
            start = draft.start, end = draft.end, kind = draft.kind,
            notes = trimmed(draft.notes), location = trimmed(draft.location), version = 1,
        )
        mutex.withLock { editedEvents[event.id] = event }
    }

    /** Applies what the draft changed; an Apple event only takes a new kind, as the Calendar service allows. */
    override suspend fun updateEvent(event: CalendarEvent, to: EventDraft) {
        val draft = to
        if (draft.changes(event).isEmpty()) return
        var changed = event.copy(kind = draft.kind)
        if (!event.isFromApple) {
            draft.problem?.let { throw AuraError.Server(code = "validation_failed", message = it, status = 400) }
            changed = changed.copy(
                title = trimmed(draft.title) ?: "", start = draft.start, end = draft.end,
                location = trimmed(draft.location), notes = trimmed(draft.notes),
            )
        }
        changed = changed.copy(version = (event.version ?: 1) + 1)
        mutex.withLock { editedEvents[changed.id] = changed }
    }

    override suspend fun deleteEvent(id: String) {
        mutex.withLock {
            deletedEvents.add(id)
            editedEvents.remove(id)
        }
    }

    override suspend fun grantHealthConsent() {}
    override suspend fun setTimeZoneIfMissing(identifier: String) {}

    // MARK: Work hours and day zones

    override suspend fun workSchedule(): WorkSchedule? = mutex.withLock { held }

    override suspend fun setWorkSchedule(schedule: WorkSchedule) {
        mutex.withLock { held = schedule }
    }

    /** One day per date, both ends included, at most 62. Work days are all ready with the same score. */
    override suspend fun dayZones(from: String, to: String): List<ZoneDay> {
        val schedule = workSchedule()
        val zone = zone
        val first = DayKey.localDate(from) ?: return emptyList()
        val last = DayKey.localDate(to) ?: return emptyList()
        val days = mutableListOf<ZoneDay>()
        var date = first
        while (date <= last && days.size < 62) {
            days.add(StubZones.day(DayKey.string(date), schedule, zoneScore, zone))
            date = date.plusDays(1)
        }
        return days
    }

    // MARK: Find a time

    /**
     * The same plan for every day, inside the stub zones. A past date and, with `needsQuickCheck`, today without
     * `skipCheck` are refused as the Planner refuses them, until a valid Quick check is taken today; the plan then
     * names that check's time. A later day is planned as the Planner plans it: with no Quick check gate whatever
     * `skipCheck` says, `quickCheckAt` null, and "Book the dentist" placed (see `StubPlan.build`). A real run keeps the
     * plan for its date, today or later, and `events(from, to)` shows its blocks in that day's range; a dry run doesn't.
     */
    override suspend fun allocate(date: String, dryRun: Boolean, skipCheck: Boolean, taskIds: List<String>?): DayPlan {
        val zone = zone
        val day = DayKey.localDate(date)
            ?: throw AuraError.Server(code = "validation_failed", message = "Send the date as yyyy-MM-dd.", status = 400)
        val key = DayKey.string(day)
        val now = now()
        val today = DayKey.string(now, zone)
        if (key < today) {
            throw AuraError.Server(code = "date_in_past", message = "Plan today or a later day.", status = 400)
        }
        val laterDay = key > today
        return mutex.withLock {
            // Only today has the Quick check gate and a check time; a later day records none.
            val checkAt: Instant? = if (laterDay) null else firstValidCheck[key]
            if (needsQuickCheck && !skipCheck && key == today && checkAt == null) {
                throw AuraError.Server(
                    code = "quick_check_required",
                    message = "Take today's Quick check first, or send skipCheck to plan without it.", status = 409,
                )
            }
            val version = (savedPlans[key]?.version ?: 0) + 1
            val plan = StubPlan.build(
                key = key, day = day, schedule = held, skipCheck = skipCheck, taskIds = taskIds, version = version,
                zone = zone, generatedAt = now, quickCheckAt = checkAt, laterDay = laterDay,
            )
            if (!dryRun) savedPlans[key] = plan
            plan
        }
    }

    override suspend fun plan(date: String): DayPlan? {
        val day = DayKey.localDate(date) ?: return null
        return mutex.withLock { savedPlans[DayKey.string(day)] }
    }

    // MARK: Quick check

    /**
     * Scored as Health Data scores it (see `StubCheck`). A resent id answers with the stored result. A valid check
     * counts toward the normal here (the stub knows no wake time or sleep) and clears its day's Quick check gate.
     * The lock is held for the whole check, so two sends of one id store one result.
     */
    override suspend fun submitCheck(run: QuickCheckRun): QuickCheckResult = mutex.withLock {
        savedChecks[run.id]?.let { return@withLock it }
        val measured = StubCheck.measure(run, shortChecksCount)
        val valid = measured.invalidReason == null
        if (valid) {
            countedChecks += 1
            // The day's first valid check stays the one the plan names.
            val key = DayKey.string(run.takenAt, zone)
            val first = firstValidCheck[key]
            if (first == null || run.takenAt < first) firstValidCheck[key] = run.takenAt
        }
        val result = QuickCheckResult(
            id = run.id, valid = valid, invalidReason = measured.invalidReason,
            meanRtMs = measured.meanRtMs, medianRtMs = measured.medianRtMs,
            lapses = measured.lapses, falseStarts = measured.falseStarts,
            performance = measured.performance, qualifiesForNormal = valid,
            scores = scores(countedChecks),
        )
        savedChecks[run.id] = result
        result
    }

    // MARK: Beta day score

    /** Lists the seeded session, the sessions started here that ended, and the logged breaks, newest first. */
    override suspend fun sessions(from: String, to: String): List<SessionRecord> {
        val now = now()
        val all = mutex.withLock {
            listOf(seeded(now, ratings[seededId], zone)) +
                startedSessions.values.filter { it.outcome != null } + loggedBreaks.values
        }
        val inRange = all.filter { record ->
            val start = record.startedAt ?: return@filter false
            val key = DayKey.string(start, zone)
            key >= from && key <= to
        }
        return inRange.sortedByDescending { it.startedAt ?: Instant.MIN }
    }

    /**
     * Stores the rating and the cost it sets. An unknown id is taken as a focus session the stub just made. Another
     * kind is refused as Session refuses it (409 `not_focus_session`).
     */
    override suspend fun setDifficulty(sessionId: String, difficulty: Difficulty): SessionRecord {
        val now = now()
        return mutex.withLock {
            if (sessionId == seededId) {
                ratings[sessionId] = difficulty
                return@withLock seeded(now, difficulty, zone)
            }
            val record = startedSessions[sessionId] ?: SessionRecord(
                id = sessionId, kind = "focus", startedAt = now.minusSeconds(50 * 60), endedAt = now,
                minutes = 50, outcome = "completed",
            )
            if (!record.isFocus) {
                throw AuraError.Server(code = "not_focus_session", message = "Only a focus session can be rated.", status = 409)
            }
            ratings[sessionId] = difficulty
            val rated = record.copy(difficulty = difficulty, capacityCost = charge(difficulty).cost)
            startedSessions[sessionId] = rated
            rated
        }
    }

    /** Capacity's wording for a rated or unrated focus session; null for any other. */
    override suspend fun capacityLine(sessionId: String): String? = mutex.withLock {
        if (sessionId != seededId) {
            val record = startedSessions[sessionId]
            if (record == null || !record.isFocus || record.outcome == null) return@withLock null
        }
        lineText(ratings[sessionId])
    }

    /** 409 `log_overlaps` when the break overlaps a logged one or the seeded session. A resent id is created false. */
    override suspend fun logBreak(id: String, type: RecoveryType, startedAt: Instant, minutes: Int): LoggedBreak {
        val now = now()
        return mutex.withLock {
            loggedBreaks[id]?.let { return@withLock LoggedBreak(session = it, created = false) }
            val end = startedAt.plusSeconds(minutes * 60L)
            val taken = mutableListOf(seededWindow(now, zone))
            for (record in loggedBreaks.values) {
                val from = record.startedAt
                val to = record.endedAt
                if (from != null && to != null) taken.add(from to to)
            }
            if (taken.any { startedAt < it.second && end > it.first }) {
                throw AuraError.Server(code = "log_overlaps", message = "This break overlaps another session.", status = 409)
            }
            val record = SessionRecord(
                id = id, kind = "recovery", title = type.label, startedAt = startedAt, endedAt = end,
                minutes = minutes, outcome = "logged", recoveryType = type,
            )
            loggedBreaks[id] = record
            LoggedBreak(session = record, created = true)
        }
    }

    /** Capacity's five recovery breaks with their real lifts, in Capacity's order. */
    override suspend fun recoveryOptions(minutes: Int): List<RecoveryOption> = recoveryChoices(minutes)

    /** A meeting and a recovery block today; an answered one drops out. */
    override suspend fun reviewInbox(date: String, days: Int): List<ReviewItem> {
        val answered = mutex.withLock { reviews.keys.toSet() }
        return inboxItems(now(), zone).filter { it.blockId !in answered }
    }

    /** Stored as sent. A recovery block without a type is still taken, as the server takes it. */
    override suspend fun submitReview(answer: ReviewAnswer) {
        mutex.withLock { reviews[answer.item.blockId] = answer }
    }

    override suspend fun sleepSchedule(): SleepSchedule? = SleepSchedule(bedtime = "23:00", wakeTime = "07:00")

    /** Stored by date: created the first time, false for the same date again. */
    override suspend fun putSleepNight(night: SleepNight): SleepNightResult {
        val created = mutex.withLock {
            val isNew = nights[night.date] == null
            nights[night.date] = night
            isNew
        }
        return SleepNightResult(created = created, source = "appleHealth")
    }

    // MARK: Health Connect (Android only)

    /** Stored by date; a later `recordedAt` wins, as on the backend. False when a newer copy was already there. */
    override suspend fun putHealthDay(day: HealthDay): Boolean = mutex.withLock { storeHealthDay(day) }

    /** 1 to 31 days, each stored as `putHealthDay` stores it; 400 `validation_failed` otherwise. */
    override suspend fun putHealthDays(days: List<HealthDay>): List<Pair<String, Boolean>> {
        if (days.isEmpty() || days.size > 31) {
            throw AuraError.Server(code = "validation_failed", message = "Send 1 to 31 days.", status = 400)
        }
        return mutex.withLock { days.map { it.date to storeHealthDay(it) } }
    }

    /** Call with the lock held. */
    private fun storeHealthDay(day: HealthDay): Boolean {
        val stored = healthDays[day.date]
        if (stored != null && stored.recordedAt > day.recordedAt) return false
        healthDays[day.date] = day
        return true
    }

    override suspend fun morningCheckin(alertness: Int?, fellAsleepAt: String?, wokeAt: String?, at: Instant) {
        mutex.withLock { morning = StubMorningCheckin(alertness, fellAsleepAt, wokeAt, at) }
    }

    // MARK: What tests read

    /** The last morning check-in sent. */
    internal suspend fun lastMorningCheckin(): StubMorningCheckin? = mutex.withLock { morning }

    /** Review answers by block id. */
    internal suspend fun savedReviews(): Map<String, ReviewAnswer> = mutex.withLock { reviews.toMap() }

    /** Sleep nights by date. */
    internal suspend fun savedNights(): Map<String, SleepNight> = mutex.withLock { nights.toMap() }

    /** The Health Connect days kept, by date. Public so app tests can read them too. */
    suspend fun savedHealthDays(): Map<String, HealthDay> = mutex.withLock { healthDays.toMap() }

    companion object {
        /** Every day of the week, so CI screenshots show zones on a weekend too. */
        val defaultSchedule = WorkSchedule(
            workdayStart = "09:00", workdayEnd = "18:00", lunchStart = "13:00",
            lunchMinutes = 60, workDays = listOf(1, 2, 3, 4, 5, 6, 7),
        )

        /** The score every stub work day has: a steady day (68-78), so 2 h of heavy work. */
        internal const val zoneScore = 72

        /** The id of the session the stub pretends is running on the person's computer. */
        const val runningElsewhereId = "running-elsewhere"

        /** The seeded focus session: today 09:00-09:50, "Deep work", not rated yet. */
        internal const val seededId = "s-deep"

        /**
         * The stub's tasks, all marked for Find a time. "Book the dentist" comes back unplaced today, as due later, and
         * placed at 15:00 on a later day.
         */
        internal val stubTasks = listOf(
            TaskItem(id = "t1", title = "Reply to Ana", autoAllocate = true),
            TaskItem(id = "t2", title = "Book the dentist", autoAllocate = true),
            TaskItem(id = "t3", title = "Write the review", autoAllocate = true),
        )

        /** Capacity's band for a score, as the stub stands in for it. */
        internal fun level(score: Int): String = StubZones.level(score)

        /** The Planner's heavy minutes for a score's band. */
        internal fun heavyMinutes(score: Int): Int = StubZones.heavyMinutes(score)

        /** The title of one of the stub's tasks, or "" for none. */
        internal fun taskTitle(id: String?): String = stubTasks.firstOrNull { it.id == id }?.title ?: ""

        internal fun scores(counted: Int): ScoresToday = ScoresToday(
            attention = Score(name = "Attention", value = 75.0, checksCounted = counted),
            memory = Score(name = "Memory", value = 50.0),
            thinking = Score(name = "Thinking", value = 50.0),
            focus = Score(name = "Focus", value = 71.0),
        )

        /**
         * The editor's changes on top of the stub's events: deleted ones go, changed ones replace theirs, new ones that
         * overlap [from, to) are added. Soonest first.
         */
        internal fun applyEdits(
            base: List<CalendarEvent>,
            edited: Map<String, CalendarEvent>,
            deleted: Set<String>,
            from: Instant,
            to: Instant,
        ): List<CalendarEvent> {
            val result = base.filter { it.id !in deleted }.map { edited[it.id] ?: it }.toMutableList()
            val known = base.map { it.id }.toSet()
            result += edited.values.filter { it.id !in known && it.id !in deleted && it.start < to && it.end > from }
            return result.sortedBy { it.start }
        }

        /** Capacity's demand for a rating and what 50 minutes at it cost. No rating counts as normal. */
        internal data class Charge(val demand: Int, val cost: Double)

        internal fun charge(difficulty: Difficulty?): Charge = when (difficulty) {
            Difficulty.easy -> Charge(20, 0.1)
            Difficulty.light -> Charge(40, 0.2)
            Difficulty.hard -> Charge(80, 0.8)
            Difficulty.veryHard -> Charge(100, 1.3)
            Difficulty.normal, null -> Charge(60, 0.5)
        }

        /** "50 min of desk work at demand 80: -0.8. You rated it Hard.", without the last sentence when not rated. */
        internal fun lineText(rating: Difficulty?): String {
            val charged = charge(rating)
            val base = "50 min of desk work at demand ${charged.demand}: -${String.format(Locale.ROOT, "%.1f", charged.cost)}."
            return if (rating == null) base else "$base You rated it ${rating.label}."
        }

        /** Today 09:00-09:50 on the phone's clock. */
        internal fun seededWindow(now: Instant, zone: ZoneId = DayKey.zone): Pair<Instant, Instant> {
            val start = now.atZone(zone).toLocalDate().atTime(9, 0).atZone(zone).toInstant()
            return start to start.plusSeconds(50 * 60)
        }

        internal fun seeded(now: Instant, rating: Difficulty?, zone: ZoneId = DayKey.zone): SessionRecord {
            val (start, end) = seededWindow(now, zone)
            return SessionRecord(
                id = seededId, kind = "focus", title = "Deep work", startedAt = start, endedAt = end, minutes = 50,
                outcome = "completed", difficulty = rating, capacityCost = charge(rating).cost,
            )
        }

        /** Capacity's real values (services/capacity/src/service/views.ts), the full lift at 20 minutes. */
        internal fun recoveryChoices(minutes: Int): List<RecoveryOption> {
            val length = min(max(minutes, 0), 20)
            return listOf(
                RecoveryOption(
                    id = "nap", title = "Nap", minutes = length, maxPoints = 10.0, fadesAfterMinutes = 120,
                    note = "A 10-20 minute nap gives a small lift for about two hours; longer naps can bring grogginess first.",
                ),
                RecoveryOption(
                    id = "walk", title = "Walk", minutes = length, maxPoints = 9.0, fadesAfterMinutes = 50,
                    note = "A walk of about 20 minutes just before hard work gives a short lift. It does not pay back sleep debt.",
                ),
                RecoveryOption(
                    id = "rest", title = "Rest", minutes = length, maxPoints = 5.0, fadesAfterMinutes = 40,
                    note = "About 20 minutes of rest away from the screen gives a small lift that fades within about 40 minutes.",
                ),
                RecoveryOption(
                    id = "outside", title = "Time outside", minutes = length, maxPoints = 9.0, fadesAfterMinutes = 50,
                    note = "About 20 minutes outside gives a short lift, like a walk. It does not pay back sleep debt.",
                ),
                RecoveryOption(
                    id = "social", title = "Talk with someone", minutes = length, maxPoints = 5.0, fadesAfterMinutes = 40,
                    note = "About 20 minutes talking with someone gives a small lift that fades within about 40 minutes.",
                ),
            )
        }

        internal fun inboxItems(now: Instant, zone: ZoneId = DayKey.zone): List<ReviewItem> {
            val day = now.atZone(zone).toLocalDate()
            val today = DayKey.string(day)
            fun at(hour: Int, minute: Int): Instant = day.atTime(hour, minute).atZone(zone).toInstant()
            return listOf(
                ReviewItem(
                    blockId = "b-sync", date = today, title = "Team sync", kind = "meeting", start = at(10, 0),
                    end = at(10, 30), minutes = 30,
                ),
                ReviewItem(
                    blockId = "e1-review", date = today, title = "10 Min nap", kind = "recovery", start = at(15, 54),
                    end = at(16, 4), minutes = 10,
                ),
            )
        }

        private fun trimmed(text: String): String? = text.trim().ifEmpty { null }

        internal fun roundHalfAway(value: Double): Double = if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
    }
}

/** One morning check-in as the stub got it. */
internal data class StubMorningCheckin(
    val alertness: Int?,
    val fellAsleepAt: String?,
    val wokeAt: String?,
    val at: Instant,
)

/**
 * Health Data's PVT-B scoring (services/health-data/src/service/attention.ts), for the stub only. The real app shows
 * what the backend sends.
 *
 * The length rules follow the app's `REACTION_TEST_SECONDS` (45 s), not the backend's current 170-200 s / 30 answers,
 * so the stub's checks keep counting; the backend is to change to match.
 */
internal object StubCheck {
    /** `REACTION_TEST_SECONDS` with a little slack for the device's timing, as the backend's 170-200 s around 180 s. */
    const val minSeconds = 40
    const val maxSeconds = 60
    const val lapseMs = 355.0
    const val falseStartMs = 100.0
    /**
     * A 45 s run shows about 16 dots (a dot every 1-4 s, about 2.8 s with the answer); fewer than half that answered
     * means the person stopped, as the backend's 30 of about 62 dots in 3 minutes.
     */
    const val minResponses = 8
    const val maxFalseStartShare = 0.2

    data class Measures(
        val meanRtMs: Double,
        val medianRtMs: Double,
        val lapses: Int,
        val falseStarts: Int,
        val performance: Double,
        val invalidReason: String?,
    )

    /**
     * Reactions under 100 ms count as false starts, not reactions. With `shortChecksCount` any run with a reaction is
     * valid; otherwise it needs 40-60 s, 8+ answers and false starts at most 20% of answers plus taps.
     */
    fun measure(run: QuickCheckRun, shortChecksCount: Boolean): Measures {
        val reactions = run.reactionTimesMs.filter { it >= falseStartMs }
        val falseStarts = run.falseStarts + (run.reactionTimesMs.size - reactions.size)
        var mean = 0.0
        var middle = 0.0
        var lapses = 0
        var performance = 0.0
        if (reactions.isNotEmpty()) {
            lapses = reactions.count { it >= lapseMs }
            mean = rounded(reactions.sum() / reactions.size, 1)
            middle = rounded(median(reactions), 1)
            performance = rounded(1 - (lapses + falseStarts).toDouble() / (reactions.size + falseStarts), 4)
        }
        val reason = if (shortChecksCount && run.reactionTimesMs.isNotEmpty()) {
            null
        } else {
            invalidReason(run, reactions.size, falseStarts)
        }
        return Measures(mean, middle, lapses, falseStarts, performance, reason)
    }

    /** The server's code, checked in the server's order; the screen turns it into words. */
    private fun invalidReason(run: QuickCheckRun, answers: Int, falseStarts: Int): String? {
        if (run.durationSeconds < minSeconds || run.durationSeconds > maxSeconds) return "not_3_minutes"
        if (answers < minResponses) return "too_few_responses"
        if (falseStarts.toDouble() > maxFalseStartShare * (answers + run.falseStarts)) return "too_many_false_starts"
        return null
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun rounded(value: Double, digits: Int): Double {
        val scale = 10.0.pow(digits)
        return StubApi.roundHalfAway(value * scale) / scale
    }
}

/**
 * Find a time's answer for the stub: fixed blocks for the stub tasks. With work hours, a band and a work day they
 * follow the recovery rhythm inside the stub zones; otherwise the plain plan. The real app shows what the Planner sends.
 */
internal object StubPlan {
    private const val early =
        "09:00: early in your working day. Your sharpest hours aren't known yet, so hard work goes first."

    private data class Owned(val owner: String, val block: PlanBlock)

    /**
     * `quickCheckAt` is the day's first valid Quick check taken on the stub. Without one the basis names 08:40, or
     * nothing when `skipCheck` was sent. `laterDay` is a date after today, which the Planner plans without the Quick
     * check: the basis names no check time whatever `skipCheck` says, and "Book the dentist" (due tomorrow, so unplaced
     * today) is placed 15:00-15:30 in either variant, leaving nothing unplaced.
     */
    fun build(
        key: String,
        day: LocalDate,
        schedule: WorkSchedule?,
        skipCheck: Boolean,
        taskIds: List<String>?,
        version: Int,
        zone: ZoneId,
        generatedAt: Instant,
        quickCheckAt: Instant? = null,
        laterDay: Boolean = false,
    ): DayPlan {
        fun time(hour: Int, minute: Int): Instant = StubZones.at(hour * 60 + minute, day, zone)
        val zoneDay = StubZones.day(key, schedule, StubApi.zoneScore, zone)
        val rhythm = zoneDay.status == ZoneStatus.ready && zoneDay.heavyMinutes > 0
        // Each block with the task it belongs to, so a rest goes when its task is left out.
        val blocks: List<Owned>
        val warnings: PlanWarnings
        if (rhythm) {
            blocks = listOf(
                Owned("t3", PlanBlock(taskId = "t3", blockId = "plan-$key-t3-p1", start = time(9, 0), end = time(10, 30),
                    demand = 78.0, workType = "deepFocus", reason = early, part = PlanPart(1, 2))),
                Owned("t3", PlanBlock(taskId = "t3", blockId = "plan-$key-t3-p2", start = time(10, 45), end = time(11, 15),
                    demand = 78.0, workType = "deepFocus", reason = "10:45: the rest of it, after the short recovery.",
                    part = PlanPart(2, 2))),
                Owned("t3", PlanBlock(taskId = null, blockId = "plan-$key-t3-rest", start = time(11, 15), end = time(11, 25),
                    demand = 0.0, reason = "A short rest after deep work.", kind = "recovery")),
                Owned("t1", PlanBlock(taskId = "t1", blockId = "plan-$key-t1", start = time(14, 0), end = time(14, 30),
                    demand = 25.0, workType = "routine",
                    reason = "Light enough for any hour: 14:00 keeps the better hours for harder work.")),
            )
            warnings = PlanWarnings(committedOnRecovery = 1, recoveryMoved = 1)
        } else {
            blocks = listOf(
                Owned("t3", PlanBlock(taskId = "t3", blockId = "plan-$key-t3", start = time(9, 0), end = time(11, 0),
                    demand = 78.0, workType = "deepFocus", reason = early)),
                Owned("t1", PlanBlock(taskId = "t1", blockId = "plan-$key-t1", start = time(11, 15), end = time(11, 45),
                    demand = 25.0, workType = "routine",
                    reason = "Light enough for any hour: 11:15 keeps the better hours for harder work.")),
            )
            warnings = PlanWarnings()
        }
        // A later day has room for the task that is only due later when planned today.
        val dentist = Owned("t2", PlanBlock(taskId = "t2", blockId = "plan-$key-t2", start = time(15, 0), end = time(15, 30),
            demand = 20.0, workType = "routine", reason = "Due soon: 15:00 is a light hour for a short call."))
        val all = if (laterDay) blocks + dentist else blocks
        val wanted: Set<String>? = taskIds?.toSet()
        val placed = all.filter { wanted?.contains(it.owner) ?: true }.map { it.block }
        val tomorrow = DayKey.string(day.plusDays(1))
        val dueLater = if (laterDay) emptyList() else listOf(UnplacedTask("t2", "dueLater", "Planned for $tomorrow."))
        val unplaced = dueLater.filter { wanted?.contains(it.taskId) ?: true }
        // A later day is planned without the Quick check, so it records no check time.
        val checkAt: Instant? = if (laterDay) null else quickCheckAt ?: if (skipCheck) null else time(8, 40)
        val basis = PlanBasis(
            quickCheckAt = checkAt, attention = 71.0, sleepMinutes = 432.0, sharpestHours = null,
            capacityOpening = StubApi.zoneScore.toDouble(), workLimitMinutes = null,
        )
        return DayPlan(date = key, generatedAt = generatedAt, basis = basis, placed = placed, unplaced = unplaced,
            warnings = warnings, version = version)
    }
}

/**
 * The Planner's zone placement (services/planner/src/service/zones/classify.ts), for the stub only. The real app
 * draws what the backend sends.
 */
internal object StubZones {
    private const val maxHeavyBlock = 90
    private const val shortRecovery = 15
    private const val longStretch = 180
    private const val minHeavy = 15
    private const val snap = 15

    private data class Placed(val kind: ZoneKind, val start: Int, val end: Int)

    fun day(key: String, schedule: WorkSchedule?, score: Int, zone: ZoneId): ZoneDay {
        if (schedule == null) return ZoneDay(date = key, status = ZoneStatus.needsSchedule)
        val date = DayKey.localDate(key) ?: return ZoneDay(date = key, status = ZoneStatus.unknown)
        // java.time weekdays already run 1 = Monday ... 7 = Sunday, as the schedule's do.
        if (!schedule.workDays.contains(date.dayOfWeek.value)) return ZoneDay(date = key, status = ZoneStatus.dayOff)
        val placed = classify(schedule, heavyMinutes(score))
        val heavy = placed.filter { it.kind == ZoneKind.heavyWork }.sumOf { it.end - it.start }
        val zones = placed.map { Zone(kind = it.kind, start = at(it.start, date, zone), end = at(it.end, date, zone)) }
        return ZoneDay(
            date = key, status = ZoneStatus.ready, score = score, level = level(score), heavyMinutes = heavy,
            reason = reason(score, heavy), zones = zones,
        )
    }

    /**
     * Capacity's `openingBand` cut points since 9 Nov 2026. The Planner no longer cuts the score itself: it reads the
     * band Capacity sends, so this only stands in for Capacity here.
     */
    fun level(score: Int): String = when {
        score >= 89 -> "peak"
        score >= 79 -> "focused"
        score >= 68 -> "steady"
        score >= 57 -> "shallow"
        else -> "depleted"
    }

    /** The Planner's heavy minutes by band (ZONES.heavyMinutes). */
    fun heavyMinutes(score: Int): Int = when (level(score)) {
        "peak" -> 240
        "focused" -> 180
        "steady" -> 120
        else -> 0
    }

    /** The Planner's `zoneReason` wording. */
    fun reason(score: Int, heavyMinutes: Int): String {
        val base = "Your capacity score is $score"
        if (heavyMinutes > 0) return "$base, so about ${hoursText(heavyMinutes)} of heavy work today, first thing."
        if (level(score) == "depleted") return "$base, so today is for recovery and light work only."
        return "$base, so keep to lighter work today."
    }

    private fun hoursText(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        if (h == 0) return "$m min"
        return if (m == 0) "$h h" else "$h h $m min"
    }

    /** The wall-clock time `minute` after midnight on that day. */
    fun at(minute: Int, day: LocalDate, zone: ZoneId): Instant =
        day.atTime(minute / 60, minute % 60).atZone(zone).toInstant()

    /**
     * Lunch, heavy work from the start of the day in blocks with a short recovery between, then light work, split when
     * a stretch is long. A low day's extra recovery is left out: stub days are never low.
     */
    private fun classify(schedule: WorkSchedule, heavyMinutes: Int): List<Placed> {
        val start = WorkSchedule.minutes(schedule.workdayStart) ?: 0
        val end = WorkSchedule.minutes(schedule.workdayEnd) ?: 0
        val lunch = WorkSchedule.minutes(schedule.lunchStart) ?: start
        val lunchEnd = lunch + schedule.lunchMinutes
        val zones = mutableListOf<Placed>()
        val free = mutableListOf<Pair<Int, Int>>()
        var wanted = heavyMinutes
        for ((from, to) in listOf(start to lunch, lunchEnd to end)) {
            var cursor = from
            while (wanted >= minHeavy && to - cursor >= minHeavy) {
                val block = minOf(maxHeavyBlock, wanted, to - cursor)
                zones.add(Placed(ZoneKind.heavyWork, cursor, cursor + block))
                cursor += block
                wanted -= block
                if (wanted < minHeavy || to - cursor < shortRecovery + minHeavy) break
                zones.add(Placed(ZoneKind.shortRecovery, cursor, cursor + shortRecovery))
                cursor += shortRecovery
            }
            if (to > cursor) free.add(cursor to to)
        }
        for ((from, to) in free) zones.addAll(lightWork(from, to))
        if (schedule.lunchMinutes > 0) zones.add(Placed(ZoneKind.heavyRecovery, lunch, lunchEnd))
        return zones.sortedBy { it.start }
    }

    private fun lightWork(from: Int, to: Int): List<Placed> {
        if (to <= from) return emptyList()
        if (to - from <= longStretch) return listOf(Placed(ZoneKind.lightWork, from, to))
        val middle = from + (to - from - shortRecovery) / 2 / snap * snap
        return lightWork(from, middle) + Placed(ZoneKind.shortRecovery, middle, middle + shortRecovery) +
            lightWork(middle + shortRecovery, to)
    }
}

/** Always signed in, for UI tests and CI. */
class StubAuth : AuthService {
    override suspend fun restore(): AuthState = AuthState.SignedIn(email = null)

    override suspend fun requestCode(email: String): AuthStep =
        AuthStep(kind = AuthStep.Kind.signIn, email = email, session = "stub", codeLength = 8)

    override suspend fun finish(step: AuthStep, code: String): AuthState = AuthState.SignedIn(email = step.email)

    override suspend fun resend(step: AuthStep): AuthStep = step

    override suspend fun signOut() {}
}
