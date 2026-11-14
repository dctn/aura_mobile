package info.ibtaura.aura.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

// A port of AuraKit/LiveAPI.swift, plus the Android-only Health Connect routes.

/** yyyy-MM-dd ranges, counted on plain dates so daylight saving can't shift a day. */
internal object DayRanges {
    /** Consecutive pieces of at most `maxDays` days, both ends included. A range it can't read goes through whole, for the server to answer. */
    fun split(from: String, to: String, maxDays: Int): List<Pair<String, String>> {
        val first = DayKey.localDate(from)
        val last = DayKey.localDate(to)
        if (first == null || last == null || first > last) return listOf(from to to)
        val pieces = mutableListOf<Pair<String, String>>()
        var start: LocalDate = first
        while (start <= last) {
            val pieceEnd = start.plusDays(maxDays - 1L)
            val end = if (pieceEnd < last) pieceEnd else last
            pieces.add(DayKey.string(start) to DayKey.string(end))
            start = start.plusDays(maxDays.toLong())
        }
        return pieces
    }

    /** The day before `key`, or `key` itself if it can't be read. */
    fun dayBefore(key: String): String {
        val day = DayKey.localDate(key) ?: return key
        return DayKey.string(day.minusDays(1))
    }

    /** `from`, moved up when the range is longer than `maxDays`. */
    fun clampedStart(from: String, to: String, maxDays: Int): String {
        val first = DayKey.localDate(from) ?: return from
        val last = DayKey.localDate(to) ?: return from
        val earliest = last.plusDays(1L - maxDays)
        return if (first < earliest) DayKey.string(earliest) else from
    }
}

/** The backend, reached through `ServiceClient`. Routes and shapes follow packages/contracts/src in aws_docs. */
class LiveApi(
    private val config: AppConfig,
    tokens: AccessTokenSource,
    http: OkHttpClient,
    sleep: AuraSleep = realSleep,
    private val now: AuraNow = realNow,
) : AuraApi {
    private val client = ServiceClient(config, tokens, http, sleep, now)
    private val policyVersion = config.consentPolicyVersion

    companion object {
        /** Longest range the metrics routes take. */
        private const val maxMetricDays = 400

        /** Longest range `GET /v1/calendar/events` takes. */
        private const val maxEventDays = 62
        private const val maxPlannedMinutes = 240

        /** Longest range `GET /v1/planner/zones` takes. */
        private const val maxZoneDays = 31

        /** Most pages `GET /v1/sessions` is followed for. */
        private const val maxSessionPages = 5

        /** Most days `POST /v1/health-connect/days/batch` takes. */
        private const val maxHealthBatchDays = 31

        /** Newest start first; a session without a start goes last. */
        internal fun newestFirst(records: List<SessionRecord>): List<SessionRecord> =
            records.sortedByDescending { it.startedAt ?: Instant.MIN }

        private fun overlaps(event: CalendarEvent, from: Instant, to: Instant): Boolean {
            if (from == to) return event.start <= from && event.end >= from
            return event.start < to && event.end > from
        }

        /** Calendar blocks are focus, meeting or recovery. Anything else is left for the server to classify. */
        private fun blockKind(kind: String): String? = when (kind) {
            "focus", "meeting", "recovery" -> kind
            "walk" -> "recovery"
            else -> null
        }

        /** Ids are chosen here, so resending a create is safe. */
        private fun newId(): String = UUID.randomUUID().toString().lowercase(Locale.ROOT)

        private fun esc(text: String): String = URLEscape.component(text)

        private val badResponse: AuraError
            get() = AuraError.Server("bad_response", "Aura sent an answer this app can't read.", 200)

        private val notAvailable = "Health Connect sync isn't available yet."

        private fun <T> decode(serializer: KSerializer<T>, text: String): T =
            try {
                wireJson.decodeFromString(serializer, text)
            } catch (_: Exception) {
                throw badResponse
            }

        /** The plan in `{"plan": ...}`. One without a readable `generatedAt` is a bad answer, like one that isn't JSON. */
        private fun dayPlan(text: String): DayPlan = decode(WirePlanResponse.serializer(), text).plan.model ?: throw badResponse

        /** The value, or JSON null for a body key that must be present. */
        private fun orNull(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

        private fun orNull(value: Int?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    }

    // MARK: Home

    override suspend fun scoresToday(): ScoresToday {
        val query = mapOf("date" to DayKey.string(now()))
        return get(WireScores.serializer(), ServiceName.healthData, "/v1/scores/today", query).model
    }

    /**
     * Events overlapping `from` to `to`. The route files an event under the local day it starts, so one running past
     * midnight is only found by asking from the day before. The extra ones are dropped here. An event starting more
     * than a day before `from` isn't found.
     */
    override suspend fun events(from: Instant, to: Instant): List<CalendarEvent> {
        if (from > to) return emptyList()
        val byId = LinkedHashMap<String, CalendarEvent>()
        val firstDay = DayRanges.dayBefore(DayKey.string(from))
        for ((rangeFrom, rangeTo) in DayRanges.split(firstDay, DayKey.string(to), maxEventDays)) {
            val query = mapOf("from" to rangeFrom, "to" to rangeTo)
            val wire = allPages(WireEventPage.serializer(), ServiceName.calendar, "/v1/calendar/events", query)
            for (item in wire) {
                val event = item.model ?: continue
                if (overlaps(event, from, to)) byId[event.id] = event
            }
        }
        return byId.values.sortedBy { it.start }
    }

    override suspend fun tasks(): List<TaskItem> =
        allPages(WireTaskPage.serializer(), ServiceName.task, "/v1/tasks", emptyMap()).map { it.model }

    override suspend fun alerts(from: String, to: String): List<AlertItem> {
        val list = get(WireAlertList.serializer(), ServiceName.healthData, "/v1/alerts", mapOf("from" to from, "to" to to))
        return list.alerts.orEmpty().map { it.model }
    }

    override suspend fun markAlertDone(id: String) {
        post(ServiceName.healthData, "/v1/alerts/${esc(id)}/done", JsonObject(emptyMap()))
    }

    override suspend fun logMood(mood: Int, at: Instant) {
        val body = buildJsonObject {
            put("id", newId())
            put("kind", "mood")
            put("takenAt", WireDate.string(at))
            put("mood", mood)
        }
        post(ServiceName.healthData, "/v1/checkins", body)
    }

    override suspend fun dailyMetrics(from: String, to: String): List<DailyMetrics> {
        val days = mutableListOf<DailyMetrics>()
        for ((rangeFrom, rangeTo) in DayRanges.split(from, to, maxMetricDays)) {
            val query = mapOf("from" to rangeFrom, "to" to rangeTo, "fields" to "burnoutIndexScore,burnoutIndexRisk")
            val wire = allPages(WireDayPage.serializer(), ServiceName.healthData, "/v1/metrics/daily", query)
            days.addAll(wire.map { it.model })
        }
        return days
    }

    // MARK: Search and metrics

    override suspend fun catalogue(): List<CatalogueSection> {
        val catalogue = get(WireCatalogue.serializer(), ServiceName.healthData, "/v1/metrics/catalogue", emptyMap())
        return catalogue.sections.orEmpty().map { it.model }
    }

    /** A longer range is clamped to its newest 400 days: this route only answers each field's latest value. */
    override suspend fun availability(from: String, to: String): List<FieldAvailability> {
        val start = DayRanges.clampedStart(from, to, maxMetricDays)
        val answer = get(WireAvailability.serializer(), ServiceName.healthData, "/v1/metrics/availability",
            mapOf("from" to start, "to" to to))
        return answer.fields.orEmpty().map { it.model }
    }

    override suspend fun series(field: String, from: String, to: String): MetricSeries {
        var unit: String? = null
        val points = mutableListOf<MetricPoint>()
        val path = "/v1/metrics/series/${esc(field)}"
        for ((rangeFrom, rangeTo) in DayRanges.split(from, to, maxMetricDays)) {
            val part = get(WireSeries.serializer(), ServiceName.healthData, path, mapOf("from" to rangeFrom, "to" to rangeTo))
            if (unit == null) unit = part.unit
            for (point in part.points.orEmpty()) {
                points.add(MetricPoint(date = point.date, value = WireValue.number(point.value)))
            }
        }
        return MetricSeries(field = field, unit = unit, points = points)
    }

    // MARK: Sessions

    override suspend fun startSession(
        id: String,
        kind: String,
        mode: String,
        plannedMinutes: Int?,
        title: String,
        recoveryType: RecoveryType?,
    ): StartedSession {
        // The route wants the key present: null for an open-ended run. A timer without minutes is refused there.
        val planned: JsonElement =
            if (mode != "flowtime" && plannedMinutes != null && plannedMinutes > 0) {
                JsonPrimitive(minOf(plannedMinutes, maxPlannedMinutes))
            } else {
                JsonNull
            }
        val body = buildJsonObject {
            put("id", id)
            put("kind", kind)
            put("mode", mode)
            put("plannedMinutes", planned)
            put("title", title)
            put("device", "phone")
            // Needed on a recovery session (400 recovery_type_required) and refused on any other kind.
            if (kind == "recovery" && recoveryType != null) put("recoveryType", recoveryType.raw)
        }
        val text = client.send(ServiceName.session, "POST", "/v1/sessions", body = body.toString())
        return decode(WireStartedSession.serializer(), text).model
    }

    override suspend fun sessionAction(id: String, action: SessionAction) {
        post(ServiceName.session, "/v1/sessions/${esc(id)}/actions", buildJsonObject { put("action", action.raw) })
    }

    override suspend fun sessionTrace(id: String): SessionTrace {
        // Session history: `model` also reads the heart-rate and stress points (SessionHistoryWire.kt).
        return get(WireTrace.serializer(), ServiceName.healthData, "/v1/trends/sessions/${esc(id)}", emptyMap()).model
    }

    // MARK: Things a mode adds

    override suspend fun addEvent(title: String, start: Instant, end: Instant, kind: String) {
        val body = buildJsonObject {
            put("id", newId())
            put("title", title)
            put("start", WireDate.string(start))
            put("end", WireDate.string(end))
            blockKind(kind)?.let { put("kind", it) }
        }
        post(ServiceName.calendar, "/v1/calendar/events", body)
    }

    // MARK: Event editor

    override suspend fun createEvent(draft: EventDraft) {
        val body = JsonObject(draft.createBody() + ("id" to JsonPrimitive(newId())))
        post(ServiceName.calendar, "/v1/calendar/events", body)
    }

    /** Without a version from the read, the event is read again for one (GET /v1/calendar/events/{id}). */
    override suspend fun updateEvent(event: CalendarEvent, to: EventDraft) {
        val changes = to.changes(event)
        if (changes.isEmpty()) return
        val path = "/v1/calendar/events/${esc(event.id)}"
        val version = event.version
            ?: get(WireEventAnswer.serializer(), ServiceName.calendar, path, emptyMap()).event.version
            ?: throw badResponse
        client.send(ServiceName.calendar, "PATCH", path, body = changes.toString(),
            headers = mapOf("If-Match" to "\"v$version\""))
    }

    override suspend fun deleteEvent(id: String) {
        client.send(ServiceName.calendar, "DELETE", "/v1/calendar/events/${esc(id)}")
    }

    override suspend fun addTask(title: String, plannedFor: String) {
        val body = buildJsonObject {
            put("id", newId())
            put("title", title)
            put("plannedFor", plannedFor)
        }
        post(ServiceName.task, "/v1/tasks", body)
    }

    // MARK: Tasks page

    override suspend fun createTask(draft: TaskDraft, id: String): TaskItem {
        val body = JsonObject(draft.createBody() + ("id" to JsonPrimitive(id)))
        val text = client.send(ServiceName.task, "POST", "/v1/tasks", body = body.toString())
        return decode(WireTaskAnswer.serializer(), text).task.model
    }

    override suspend fun updateTask(id: String, version: Int?, changes: TaskChanges): TaskItem? {
        if (changes.isEmpty) return null
        return patchTask(id, version, changes.json())
    }

    override suspend fun completeTask(id: String): TaskCompletion {
        val text = client.send(ServiceName.task, "POST", "/v1/tasks/${esc(id)}/complete", body = "{}")
        val answer = decode(WireTaskAnswer.serializer(), text)
        return TaskCompletion(task = answer.task.model, next = answer.next?.model)
    }

    override suspend fun reopenTask(id: String, version: Int?): TaskItem =
        patchTask(id, version, TaskChanges(status = TaskStatus.pending).json())

    override suspend fun deleteTask(id: String) {
        try {
            client.send(ServiceName.task, "DELETE", "/v1/tasks/${esc(id)}")
        } catch (error: AuraError.Server) {
            if (error.status != 404) throw error
        }
    }

    /**
     * PATCH with If-Match. Without a version, or after a 412 (someone changed it since it was read), the task is read
     * again (GET /v1/tasks/{id}) for the current one and the change goes once more; a second 412 is thrown.
     */
    private suspend fun patchTask(id: String, version: Int?, body: JsonObject): TaskItem {
        val path = "/v1/tasks/${esc(id)}"
        var current = version ?: taskVersion(path)
        var retried = false
        while (true) {
            try {
                val text = client.send(ServiceName.task, "PATCH", path, body = body.toString(),
                    headers = mapOf("If-Match" to "\"v$current\""))
                return decode(WireTaskAnswer.serializer(), text).task.model
            } catch (error: AuraError.Server) {
                if (error.status != 412 || retried) throw error
                retried = true
                current = taskVersion(path)
            }
        }
    }

    private suspend fun taskVersion(path: String): Int =
        get(WireTaskAnswer.serializer(), ServiceName.task, path, emptyMap()).task.version ?: throw badResponse

    // MARK: Consent

    override suspend fun grantHealthConsent() {
        // Only health data changes here: the person's other choices are read first and sent back as they are.
        val current = get(WireConsent.serializer(), ServiceName.identity, "/v1/me/consent", emptyMap())
        val body = buildJsonObject {
            put("policyVersion", policyVersion)
            put("healthData", true)
            put("aiAssistant", current.aiAssistant ?: false)
            put("productAnalytics", current.productAnalytics ?: false)
        }
        client.send(ServiceName.identity, "PUT", "/v1/me/consent", body = body.toString())
        client.noteConsentGranted()
    }

    // MARK: Profile

    /** The route refuses a write without the version just read, so a zone set in between is never overwritten. */
    override suspend fun setTimeZoneIfMissing(identifier: String) {
        val body = buildJsonObject { put("timezone", identifier) }.toString()
        var retried = false
        while (true) {
            val me = get(WireMe.serializer(), ServiceName.identity, "/v1/me", emptyMap())
            if (!me.timezone.isNullOrEmpty()) return
            try {
                client.send(ServiceName.identity, "PUT", "/v1/me/profile", body = body,
                    headers = mapOf("If-Match" to "\"v${me.version}\""))
                return
            } catch (error: AuraError.Server) {
                // Something else wrote to the profile since the read: read again, once.
                if (error.status != 412 || retried) throw error
                retried = true
            }
        }
    }

    override suspend fun workSchedule(): WorkSchedule? =
        get(WireMe.serializer(), ServiceName.identity, "/v1/me", emptyMap()).schedule

    /** Sends only the work hours, with the version just read; a write in between makes it read again, once. */
    override suspend fun setWorkSchedule(schedule: WorkSchedule) {
        val body = WireWorkSchedule.update(schedule).toString()
        var retried = false
        while (true) {
            val me = get(WireMe.serializer(), ServiceName.identity, "/v1/me", emptyMap())
            try {
                client.send(ServiceName.identity, "PUT", "/v1/me/profile", body = body,
                    headers = mapOf("If-Match" to "\"v${me.version}\""))
                return
            } catch (error: AuraError.Server) {
                if (error.status != 412 || retried) throw error
                retried = true
            }
        }
    }

    // MARK: Day zones

    /** One entry per day. A range over 31 days is asked in pieces, as the route takes at most 31. */
    override suspend fun dayZones(from: String, to: String): List<ZoneDay> {
        val days = mutableListOf<ZoneDay>()
        for ((rangeFrom, rangeTo) in DayRanges.split(from, to, maxZoneDays)) {
            val answer = get(WireZoneDays.serializer(), ServiceName.planner, "/v1/planner/zones",
                mapOf("from" to rangeFrom, "to" to rangeTo))
            days.addAll(answer.models)
        }
        return days
    }

    // MARK: Find a time

    /**
     * Reads the profile's work hours first and sends them as the day window; without any the Planner uses
     * 09:00-18:00. A failed read stops here instead of planning in the wrong hours. Errors come through as the
     * Planner sends them: 409 `quick_check_required` (ask, then call again with `skipCheck`), 400 `date_in_past`.
     */
    override suspend fun allocate(date: String, dryRun: Boolean, skipCheck: Boolean, taskIds: List<String>?): DayPlan {
        val schedule = workSchedule()
        val request = WireAllocateRequest(date = date, dryRun = dryRun, skipCheck = skipCheck, taskIds = taskIds,
            workdayStart = schedule?.workdayStart, workdayEnd = schedule?.workdayEnd)
        val text = client.send(ServiceName.planner, "POST", "/v1/planner/allocate", body = request.json().toString())
        return dayPlan(text)
    }

    /** Null on a 404, which is how the Planner says no plan has been made for that day yet. */
    override suspend fun plan(date: String): DayPlan? {
        val text = try {
            client.send(ServiceName.planner, "GET", "/v1/planner/plan/${esc(date)}")
        } catch (error: AuraError.Server) {
            if (error.status == 404) return null
            throw error
        }
        return dayPlan(text)
    }

    // MARK: Quick check

    /**
     * The id is the run's own, so a resend answers with the stored check. Errors come through as Health Data sends
     * them: 403 `consent_required` is `AuraError.ConsentRequired`, as on the other Health Data routes.
     */
    override suspend fun submitCheck(run: QuickCheckRun): QuickCheckResult {
        val text = client.send(ServiceName.healthData, "POST", "/v1/checks", body = WireCheckRequest.json(run).toString())
        return decode(WireCheckResponse.serializer(), text).model ?: throw badResponse
    }

    // MARK: Beta day score

    /** Errors come through as Session sends them: 409 `not_focus_session`, 409 `session_not_ended`. */
    override suspend fun setDifficulty(sessionId: String, difficulty: Difficulty): SessionRecord {
        val body = buildJsonObject { put("difficulty", difficulty.raw) }
        val text = client.send(ServiceName.session, "PUT", "/v1/sessions/${esc(sessionId)}/difficulty",
            body = body.toString())
        return decode(WireSessionResponse.serializer(), text).session.model
    }

    /**
     * Today's ledger line with source "work" for the session. A recharge after a new rating adds a line, so the last
     * one is the current one. No session answer carries this text.
     */
    override suspend fun capacityLine(sessionId: String): String? {
        val query = mapOf("date" to DayKey.string(now()))
        val today = get(WireCapacityToday.serializer(), ServiceName.capacity, "/v1/capacity/today", query)
        val line = today.entries.lastOrNull { it.source == "work" && it.sourceId == sessionId }
        val detail = line?.detail
        return if (detail.isNullOrEmpty()) null else detail
    }

    /** At most `maxSessionPages` pages, then newest first by start. */
    override suspend fun sessions(from: String, to: String): List<SessionRecord> {
        val records = mutableListOf<SessionRecord>()
        var cursor: String? = null
        var pages = 0
        do {
            val query = mutableMapOf("from" to from, "to" to to)
            cursor?.let { query["cursor"] = it }
            val page = get(WireSessionPage.serializer(), ServiceName.session, "/v1/sessions", query)
            records.addAll(page.records)
            pages += 1
            cursor = page.cursor?.takeIf { it.isNotEmpty() }
        } while (cursor != null && pages < maxSessionPages)
        return newestFirst(records)
    }

    /** Errors come through as Session sends them: 400 `log_too_old`, 400 `log_in_future`, 409 `log_overlaps`. */
    override suspend fun logBreak(id: String, type: RecoveryType, startedAt: Instant, minutes: Int): LoggedBreak {
        val request = WireLogRequest(id = id, kind = "recovery", recoveryType = type.raw,
            startedAt = WireDate.string(startedAt), minutes = minutes, device = "phone")
        val text = client.send(ServiceName.session, "POST", "/v1/sessions/log", body = request.json().toString())
        val answer = decode(WireLoggedSession.serializer(), text)
        return LoggedBreak(session = answer.session.model, created = answer.created ?: true)
    }

    override suspend fun recoveryOptions(minutes: Int): List<RecoveryOption> {
        val answer = get(WireRecoveryOptions.serializer(), ServiceName.capacity, "/v1/capacity/recovery-options",
            mapOf("minutes" to minutes.toString()))
        return answer.models
    }

    /** Items that already have a review are answered, so they are left out. */
    override suspend fun reviewInbox(date: String, days: Int): List<ReviewItem> {
        val inbox = get(WireInbox.serializer(), ServiceName.session, "/v1/reviews/inbox",
            mapOf("date" to date, "days" to days.toString()))
        return inbox.open
    }

    /**
     * The route is a strict object whose nullable keys must all be there, so empty answers go as JSON null. A
     * recovery type goes only with a recovery block (400 `recovery_type_not_allowed` on another kind).
     */
    override suspend fun submitReview(answer: ReviewAnswer) {
        val item = answer.item
        val type = if (item.isRecovery) answer.recoveryType else null
        val body = buildJsonObject {
            put("date", item.date)
            put("happened", answer.happened)
            put("kind", item.kind)
            put("start", WireDate.string(item.start))
            put("end", WireDate.string(item.end))
            put("intensity", orNull(answer.intensity?.raw))
            put("drain", orNull(answer.drain))
            put("recoveryType", orNull(type?.raw))
            if (item.title.isNotEmpty()) put("title", item.title.take(500))
        }
        client.send(ServiceName.session, "PUT", "/v1/reviews/${esc(item.blockId)}", body = body.toString())
    }

    override suspend fun sleepSchedule(): SleepSchedule? =
        get(WireMe.serializer(), ServiceName.identity, "/v1/me", emptyMap()).sleepSchedule

    /** 403 `consent_required` is `AuraError.ConsentRequired`, as on the other Health Data routes. */
    override suspend fun putSleepNight(night: SleepNight): SleepNightResult {
        val body = WireSleepNightRequest.json(night).toString()
        val text = client.send(ServiceName.healthData, "PUT", "/v1/sleep/nights/${esc(night.date)}", body = body)
        return decode(WireSleepNightResponse.serializer(), text).model
    }

    // MARK: Health Connect (Android only)

    /**
     * PUT /v1/health-connect/days/{date} on the apple-health-data service. Throws `not_available` when that service has
     * no URL in the config or doesn't have the route yet (404), so the app can skip quietly.
     */
    override suspend fun putHealthDay(day: HealthDay): Boolean {
        val text = healthConnectSend("PUT", "/v1/health-connect/days/${esc(day.date)}", WireHealthDay.json(day))
        return decode(WireHealthDayResponse.serializer(), text).written ?: false
    }

    /** POST /v1/health-connect/days/batch, 31 days a call (the route's limit). Nothing is sent for no days. */
    override suspend fun putHealthDays(days: List<HealthDay>): List<Pair<String, Boolean>> {
        val results = mutableListOf<Pair<String, Boolean>>()
        for (chunk in days.chunked(maxHealthBatchDays)) {
            val text = healthConnectSend("POST", "/v1/health-connect/days/batch", WireHealthDay.batch(chunk))
            results.addAll(decode(WireHealthBatchResponse.serializer(), text).pairs)
        }
        return results
    }

    private suspend fun healthConnectSend(method: String, path: String, body: JsonObject): String {
        if (config.baseURL(ServiceName.appleHealthData) == null) {
            throw AuraError.Server("not_available", notAvailable, 0)
        }
        try {
            return client.send(ServiceName.appleHealthData, method, path, body = body.toString())
        } catch (error: AuraError.Server) {
            if (error.status == 404) throw AuraError.Server("not_available", notAvailable, 404)
            throw error
        }
    }

    /** The route refuses one time without the other, so a lone time is left out with its pair. */
    override suspend fun morningCheckin(alertness: Int?, fellAsleepAt: String?, wokeAt: String?, at: Instant) {
        val body = buildJsonObject {
            put("id", newId())
            put("kind", "morning")
            put("takenAt", WireDate.string(at))
            alertness?.let { put("alertness", it) }
            if (fellAsleepAt != null && wokeAt != null) {
                put("fellAsleepAt", fellAsleepAt)
                put("wokeAt", wokeAt)
            }
        }
        post(ServiceName.healthData, "/v1/checkins", body)
    }

    // MARK: Goals

    private val goals by lazy { LiveGoals(client, now) }

    override suspend fun goalsProfile(): GoalsProfile = goals.profile()

    override suspend fun putHealthGoals(changes: Map<String, JsonElement?>, version: Int): GoalsProfile =
        goals.putHealthGoals(changes, version)

    /**
     * The goal columns must load; water and focus minutes are extras, null when their route fails (water logs need
     * Health Data's /v1/logs, focus minutes Session's list).
     */
    override suspend fun goalProgressToday(): GoalProgress {
        val progress = goals.metricsToday()
        val water = try {
            goals.waterToday()
        } catch (error: AuraError.Server) {
            null
        }
        val focus = try {
            sessions(progress.date, progress.date).filter { it.kind == "focus" }.sumOf { it.minutes }
        } catch (error: AuraError.Server) {
            null
        }
        return progress.copy(waterMl = water, focusMinutes = focus)
    }

    override suspend fun habits(includeArchived: Boolean): List<Habit> = goals.habits(includeArchived)

    override suspend fun createHabit(draft: HabitDraft): Habit = goals.createHabit(draft)

    override suspend fun updateHabit(habit: Habit, to: HabitDraft): Habit = goals.updateHabit(habit, to)

    override suspend fun deleteHabit(id: String) = goals.deleteHabit(id)

    override suspend fun logHabit(habitId: String, date: String, count: Int, minutes: Int): HabitLogResult =
        goals.logHabit(habitId, date, count, minutes)

    override suspend fun deleteHabitLog(habitId: String, logId: String) = goals.deleteHabitLog(habitId, logId)

    override suspend fun habitLogs(from: String, to: String): List<HabitLog> = goals.habitLogs(from, to)

    override suspend fun habitStats(id: String): HabitStats = goals.habitStats(id)

    // MARK: Settings (goals)

    override suspend fun meSettings(): Map<String, JsonElement> {
        val text = client.send(ServiceName.identity, "GET", "/v1/me")
        val me = try {
            wireJson.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw badResponse
        return (me["settings"] as? JsonObject)?.toMap() ?: emptyMap()
    }

    override suspend fun putMeSettings(changes: Map<String, JsonElement?>) {
        if (changes.isEmpty()) return
        val body = JsonObject(changes.mapValues { it.value ?: JsonNull })
        client.send(ServiceName.identity, "PUT", "/v1/me/settings", body = body.toString())
    }

    // MARK: Session history

    override suspend fun sessionPage(from: String, to: String, kind: String?, cursor: String?, limit: Int?): SessionPage {
        val query = mutableMapOf("from" to from, "to" to to)
        kind?.let { query["kind"] = it }
        cursor?.takeIf { it.isNotEmpty() }?.let { query["cursor"] = it }
        limit?.let { query["limit"] = it.coerceIn(1, 500).toString() }
        return get(WireSessionDetailPage.serializer(), ServiceName.session, "/v1/sessions", query).model
    }

    override suspend fun session(id: String): SessionDetail {
        val answer = get(WireSessionDetailAnswer.serializer(), ServiceName.session, "/v1/sessions/${esc(id)}", emptyMap())
        return answer.session?.let { WireSessionDetail.model(it) } ?: throw badResponse
    }

    override suspend fun rewards(): Rewards =
        get(WireRewards.serializer(), ServiceName.session, "/v1/rewards", emptyMap()).model

    /** The body is the file itself, not JSON, so it comes back as it is. */
    override suspend fun exportSessionsCsv(from: String, to: String): String =
        client.send(ServiceName.session, "GET", "/v1/sessions/export",
            query = mapOf("from" to from, "to" to to, "format" to "csv"),
            headers = mapOf("Accept" to "text/csv"))

    // MARK: Event by id

    override suspend fun event(id: String): CalendarEvent? {
        val text = try {
            client.send(ServiceName.calendar, "GET", "/v1/calendar/events/${esc(id)}")
        } catch (error: AuraError.Server) {
            if (error.status == 404) return null
            throw error
        }
        return decode(WireEventAnswer.serializer(), text).event.model ?: throw badResponse
    }

    // MARK: Logbook

    private val logbook = LiveLogbook(client)

    override suspend fun logs(from: String, to: String, type: LogType?): List<LogEntry> = logbook.logs(from, to, type)

    override suspend fun addLog(type: LogType, date: String, takenAt: Instant?, data: Map<String, JsonElement>, id: String): LogEntry =
        logbook.addLog(id, type, date, takenAt, data)

    override suspend fun checkins(from: String, to: String, kind: CheckinKind?): List<Checkin> =
        logbook.checkins(from, to, kind)

    override suspend fun addCheckin(kind: CheckinKind, takenAt: Instant, answers: Map<String, JsonElement>, id: String): Checkin =
        logbook.addCheckin(id, kind, takenAt, answers)

    override suspend fun waterGoalMl(): Int? = logbook.waterGoalMl()

    // MARK: Helpers

    private suspend fun <T> get(serializer: KSerializer<T>, service: ServiceName, path: String, query: Map<String, String>): T {
        val text = client.send(service, "GET", path, query = query)
        return decode(serializer, text)
    }

    private suspend fun post(service: ServiceName, path: String, body: JsonObject) {
        client.send(service, "POST", path, body = body.toString())
    }

    private suspend fun <Item, Page : CursorPage<Item>> allPages(
        serializer: KSerializer<Page>,
        service: ServiceName,
        path: String,
        query: Map<String, String>,
    ): List<Item> {
        val items = mutableListOf<Item>()
        var cursor: String? = null
        do {
            val pageQuery = query.toMutableMap()
            cursor?.let { pageQuery["cursor"] = it }
            val page = get(serializer, service, path, pageQuery)
            items.addAll(page.pageItems)
            cursor = page.cursor?.takeIf { it.isNotEmpty() }
        } while (cursor != null)
        return items
    }
}
