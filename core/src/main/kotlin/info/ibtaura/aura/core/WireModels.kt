package info.ibtaura.aura.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

// A port of AuraKit/WireModels.swift. The backend's JSON, as packages/contracts/src describes it. Everything but ids
// is optional here, so a missing field never fails a whole screen, and fields the app doesn't read are ignored.
// Where Swift has `WireLossy<T>`, the field is kept as a raw `JsonElement` and read with `lossy`, which gives null
// instead of failing everything around it.

internal val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** A value that comes through as null instead of failing everything around it when it can't be read (Swift `WireLossy`). */
internal fun <T> JsonElement?.lossy(serializer: KSerializer<T>): T? {
    if (this == null) return null
    return try {
        wireJson.decodeFromJsonElement(serializer, this)
    } catch (_: Exception) {
        null
    }
}

/** Each readable item of a lossy list; the ones that can't be read are left out. */
internal fun <T> List<JsonElement>?.lossyItems(serializer: KSerializer<T>): List<T> =
    this.orEmpty().mapNotNull { it.lossy(serializer) }

internal object WireDate {
    private val plain: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    private val outgoing: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

    /** With or without fractional seconds, at any precision, with Z or an offset. */
    fun parse(text: String?): Instant? {
        if (text == null) return null
        val dot = text.indexOf('.')
        if (dot < 0) return parsePlain(text)
        val afterDot = text.substring(dot + 1)
        val digits = afterDot.takeWhile { it in '0'..'9' }
        val whole = text.substring(0, dot) + afterDot.substring(digits.length)
        val seconds = parsePlain(whole) ?: return null
        if (digits.isEmpty()) return seconds
        val nanos = digits.take(9).padEnd(9, '0').toLong()
        return seconds.plusNanos(nanos)
    }

    private fun parsePlain(text: String): Instant? =
        try {
            OffsetDateTime.parse(text, plain).toInstant()
        } catch (_: Exception) {
            null
        }

    /** An instant with the phone's own offset (whole seconds), which is what Health Data files a check-in's local day by. */
    fun string(date: Instant): String =
        OffsetDateTime.ofInstant(date.truncatedTo(ChronoUnit.SECONDS), ZoneId.systemDefault()).format(outgoing)
}

/** A metric's value: a number, a word or a yes/no. Lists and documents come through as other (Swift `WireValue`). */
internal object WireValue {
    fun number(value: JsonElement?): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString || primitive.booleanOrNull != null) return null
        return primitive.doubleOrNull
    }

    fun text(value: JsonElement?): String? {
        val primitive = value as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    fun flag(value: JsonElement?): Boolean? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        return primitive.booleanOrNull
    }
}

/** A whole number sent as any JSON number, rounded. Null when it's missing or too big to be one. */
internal object WireWhole {
    fun from(value: Double?): Int? {
        if (value == null || !value.isFinite() || abs(value) >= 1_000_000_000) return null
        return roundHalfAway(value).toInt()
    }

    /** Swift's `rounded()`: halves go away from zero. */
    fun roundHalfAway(value: Double): Double = if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
}

/** A list route that hands back `cursor` until it's null. */
internal interface CursorPage<Item> {
    val pageItems: List<Item>
    val cursor: String?
}

// MARK: Scores: GET /v1/scores/today?date

@Serializable
internal data class WireScore(
    val value: Double? = null,
    val checksCounted: Int? = null,
    val checksNeeded: Int? = null,
) {
    companion object {
        fun model(wire: WireScore?, name: String): Score =
            Score(name = name, value = wire?.value, checksCounted = wire?.checksCounted ?: 0,
                checksNeeded = wire?.checksNeeded ?: 0)
    }
}

@Serializable
internal data class WireScores(
    val attention: WireScore? = null,
    val memory: WireScore? = null,
    val thinking: WireScore? = null,
    val focus: WireScore? = null,
) {
    val model: ScoresToday
        get() = ScoresToday(
            attention = WireScore.model(attention, "Attention"),
            memory = WireScore.model(memory, "Memory"),
            thinking = WireScore.model(thinking, "Thinking"),
            focus = WireScore.model(focus, "Focus"),
        )
}

// MARK: Calendar: GET /v1/calendar/events

@Serializable
internal data class WireEvent(
    val id: String,
    val title: String? = null,
    val start: String? = null,
    val end: String? = null,
    val kind: String? = null,
    val notes: String? = null,
    val location: String? = null,
    val source: String? = null,
    val seriesId: String? = null,
    val version: Int? = null,
) {
    val model: CalendarEvent?
        get() {
            val startDate = WireDate.parse(start) ?: return null
            val endDate = WireDate.parse(end) ?: return null
            return CalendarEvent(id = id, title = title ?: "", start = startDate, end = endDate, kind = kind ?: "focus",
                notes = notes, location = location, source = source ?: "app", seriesId = seriesId, version = version)
        }
}

/** GET /v1/calendar/events/{id}: `{"event": ...}`. */
@Serializable
internal data class WireEventAnswer(val event: WireEvent)

@Serializable
internal data class WireEventPage(
    val events: List<WireEvent>? = null,
    override val cursor: String? = null,
) : CursorPage<WireEvent> {
    override val pageItems: List<WireEvent> get() = events.orEmpty()
}

// MARK: Tasks: GET /v1/tasks

@Serializable
internal data class WireTask(
    val id: String,
    val title: String? = null,
    val status: String? = null,
    val dueAt: String? = null,
    val hasTime: Boolean? = null,
    val scheduledAt: String? = null,
    val completedAt: String? = null,
    /** Find a time places it. A Task service from before the flag sends none, which reads as no. */
    val autoAllocate: Boolean? = null,
    val version: Int? = null,
    val priority: String? = null,
    val notes: String? = null,
    val horizon: String? = null,
    val flagged: Boolean? = null,
    val estimateMinutes: Int? = null,
    val plannedFor: String? = null,
    val costPoints: Double? = null,
) {
    val model: TaskItem
        get() {
            val dueInstant = WireDate.parse(dueAt)
            val timed = hasTime == true
            // A due date without a time only names the day, so it isn't the task's time.
            val due = if (timed) dueInstant else null
            val completed = WireDate.parse(completedAt)
            val done = status == "completed" || completedAt != null
            val scheduled = WireDate.parse(scheduledAt)
            return TaskItem(
                id = id, title = title ?: "", at = scheduled ?: due, done = done,
                autoAllocate = autoAllocate == true, version = version,
                status = if (done) TaskStatus.completed else TaskStatus.from(status),
                priority = TaskPriority.from(priority), dueAt = dueInstant, hasTime = timed && dueInstant != null,
                notes = notes, horizon = TaskHorizon.from(horizon), flagged = flagged == true,
                estimateMinutes = estimateMinutes, plannedFor = plannedFor, completedAt = completed,
                scheduledAt = scheduled, costPoints = costPoints,
            )
        }
}

/** POST /v1/tasks, PATCH /v1/tasks/{id}, GET /v1/tasks/{id}, POST .../complete: `{task, created?, next?}`. */
@Serializable
internal data class WireTaskAnswer(
    val task: WireTask,
    val next: WireTask? = null,
)

@Serializable
internal data class WireTaskPage(
    val tasks: List<WireTask>? = null,
    override val cursor: String? = null,
) : CursorPage<WireTask> {
    override val pageItems: List<WireTask> get() = tasks.orEmpty()
}

// MARK: Alerts: GET /v1/alerts

@Serializable
internal data class WireAlert(
    val id: String,
    val featureId: String? = null,
    val date: String? = null,
    val title: String? = null,
    val body: String? = null,
    val action: Action? = null,
    val done: Boolean? = null,
) {
    @Serializable
    internal data class Action(val ref: String? = null)

    val model: AlertItem
        get() = AlertItem(id = id, featureId = featureId ?: "", title = title ?: "", detail = body, date = date ?: "",
            done = done ?: false, actionRef = action?.ref)
}

@Serializable
internal data class WireAlertList(val alerts: List<WireAlert>? = null)

// MARK: Metrics: GET /v1/metrics/daily, catalogue, availability, series

@Serializable
internal data class WireDay(
    val date: String,
    val metrics: Metrics? = null,
) {
    @Serializable
    internal data class Metrics(
        val burnoutIndexScore: JsonElement? = null,
        val burnoutIndexRisk: JsonElement? = null,
    )

    val model: DailyMetrics
        get() = DailyMetrics(date = date, burnoutIndexScore = WireValue.number(metrics?.burnoutIndexScore),
            burnoutIndexRisk = WireValue.text(metrics?.burnoutIndexRisk))
}

@Serializable
internal data class WireDayPage(
    val days: List<WireDay>? = null,
    override val cursor: String? = null,
) : CursorPage<WireDay> {
    override val pageItems: List<WireDay> get() = days.orEmpty()
}

@Serializable
internal data class WireParameter(
    val name: String? = null,
    val field: String? = null,
    val read: String? = null,
    val unit: String? = null,
    val format: String? = null,
) {
    val model: CatalogueParameter
        get() = CatalogueParameter(name = name ?: "", field = this.field, read = read ?: "none", unit = unit ?: "",
            format = format ?: "")
}

@Serializable
internal data class WireSection(
    val id: String,
    val title: String? = null,
    val parameters: List<WireParameter>? = null,
) {
    val model: CatalogueSection
        get() = CatalogueSection(id = id, title = title ?: "", parameters = parameters.orEmpty().map { it.model })
}

@Serializable
internal data class WireCatalogue(val sections: List<WireSection>? = null)

@Serializable
internal data class WireAvailableField(
    val field: String,
    val latestDate: String? = null,
    val latestValue: JsonElement? = null,
) {
    val model: FieldAvailability
        get() {
            val flag = WireValue.flag(latestValue)
            val text = if (flag != null) (if (flag) "Yes" else "No") else WireValue.text(latestValue)
            return FieldAvailability(field = this.field, latestDate = latestDate ?: "",
                latestNumber = WireValue.number(latestValue), latestText = text)
        }
}

@Serializable
internal data class WireAvailability(val fields: List<WireAvailableField>? = null)

@Serializable
internal data class WireSeriesPoint(val date: String, val value: JsonElement? = null)

@Serializable
internal data class WireSeries(val unit: String? = null, val points: List<WireSeriesPoint>? = null)

// MARK: Sessions

@Serializable
internal data class WireStartedSession(
    val session: Session,
    val joined: Boolean? = null,
) {
    @Serializable
    internal data class Session(
        val id: String,
        val plannedMinutes: Int? = null,
        val title: String? = null,
        val kind: String? = null,
        val device: String? = null,
        /** An ISO instant, parsed with WireDate.parse; null when open-ended. */
        val endsAt: String? = null,
    )

    val model: StartedSession
        get() = StartedSession(id = session.id, joined = joined ?: false, plannedMinutes = session.plannedMinutes,
            title = session.title, kind = session.kind, device = session.device,
            endsAt = WireDate.parse(session.endsAt))
}

@Serializable
internal data class WireTrace(
    val status: String? = null,
    val stressDrop: Double? = null,
    // MARK: Session history (read by `WireTrace.model` in SessionHistoryWire.kt). Lossy items.
    val hr: List<JsonElement>? = null,
    val stress: List<JsonElement>? = null,
    val avgHr: Double? = null,
)

// MARK: Beta day score: Session's sessionView, reviews; Capacity's ledger and recovery options; sleep nights

/** The sessionView keys the app reads. */
@Serializable
internal data class WireSessionView(
    val id: String,
    val kind: String? = null,
    val title: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val minutes: Double? = null,
    val activeMinutes: Double? = null,
    val outcome: String? = null,
    val difficulty: String? = null,
    val recoveryType: String? = null,
    /** Lossy `Capacity`. */
    val capacity: JsonElement? = null,
) {
    @Serializable
    internal data class Capacity(val cost: Double? = null, val charged: Boolean? = null)

    /** A difficulty or recovery type this app doesn't know reads as none. The cost is only kept once charged. */
    val model: SessionRecord
        get() {
            val charge = capacity.lossy(Capacity.serializer())
            val cost = if (charge?.charged == true) charge.cost else null
            val length = WireWhole.from(activeMinutes) ?: WireWhole.from(minutes) ?: 0
            return SessionRecord(id = id, kind = kind ?: "", title = title, startedAt = WireDate.parse(startedAt),
                endedAt = WireDate.parse(endedAt), minutes = length, outcome = outcome,
                difficulty = difficulty?.let { Difficulty.fromRaw(it) },
                recoveryType = recoveryType?.let { RecoveryType.fromRaw(it) }, capacityCost = cost)
        }
}

/** `{"session": sessionView}`, the answer of PUT /v1/sessions/{id}/difficulty. */
@Serializable
internal data class WireSessionResponse(val session: WireSessionView)

/** `{"session": sessionView, "created": bool}`, the answer of POST /v1/sessions/log. */
@Serializable
internal data class WireLoggedSession(val session: WireSessionView, val created: Boolean? = null)

/** GET /v1/sessions?from&to. A session the app can't read is left out. */
@Serializable
internal data class WireSessionPage(
    /** Lossy `WireSessionView` items. */
    val sessions: List<JsonElement>? = null,
    val cursor: String? = null,
) {
    val records: List<SessionRecord> get() = sessions.lossyItems(WireSessionView.serializer()).map { it.model }
}

/** The log body. `minutes` and the times are the caller's; the route checks them. */
internal data class WireLogRequest(
    val id: String,
    val kind: String,
    val recoveryType: String,
    val startedAt: String,
    val minutes: Int,
    val device: String,
) {
    fun json(): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", kind)
        put("recoveryType", recoveryType)
        put("startedAt", startedAt)
        put("minutes", minutes)
        put("device", device)
    }
}

@Serializable
internal data class WireInboxItem(
    val blockId: String,
    val date: String? = null,
    val title: String? = null,
    val kind: String? = null,
    val start: String? = null,
    val end: String? = null,
    val minutes: Double? = null,
    /** Only its presence is read: an item with one is already answered. */
    val review: JsonElement? = null,
) {
    /** True when a review is there (anything but missing or null), as Swift reads its lossy marker. */
    val answered: Boolean get() = review != null && review !is JsonNull

    /** Null when a time can't be read: that item is left out. */
    val model: ReviewItem?
        get() {
            val startDate = WireDate.parse(start) ?: return null
            val endDate = WireDate.parse(end) ?: return null
            val seconds = (endDate.toEpochMilli() - startDate.toEpochMilli()) / 1000.0
            val length = WireWhole.from(minutes) ?: WireWhole.roundHalfAway(seconds / 60).toInt()
            return ReviewItem(blockId = blockId, date = date ?: DayKey.string(startDate), title = title ?: "",
                kind = kind ?: "focus", start = startDate, end = endDate, minutes = length)
        }
}

/** GET /v1/reviews/inbox?date&days. */
@Serializable
internal data class WireInbox(val items: List<JsonElement>? = null) {
    val open: List<ReviewItem>
        get() = items.lossyItems(WireInboxItem.serializer()).filter { !it.answered }.mapNotNull { it.model }
}

/** One line of Capacity's ledger. Only the fields that find a session's line are read. */
@Serializable
internal data class WireLedgerEntry(
    val source: String? = null,
    val sourceId: String? = null,
    val detail: String? = null,
)

/** GET /v1/capacity/today?date: only the ledger is read. */
@Serializable
internal data class WireCapacityToday(val ledger: List<JsonElement>? = null) {
    val entries: List<WireLedgerEntry> get() = ledger.lossyItems(WireLedgerEntry.serializer())
}

@Serializable
internal data class WireRecoveryOption(
    val id: String,
    val title: String? = null,
    val minutes: Double? = null,
    val maxPoints: Double? = null,
    val fadesAfterMinutes: Double? = null,
    val note: String? = null,
) {
    val model: RecoveryOption
        get() = RecoveryOption(id = id, title = title ?: "", minutes = WireWhole.from(minutes) ?: 0,
            maxPoints = maxPoints ?: 0.0, fadesAfterMinutes = WireWhole.from(fadesAfterMinutes) ?: 0,
            note = note ?: "")
}

/** GET /v1/capacity/recovery-options?minutes. */
@Serializable
internal data class WireRecoveryOptions(val options: List<JsonElement>? = null) {
    val models: List<RecoveryOption> get() = options.lossyItems(WireRecoveryOption.serializer()).map { it.model }
}

/**
 * The night body. The route is a strict object whose optional keys take a number or nothing, never null: they are
 * left out when null.
 */
internal object WireSleepNightRequest {
    fun json(night: SleepNight): JsonObject = buildJsonObject {
        put("timeZone", night.timeZone)
        put("bedtimeAt", WireDate.string(night.bedtimeAt))
        put("wakeAt", WireDate.string(night.wakeAt))
        put("totalSleepMin", dayMinutes(night.totalSleepMin))
        night.awakeMin?.let { put("awakeMin", dayMinutes(it)) }
        night.inBedMin?.let { put("inBedMin", dayMinutes(it)) }
    }

    /** Minutes inside the route's 0-1440. */
    fun dayMinutes(value: Int): Int = value.coerceIn(0, 1440)
}

/** `{"night": sleepNightView, "created": bool}`. */
@Serializable
internal data class WireSleepNightResponse(
    /** Lossy `Night`. */
    val night: JsonElement? = null,
    val created: Boolean? = null,
) {
    @Serializable
    internal data class Night(val source: String? = null)

    val model: SleepNightResult
        get() = SleepNightResult(created = created ?: false,
            source = night.lossy(Night.serializer())?.source ?: "appleHealth")
}

// MARK: Day zones: GET /v1/planner/zones?from&to

@Serializable
internal data class WireZone(
    val kind: String? = null,
    val start: String? = null,
    val end: String? = null,
) {
    /** Null for a kind this app doesn't know or a time it can't read: that zone is left out. */
    val model: Zone?
        get() {
            val zoneKind = kind?.let { ZoneKind.fromRaw(it) } ?: return null
            val startDate = WireDate.parse(start) ?: return null
            val endDate = WireDate.parse(end) ?: return null
            return Zone(kind = zoneKind, start = startDate, end = endDate)
        }
}

@Serializable
internal data class WireZoneDay(
    val date: String,
    val status: String? = null,
    val score: Double? = null,
    val level: String? = null,
    val heavyMinutes: Double? = null,
    val reason: String? = null,
    /** Lossy `WireZone` items. */
    val zones: List<JsonElement>? = null,
) {
    val model: ZoneDay
        get() {
            val known = status?.let { ZoneStatus.fromRaw(it) } ?: ZoneStatus.unknown
            val placed = zones.lossyItems(WireZone.serializer()).mapNotNull { it.model }
            return ZoneDay(date = date, status = known, score = WireWhole.from(score), level = level,
                heavyMinutes = WireWhole.from(heavyMinutes) ?: 0, reason = reason, zones = placed)
        }
}

@Serializable
internal data class WireZoneDays(val days: List<JsonElement>? = null) {
    val models: List<ZoneDay> get() = days.lossyItems(WireZoneDay.serializer()).map { it.model }
}

// MARK: Find a time: POST /v1/planner/allocate, GET /v1/planner/plan/{date}

/** The allocate body. The route is a strict object that takes a value or nothing, never null: null fields are left out. */
internal data class WireAllocateRequest(
    val date: String,
    val dryRun: Boolean,
    val skipCheck: Boolean,
    val taskIds: List<String>?,
    val workdayStart: String?,
    val workdayEnd: String?,
) {
    fun json(): JsonObject = buildJsonObject {
        put("date", date)
        put("dryRun", dryRun)
        put("skipCheck", skipCheck)
        taskIds?.let { ids -> put("taskIds", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }) }
        workdayStart?.let { put("workdayStart", it) }
        workdayEnd?.let { put("workdayEnd", it) }
    }
}

/** `{"plan": planView}`, the answer of both routes. */
@Serializable
internal data class WirePlanResponse(val plan: WirePlan)

@Serializable
internal data class WirePlan(
    val date: String,
    val generatedAt: String? = null,
    /** Lossy `Basis`. */
    val basis: JsonElement? = null,
    /** Lossy `Placement` items. */
    val placed: List<JsonElement>? = null,
    /** Lossy `Unplaced` items. */
    val unplaced: List<JsonElement>? = null,
    /** Lossy `Warnings`. Missing from a Planner deployed before the recovery rhythm: that reads as no warnings. */
    val warnings: JsonElement? = null,
    val version: Double? = null,
) {
    @Serializable
    internal data class Hours(val startHour: Double? = null, val endHour: Double? = null) {
        val model: SharpestHours?
            get() {
                val start = WireWhole.from(startHour) ?: return null
                val end = WireWhole.from(endHour) ?: return null
                return SharpestHours(startHour = start, endHour = end)
            }
    }

    @Serializable
    internal data class Basis(
        val quickCheckAt: String? = null,
        val attention: Double? = null,
        val sleepMinutes: Double? = null,
        /** Lossy `Hours`. */
        val sharpestHours: JsonElement? = null,
        val capacityOpening: Double? = null,
        val workLimitMinutes: Double? = null,
    ) {
        val model: PlanBasis
            get() = PlanBasis(quickCheckAt = WireDate.parse(quickCheckAt), attention = attention,
                sleepMinutes = sleepMinutes, sharpestHours = sharpestHours.lossy(Hours.serializer())?.model,
                capacityOpening = capacityOpening, workLimitMinutes = WireWhole.from(workLimitMinutes))
    }

    @Serializable
    internal data class Part(val index: Double? = null, val of: Double? = null) {
        val model: PlanPart?
            get() {
                val number = WireWhole.from(index) ?: return null
                val total = WireWhole.from(of) ?: return null
                return PlanPart(index = number, of = total)
            }
    }

    @Serializable
    internal data class Placement(
        val taskId: String? = null,
        val blockId: String? = null,
        val start: String? = null,
        val end: String? = null,
        val demand: Double? = null,
        val workType: String? = null,
        val reason: String? = null,
        /** Missing from a Planner deployed before the recovery rhythm: that reads as focus. */
        val kind: String? = null,
        /** Lossy `Part`. */
        val part: JsonElement? = null,
    ) {
        /** Null when a time can't be read: that block is left out. */
        val model: PlanBlock?
            get() {
                val startDate = WireDate.parse(start) ?: return null
                val endDate = WireDate.parse(end) ?: return null
                return PlanBlock(taskId = taskId, blockId = blockId, start = startDate, end = endDate,
                    demand = demand ?: 0.0, workType = workType, reason = reason ?: "", kind = kind ?: "focus",
                    part = part.lossy(Part.serializer())?.model)
            }
    }

    /** The reason is kept as sent, so a code this app doesn't know yet still reads. */
    @Serializable
    internal data class Unplaced(val taskId: String, val reason: String? = null, val detail: String? = null) {
        val model: UnplacedTask get() = UnplacedTask(taskId = taskId, reason = reason ?: "", detail = detail ?: "")
    }

    @Serializable
    internal data class Warnings(val committedOnRecovery: Double? = null, val recoveryMoved: Double? = null) {
        val model: PlanWarnings
            get() = PlanWarnings(committedOnRecovery = WireWhole.from(committedOnRecovery) ?: 0,
                recoveryMoved = WireWhole.from(recoveryMoved) ?: 0)
    }

    /**
     * Null when `generatedAt` can't be read, which the caller turns into a bad answer. Every Planner build sends it,
     * so an unreadable one means the shape changed, and any time put in its place would show a wrong "planned at".
     */
    val model: DayPlan?
        get() {
            val generated = WireDate.parse(generatedAt) ?: return null
            val blocks = placed.lossyItems(Placement.serializer()).mapNotNull { it.model }.sortedBy { it.start }
            return DayPlan(date = date, generatedAt = generated,
                basis = basis.lossy(Basis.serializer())?.model ?: PlanBasis(), placed = blocks,
                unplaced = unplaced.lossyItems(Unplaced.serializer()).map { it.model },
                warnings = warnings.lossy(Warnings.serializer())?.model ?: PlanWarnings(),
                version = WireWhole.from(version) ?: 0)
        }
}

// MARK: Quick check: POST /v1/checks

/**
 * The check body. The route is a strict object whose nullable keys must all be there, so this writes JSON null for
 * them. `minutesSinceWake` is always null: the server works it out from the wake time it knows. `switching` is always
 * null: the rule-switch part isn't built.
 */
internal object WireCheckRequest {
    fun json(run: QuickCheckRun): JsonObject = buildJsonObject {
        put("id", run.id)
        put("takenAt", WireDate.string(run.takenAt))
        put("device", "phone")
        put("kind", run.kind)
        put("minutesSinceWake", JsonNull)
        put("pvt", buildJsonObject {
            put("durationSeconds", run.durationSeconds)
            put("reactionTimesMs", buildJsonArray { run.reactionTimesMs.forEach { add(JsonPrimitive(wholeMs(it))) } })
            put("falseStarts", run.falseStarts)
        })
        val span = run.memory
        if (span != null) {
            put("memory", buildJsonObject {
                put("spanCorrect", span.spanCorrect)
                put("spanAttempts", span.spanAttempts)
            })
        } else {
            put("memory", JsonNull)
        }
        put("switching", JsonNull)
    }

    /** Whole milliseconds inside the route's 0-30000. */
    fun wholeMs(value: Double): Int {
        if (!value.isFinite()) return 0
        return WireWhole.roundHalfAway(value.coerceIn(0.0, 30_000.0)).toInt()
    }
}

/** `{"check": checkView, "scores": scoresToday, "created": bool}`. */
@Serializable
internal data class WireCheckResponse(
    val check: Check,
    /** Lossy `WireScores`. */
    val scores: JsonElement? = null,
    val created: Boolean? = null,
) {
    @Serializable
    internal data class Attention(
        val meanRtMs: Double? = null,
        val medianRtMs: Double? = null,
        val lapses: Double? = null,
        val falseStarts: Double? = null,
        val performance: Double? = null,
    )

    @Serializable
    internal data class Check(
        val id: String,
        val valid: Boolean? = null,
        val invalidReason: String? = null,
        /** Lossy `Attention`. */
        val attention: JsonElement? = null,
        val qualifiesForNormal: Boolean? = null,
    )

    /**
     * Null without a readable `valid`, which the caller turns into a bad answer: guessing either way would tell the
     * person something the server didn't say.
     */
    val model: QuickCheckResult?
        get() {
            val valid = check.valid ?: return null
            val attention = check.attention.lossy(Attention.serializer())
            val today = scores.lossy(WireScores.serializer()) ?: WireScores()
            return QuickCheckResult(id = check.id, valid = valid, invalidReason = check.invalidReason,
                meanRtMs = attention?.meanRtMs ?: 0.0, medianRtMs = attention?.medianRtMs ?: 0.0,
                lapses = WireWhole.from(attention?.lapses) ?: 0,
                falseStarts = WireWhole.from(attention?.falseStarts) ?: 0,
                performance = attention?.performance ?: 0.0,
                qualifiesForNormal = check.qualifiesForNormal ?: false, scores = today.model)
        }
}

// MARK: Profile: GET /v1/me, PUT /v1/me/profile

@Serializable
internal data class WireMe(
    val timezone: String? = null,
    /** Lossy `WireWorkSchedule`. Null until the person sets it; one the app can't read counts as none. */
    val workSchedule: JsonElement? = null,
    /** Lossy `WireHealthGoals`, read for the usual sleep times only. */
    val healthGoals: JsonElement? = null,
    /** Not optional: a profile write has to name the version it read. */
    val version: Int,
) {
    val schedule: WorkSchedule? get() = workSchedule.lossy(WireWorkSchedule.serializer())?.model
    val sleepSchedule: SleepSchedule? get() = healthGoals.lossy(WireHealthGoals.serializer())?.schedule
}

/** The two health goals the app reads: "HH:mm" or null each. */
@Serializable
internal data class WireHealthGoals(val bedtime: String? = null, val wakeTime: String? = null) {
    /** Null when either time is missing or empty. */
    val schedule: SleepSchedule?
        get() {
            val bed = bedtime?.takeIf { it.isNotEmpty() } ?: return null
            val wake = wakeTime?.takeIf { it.isNotEmpty() } ?: return null
            return SleepSchedule(bedtime = bed, wakeTime = wake)
        }
}

@Serializable
internal data class WireWorkSchedule(
    val workdayStart: String? = null,
    val workdayEnd: String? = null,
    val lunchStart: String? = null,
    val lunchMinutes: Int? = null,
    val workDays: List<Int>? = null,
) {
    val model: WorkSchedule?
        get() {
            return WorkSchedule(
                workdayStart = workdayStart ?: return null,
                workdayEnd = workdayEnd ?: return null,
                lunchStart = lunchStart ?: return null,
                lunchMinutes = lunchMinutes ?: return null,
                workDays = workDays ?: return null,
            )
        }

    companion object {
        /** The profile write for the work hours alone: fields not sent are kept. */
        fun update(schedule: WorkSchedule): JsonObject = buildJsonObject {
            put("workSchedule", buildJsonObject {
                put("workdayStart", schedule.workdayStart)
                put("workdayEnd", schedule.workdayEnd)
                put("lunchStart", schedule.lunchStart)
                put("lunchMinutes", schedule.lunchMinutes)
                put("workDays", buildJsonArray { schedule.workDays.forEach { add(JsonPrimitive(it)) } })
            })
        }
    }
}

// MARK: Consent: GET /v1/me/consent

@Serializable
internal data class WireConsent(val aiAssistant: Boolean? = null, val productAnalytics: Boolean? = null)

// MARK: Health Connect (Android only): PUT /v1/health-connect/days/{date}, POST /v1/health-connect/days/batch

/**
 * The day body, as the apple-health-data contract's `hcPutDay` (a strict object). Metrics and workout fields that are
 * null are left out (`metrics` is partial and the workout's nullable fields default to null there).
 */
internal object WireHealthDay {
    fun json(day: HealthDay, withDate: Boolean = false): JsonObject = buildJsonObject {
        if (withDate) put("date", day.date)
        put("timeZone", day.timeZone)
        put("recordedAt", WireDate.string(day.recordedAt))
        put("sources", buildJsonArray { day.sources.forEach { add(JsonPrimitive(it)) } })
        put("metrics", metrics(day.metrics))
        put("workouts", buildJsonArray { day.workouts.forEach { add(workout(it)) } })
    }

    fun metrics(metrics: HealthMetrics): JsonObject = buildJsonObject {
        metrics.stepCount?.let { put("stepCount", it) }
        metrics.distanceKm?.let { put("distanceKm", it) }
        metrics.activeKcal?.let { put("activeKcal", it) }
        metrics.exerciseMinutes?.let { put("exerciseMinutes", it) }
        metrics.restingHrBpm?.let { put("restingHrBpm", it) }
        metrics.hrvRmssdMs?.let { put("hrvRmssdMs", it) }
        metrics.breathsPerMin?.let { put("breathsPerMin", it) }
        metrics.spo2Pct?.let { put("spo2Pct", it) }
        metrics.skinTempDeltaC?.let { put("skinTempDeltaC", it) }
        metrics.mindfulMinutes?.let { put("mindfulMinutes", it) }
        metrics.weightKg?.let { put("weightKg", it) }
        metrics.sleepDeepMin?.let { put("sleepDeepMin", it) }
        metrics.sleepLightMin?.let { put("sleepLightMin", it) }
        metrics.sleepRemMin?.let { put("sleepRemMin", it) }
        metrics.sleepAwakeMin?.let { put("sleepAwakeMin", it) }
    }

    fun workout(workout: HealthWorkout): JsonObject = buildJsonObject {
        put("id", workout.id)
        put("type", workout.type)
        put("startedAt", WireDate.string(workout.startedAt))
        put("endedAt", WireDate.string(workout.endedAt))
        put("minutes", workout.minutes)
        workout.activeKcal?.let { put("activeKcal", it) }
        workout.distanceKm?.let { put("distanceKm", it) }
        workout.avgHrBpm?.let { put("avgHrBpm", it) }
        workout.source?.let { put("source", it) }
    }

    fun batch(days: List<HealthDay>): JsonObject = buildJsonObject {
        put("days", JsonArray(days.map { json(it, withDate = true) }))
    }
}

/** `{"day": hcDayView, "written": bool}`. Only `written` is read. */
@Serializable
internal data class WireHealthDayResponse(val written: Boolean? = null)

/** `{"results": [{date, written}]}`. A result the app can't read is left out. */
@Serializable
internal data class WireHealthBatchResponse(val results: List<JsonElement>? = null) {
    @Serializable
    internal data class Result(val date: String, val written: Boolean? = null)

    val pairs: List<Pair<String, Boolean>>
        get() = results.lossyItems(Result.serializer()).map { it.date to (it.written ?: false) }
}
