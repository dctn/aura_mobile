package info.ibtaura.aura.core

import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// A port of AuraKit/AuraAPI.swift. Every call throws `AuraError` on failure.

/** Everything the screens ask the backend. LiveApi talks to dev us-west-1; StubApi answers for tests and CI. */
interface AuraApi {
    // Home
    suspend fun scoresToday(): ScoresToday
    suspend fun events(from: Instant, to: Instant): List<CalendarEvent>
    suspend fun tasks(): List<TaskItem>
    suspend fun alerts(from: String, to: String): List<AlertItem>
    suspend fun markAlertDone(id: String)
    /** A mood check-in, 1 (low) to 5. */
    suspend fun logMood(mood: Int, at: Instant)
    suspend fun dailyMetrics(from: String, to: String): List<DailyMetrics>

    // Search and metrics
    suspend fun catalogue(): List<CatalogueSection>
    suspend fun availability(from: String, to: String): List<FieldAvailability>
    suspend fun series(field: String, from: String, to: String): MetricSeries

    // Sessions
    /**
     * `id` is the caller's and is safe to resend: the same id answers with the session it made. The answer's id can
     * still differ, when another session was already running and this joined it. `recoveryType` is sent only when
     * `kind` is "recovery": the route needs it there and refuses it on any other kind.
     */
    suspend fun startSession(
        id: String,
        kind: String,
        mode: String,
        plannedMinutes: Int?,
        title: String,
        recoveryType: RecoveryType?,
    ): StartedSession
    suspend fun sessionAction(id: String, action: SessionAction)
    suspend fun sessionTrace(id: String): SessionTrace

    // Things a mode adds
    suspend fun addEvent(title: String, start: Instant, end: Instant, kind: String)

    // Event editor
    /**
     * A new event from the editor (POST /v1/calendar/events). 409 `work_limit_reached` when a focus block would pass
     * the day's work limit.
     */
    suspend fun createEvent(draft: EventDraft)
    /**
     * Saves what the editor changed (PATCH /v1/calendar/events/{id} with If-Match). Nothing is sent when nothing
     * changed. 412 when the event changed elsewhere since it was read; on an occurrence it detaches that day.
     */
    suspend fun updateEvent(event: CalendarEvent, to: EventDraft)
    /** DELETE /v1/calendar/events/{id}. On an occurrence only that day goes. 409 on an Apple event. */
    suspend fun deleteEvent(id: String)
    /** A task meant for a day (a `DayKey`), with no time of its own. */
    suspend fun addTask(title: String, plannedFor: String)

    // Tasks page
    /**
     * A new task from the editor (POST /v1/tasks). `id` is the caller's (a lowercase UUID): keep it for retries, as
     * the same id answers with the task already made.
     */
    suspend fun createTask(draft: TaskDraft, id: String = TaskDraft.newId()): TaskItem
    /**
     * PATCH /v1/tasks/{id} with If-Match "v<version>", sending only `changes`. Without a version the task is read
     * first for one. A 412 (changed elsewhere) reads the task again and tries once more. Null, and nothing sent,
     * when nothing changes.
     */
    suspend fun updateTask(id: String, version: Int?, changes: TaskChanges): TaskItem?
    /** POST /v1/tasks/{id}/complete. Safe to repeat. `next` is the new copy of a repeating task. */
    suspend fun completeTask(id: String): TaskCompletion
    /** Back to pending (PATCH status "pending"), which clears completedAt. The same If-Match rules as `updateTask`. */
    suspend fun reopenTask(id: String, version: Int?): TaskItem
    /** DELETE /v1/tasks/{id}. A task that is already gone counts as deleted. */
    suspend fun deleteTask(id: String)

    // Consent
    suspend fun grantHealthConsent()

    // Profile
    /** Sets the profile's time zone when it has none. One already there is never changed. */
    suspend fun setTimeZoneIfMissing(identifier: String)
    /** The profile's work hours, or null when none are set. */
    suspend fun workSchedule(): WorkSchedule?
    /** Writes only the work hours to the profile. Every other profile field is kept. */
    suspend fun setWorkSchedule(schedule: WorkSchedule)

    // Day zones
    /** `from` and `to` are `DayKey`s, at most 31 days apart. One entry per day. */
    suspend fun dayZones(from: String, to: String): List<ZoneDay>

    // Find a time
    /**
     * Places the tasks marked `autoAllocate` on `date` (a `DayKey`). The day window is the profile's work hours when
     * set, else the Planner's 09:00-18:00. `dryRun` writes nothing; `skipCheck` plans today without the Quick check
     * (otherwise 409 `quick_check_required`). `taskIds` narrows the tasks, at most 200; null means all of them.
     */
    suspend fun allocate(date: String, dryRun: Boolean, skipCheck: Boolean, taskIds: List<String>?): DayPlan
    /** The stored plan for `date`, or null when there isn't one yet. */
    suspend fun plan(date: String): DayPlan?

    // Quick check
    /** Sends one Quick check's raw results (POST /v1/checks). Resending the same `id` is safe and returns the stored check. */
    suspend fun submitCheck(run: QuickCheckRun): QuickCheckResult

    // Beta day score
    /** Rates an ended focus session (PUT /v1/sessions/{id}/difficulty). A new answer replaces the cost. */
    suspend fun setDifficulty(sessionId: String, difficulty: Difficulty): SessionRecord
    /** The Capacity ledger's line for a session today ("... You rated it Hard."), or null when it isn't charged yet. */
    suspend fun capacityLine(sessionId: String): String?
    /** Sessions from `from` to `to` (DayKeys), newest first. */
    suspend fun sessions(from: String, to: String): List<SessionRecord>
    /** Records a break taken earlier (POST /v1/sessions/log). Resending the same id is safe. */
    suspend fun logBreak(id: String, type: RecoveryType, startedAt: Instant, minutes: Int): LoggedBreak
    suspend fun recoveryOptions(minutes: Int): List<RecoveryOption>
    /** Blocks to review, `days` back from `date` (a DayKey). */
    suspend fun reviewInbox(date: String, days: Int): List<ReviewItem>
    suspend fun submitReview(answer: ReviewAnswer)
    /** The profile's healthGoals bedtime and wake time, or null when either is missing. */
    suspend fun sleepSchedule(): SleepSchedule?
    suspend fun putSleepNight(night: SleepNight): SleepNightResult

    // Health Connect (Android only, no iOS equivalent yet)
    /** Writes one day of Health Connect data. Returns `written`: false when a newer copy was already there. */
    suspend fun putHealthDay(day: HealthDay): Boolean
    /** Writes 1 to 31 days at once. One (date, written) pair per day. */
    suspend fun putHealthDays(days: List<HealthDay>): List<Pair<String, Boolean>>

    /** The morning check-in. Both times ("HH:mm") or neither; alertness 1...9. */
    suspend fun morningCheckin(alertness: Int?, fellAsleepAt: String?, wokeAt: String?, at: Instant)

    // MARK: Goals
    /** GET /v1/me: healthGoals (null when Identity doesn't send it yet), every setting, and the profile version. */
    suspend fun goalsProfile(): GoalsProfile
    /**
     * PUT /v1/me/profile `{healthGoals: changes}` with If-Match "v<version>"; merged per field, a null value clears one.
     * Keys are `HealthGoals.STEPS` etc. A 412 reads the version again and sends once more. Identity refusing the field
     * throws `AuraError.Server` code `health_goals_refused`.
     */
    suspend fun putHealthGoals(changes: Map<String, JsonElement?>, version: Int): GoalsProfile
    /** Today's steps, active calories and stand hours with Health Data's goal percentages, water ml and focus minutes. */
    suspend fun goalProgressToday(): GoalProgress
    /** GET /v1/habits, every page, by sortOrder. */
    suspend fun habits(includeArchived: Boolean): List<Habit>
    /** POST /v1/habits with a new client id. */
    suspend fun createHabit(draft: HabitDraft): Habit
    /** PATCH /v1/habits/{id} with If-Match: only what changed. Nothing changed sends nothing and returns `habit`. */
    suspend fun updateHabit(habit: Habit, to: HabitDraft): Habit
    suspend fun deleteHabit(id: String)
    /** A check-off for `date` (a DayKey). 409 habit_paused, 400 date_in_future / date_too_old. */
    suspend fun logHabit(habitId: String, date: String, count: Int, minutes: Int): HabitLogResult
    suspend fun deleteHabitLog(habitId: String, logId: String)
    /** Check-offs from `from` to `to` (DayKeys, at most 400 days apart). */
    suspend fun habitLogs(from: String, to: String): List<HabitLog>
    suspend fun habitStats(id: String): HabitStats

    // MARK: Settings (goals)
    /** GET /v1/me's `settings` object, key by key. */
    suspend fun meSettings(): Map<String, JsonElement>
    /** PUT /v1/me/settings with `changes` as the body: merged key by key, a null value removes that key. */
    suspend fun putMeSettings(changes: Map<String, JsonElement?>)

    // MARK: Session history (Search > Sessions; models in SessionHistory.kt)
    /**
     * One page of GET /v1/sessions from `from` to `to` (DayKeys, at most 62 days), oldest first as the route lists
     * them. `kind` filters each page on the server, so a page can be empty and still have a cursor. `limit` 1-500
     * (the route's default is 100). Pass the answer's cursor back for the next page.
     */
    suspend fun sessionPage(
        from: String,
        to: String,
        kind: String? = null,
        cursor: String? = null,
        limit: Int? = null,
    ): SessionPage
    /** The whole session (GET /v1/sessions/{id}). 404 `session_not_found` when it isn't there. */
    suspend fun session(id: String): SessionDetail
    /** XP, level, plant and badges (GET /v1/rewards). */
    suspend fun rewards(): Rewards
    /** The sessions from `from` to `to` (DayKeys) as CSV text (GET /v1/sessions/export?format=csv). 413 when over 5 MB. */
    suspend fun exportSessionsCsv(from: String, to: String): String

    // MARK: Event by id (the event editor after a restart)
    /**
     * One calendar event (GET /v1/calendar/events/{id}); an occurrence id `id@yyyy-MM-dd` works too. Null on a 404,
     * which is how Calendar says it was deleted.
     */
    suspend fun event(id: String): CalendarEvent?

    // MARK: Logbook
    /** GET /v1/logs: manual logs dated `from` to `to` (DayKeys, both included), all types or one. Oldest first. */
    suspend fun logs(from: String, to: String, type: LogType? = null): List<LogEntry>
    /**
     * POST /v1/logs. `date` is a DayKey; `data` values are numbers, text (cut at 500 characters), booleans or null.
     * Resending the same `id` answers with the stored log: logs can't be edited or deleted.
     */
    suspend fun addLog(
        type: LogType,
        date: String,
        takenAt: Instant?,
        data: Map<String, JsonElement>,
        id: String = LogIds.new(),
    ): LogEntry
    /** GET /v1/checkins: check-ins filed `from` to `to` (DayKeys), all kinds or one. Oldest first. */
    suspend fun checkins(from: String, to: String, kind: CheckinKind? = null): List<Checkin>
    /**
     * POST /v1/checkins with the kind's answers (e.g. `mood`, `connection`, `stress`, `effort`, `exhaustion`,
     * `insomnia` as a 7-item array). 400 `invalid_request` without one of `kind.needs`. Resending the same `id` is safe.
     */
    suspend fun addCheckin(
        kind: CheckinKind,
        takenAt: Instant,
        answers: Map<String, JsonElement>,
        id: String = LogIds.new(),
    ): Checkin
    /** The profile's healthGoals.waterGoalMl (GET /v1/me), or null when it isn't set. */
    suspend fun waterGoalMl(): Int?
}

sealed class AuthState {
    data object SignedOut : AuthState()
    data class SignedIn(val email: String?) : AuthState()
}

/** The step between asking for a code and typing it. Sign-in codes have 8 digits, sign-up codes 6. */
data class AuthStep(
    val kind: Kind,
    val email: String,
    val session: String?,
    val codeLength: Int,
    /** An unconfirmed account can hide as a sign-in, so both code lengths are accepted (see cognito-sign-in.md). */
    val alsoConfirm: Boolean = false,
    val destination: String = "",
) {
    enum class Kind { signIn, confirmSignUp }
}

interface AuthService {
    /** Reads the token store: signed in when a usable session is there. */
    suspend fun restore(): AuthState
    suspend fun requestCode(email: String): AuthStep
    suspend fun finish(step: AuthStep, code: String): AuthState
    suspend fun resend(step: AuthStep): AuthStep
    suspend fun signOut()
}

/** Dates the backend reads as yyyy-MM-dd: Gregorian (ISO) whatever the phone's calendar is, in the phone's time zone. */
object DayKey {
    /** The phone's time zone, read on every call so a zone change is picked up. */
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun string(date: Instant, zone: ZoneId = DayKey.zone): String = string(date.atZone(zone).toLocalDate())

    fun string(day: LocalDate): String =
        String.format(Locale.ROOT, "%04d-%02d-%02d", day.year, day.monthValue, day.dayOfMonth)

    /** The start of the day `key` names, or null when it isn't a yyyy-MM-dd date. */
    fun date(key: String, zone: ZoneId = DayKey.zone): Instant? = localDate(key)?.atStartOfDay(zone)?.toInstant()

    /** The day `key` names, or null when it isn't a yyyy-MM-dd date. */
    fun localDate(key: String): LocalDate? {
        val parts = key.take(10).split("-").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        return runCatching { LocalDate.of(parts[0], parts[1], parts[2]) }.getOrNull()
    }
}
