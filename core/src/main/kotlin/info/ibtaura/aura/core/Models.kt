package info.ibtaura.aura.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.math.BigDecimal
import java.text.BreakIterator
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

// What the screens work with. Field names follow the backend contracts in aws_docs (packages/contracts/src).
// A port of AuraKit/Models.swift: same names, same logic. Swift `Date` is `Instant`, `Calendar` is a `ZoneId`.

// MARK: Config

/** Public settings in aura-dev.json. Client ids and API URLs aren't secrets. */
@Serializable
data class AppConfig(
    val stage: String,
    val region: String,
    val userPoolId: String,
    val clientId: String,
    val platform: String,
    val appVersion: String,
    val consentPolicyVersion: String,
    val services: Map<String, String>,
) {
    fun baseURL(service: ServiceName): HttpUrl? = services[service.raw]?.toHttpUrlOrNull()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads aura-dev.json. Throws when a field is missing. */
        fun decode(text: String): AppConfig = json.decodeFromString(serializer(), text)
    }
}

enum class ServiceName(val raw: String) {
    identity("identity"), healthData("health-data"), notification("notification"), calendar("calendar"),
    capacity("capacity"), content("content"), habit("habit"), planner("planner"), session("session"), task("task");

    companion object {
        fun fromRaw(raw: String): ServiceName? = entries.firstOrNull { it.raw == raw }
    }
}

// MARK: Home

data class Score(
    val name: String,
    /** 100 is the person's normal; null while the app is still learning it. */
    val value: Double?,
    val checksCounted: Int = 0,
    val checksNeeded: Int = 0,
)

data class ScoresToday(
    val attention: Score,
    val memory: Score,
    val thinking: Score,
    val focus: Score,
)

data class CalendarEvent(
    val id: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    val kind: String = "focus",
    val notes: String? = null,
    val location: String? = null,
    /** "app", "apple" (read from Apple Calendar on the Mac) or "planner" (placed by Find a time). */
    val source: String = "app",
    /** The repeating event this occurrence belongs to, or null for a one-off. */
    val seriesId: String? = null,
    /** The version to send as If-Match when changing it. Null when the answer had none. */
    val version: Int? = null,
) {
    /** Apple Calendar owns it: only its kind may change here, and it can't be deleted here (409 apple_event_read_only). */
    val isFromApple: Boolean get() = source == "apple"

    /** One day of a repeating event: a change or delete applies to that day only. */
    val isOccurrence: Boolean get() = seriesId != null
}

/** The event editor's fields. The same limits as the Calendar contract (calendar.ts). */
data class EventDraft(
    val title: String,
    val start: Instant,
    val end: Instant,
    /** "focus", "meeting" or "recovery". */
    val kind: String = "focus",
    val location: String = "",
    val notes: String = "",
) {
    /** The fields of an event as it is now. */
    constructor(event: CalendarEvent) : this(
        title = event.title, start = event.start, end = event.end,
        kind = if (kinds.contains(event.kind)) event.kind else "focus",
        location = event.location ?: "", notes = event.notes ?: "",
    )

    private val trimmedTitle: String get() = title.trim()

    /** What stops it being saved, in words, or null when it can be. */
    val problem: String?
        get() {
            if (trimmedTitle.isEmpty()) return "Give it a title."
            if (trimmedTitle.characterCount() > maxTitle) return "The title is too long."
            if (end <= start) return "It has to end after it starts."
            if (location.characterCount() > maxLocation) return "The location is too long."
            if (notes.characterCount() > maxNotes) return "The notes are too long."
            return null
        }

    /** The body of POST /v1/calendar/events, without its id. Empty location and notes are left out. */
    internal fun createBody(): JsonObject = buildJsonObject {
        put("title", trimmedTitle)
        put("start", wireDateString(start))
        put("end", wireDateString(end))
        put("kind", kind)
        text(location)?.let { put("location", it) }
        text(notes)?.let { put("notes", it) }
    }

    /**
     * The body of PATCH /v1/calendar/events/{id}: only what differs from `event`, so an unchanged kind stays the
     * automatic one and an Apple event sends only its kind. Cleared location and notes are sent as null. Empty when
     * nothing changed.
     */
    internal fun changes(from: CalendarEvent): JsonObject {
        val event = from
        val before = EventDraft(event)
        return buildJsonObject {
            if (event.isFromApple) {
                if (kind != event.kind) put("kind", kind)
                return@buildJsonObject
            }
            if (trimmedTitle != event.title) put("title", trimmedTitle)
            if (start != event.start) put("start", wireDateString(start))
            if (end != event.end) put("end", wireDateString(end))
            if (kind != event.kind) put("kind", kind)
            if (text(location) != text(before.location)) {
                put("location", text(location)?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            if (text(notes) != text(before.notes)) {
                put("notes", text(notes)?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
    }

    companion object {
        val kinds: List<String> = listOf("focus", "meeting", "recovery")
        internal const val maxTitle = 500
        internal const val maxLocation = 500
        internal const val maxNotes = 20_000

        /** A new event starting at the next half hour after `after`, an hour long. */
        fun new(after: Instant, zone: ZoneId = ZoneId.systemDefault()): EventDraft {
            val wholeMinute = ZonedDateTime.ofInstant(after, zone).truncatedTo(ChronoUnit.MINUTES)
            val minute = wholeMinute.minute
            val toNext = if (minute < 30) 30 - minute else 60 - minute
            val start = wholeMinute.toInstant().plusSeconds(toNext * 60L)
            return EventDraft(title = "", start = start, end = start.plusSeconds(3600))
        }

        /** Trimmed text, or null when there is none. */
        private fun text(value: String): String? {
            val trimmed = value.trim()
            return trimmed.ifEmpty { null }
        }
    }
}

data class TaskItem(
    val id: String,
    val title: String,
    /** Its time when it has one (a block or a timed due date). */
    val at: Instant? = null,
    val done: Boolean = false,
    /** The person asked Find a time to place it (Task's `autoAllocate`). */
    val autoAllocate: Boolean = false,
)

// MARK: Find a time: POST /v1/planner/allocate, GET /v1/planner/plan/{date}

/**
 * A day's plan from Find a time. With work hours, a morning band and a work day it follows the recovery rhythm
 * (zones kept free, a break after deep work, tasks split to fit, recovery moved around the person's own blocks).
 */
data class DayPlan(
    val date: String,
    val generatedAt: Instant,
    val basis: PlanBasis = PlanBasis(),
    /** In time order. */
    val placed: List<PlanBlock> = emptyList(),
    val unplaced: List<UnplacedTask> = emptyList(),
    val warnings: PlanWarnings = PlanWarnings(),
    val version: Int = 1,
)

/** What the plan was built on. */
data class PlanBasis(
    val quickCheckAt: Instant? = null,
    val attention: Double? = null,
    val sleepMinutes: Double? = null,
    val sharpestHours: SharpestHours? = null,
    val capacityOpening: Double? = null,
    val workLimitMinutes: Int? = null,
)

data class SharpestHours(val startHour: Int, val endHour: Int)

/** One block of the plan. `kind` "recovery" is a 10-min rest after deep work: no task, rest only, not work. */
data class PlanBlock(
    val taskId: String?,
    val blockId: String?,
    val start: Instant,
    val end: Instant,
    val demand: Double,
    val workType: String? = null,
    val reason: String,
    /** focus, meeting or recovery. An older Planner sends none, which reads as focus. */
    val kind: String = "focus",
    /** Set on the pieces of a task split around recovery zones. */
    val part: PlanPart? = null,
) {
    val isRecovery: Boolean get() = kind == "recovery"

    /** "Recovery" for a rest block, "Title (1/2)" for a piece, else the task's title. */
    fun title(taskTitle: String): String {
        if (isRecovery) return "Recovery"
        part?.let { return "$taskTitle (${it.index}/${it.of})" }
        return taskTitle
    }
}

data class PlanPart(val index: Int, val of: Int)

/**
 * A task that didn't fit. `reason` is the Planner's code (noFreeTime, overCapacity, workLimit, dueLater, tooLong,
 * missingEstimate), kept as sent so a new code still reads; `detail` is the sentence to show.
 */
data class UnplacedTask(val taskId: String, val reason: String, val detail: String)

/**
 * committedOnRecovery: recovery zones that one of the person's own blocks (a meeting, their own focus) sat on.
 * recoveryMoved: how many of those recoveries moved to free time; the rest were skipped.
 */
data class PlanWarnings(val committedOnRecovery: Int = 0, val recoveryMoved: Int = 0) {
    /** The short note to show, or null when nothing sat on recovery time. */
    val note: String?
        get() {
            if (committedOnRecovery <= 0) return null
            val blocks = if (committedOnRecovery == 1) "1 of your blocks was" else "$committedOnRecovery of your blocks were"
            val moved = when {
                recoveryMoved == 0 -> "no free time to move the recovery"
                recoveryMoved == committedOnRecovery ->
                    if (committedOnRecovery == 1) "the recovery moved to free time" else "the recoveries moved to free time"
                else -> "$recoveryMoved moved to free time"
            }
            return "$blocks on recovery time; $moved."
        }
}

data class AlertItem(
    val id: String,
    val featureId: String,
    val title: String,
    val detail: String? = null,
    val date: String,
    val done: Boolean = false,
    /** The app action the alert asks for, from `action.ref`, e.g. a mode id. */
    val actionRef: String? = null,
)

/** One day of `GET /v1/metrics/daily`. */
data class DailyMetrics(
    val date: String,
    val burnoutIndexScore: Double? = null,
    val burnoutIndexRisk: String? = null,
)

// MARK: Search and metrics

/** One of the 25 sections of `GET /v1/metrics/catalogue`. */
data class CatalogueSection(val id: String, val title: String, val parameters: List<CatalogueParameter>)

data class CatalogueParameter(
    val name: String,
    /** Null when nothing measures it. */
    val field: String?,
    /** series, labels, profile, route or none. */
    val read: String,
    val unit: String,
    val format: String = "",
) {
    val id: String get() = (this.field ?: "none") + "/" + name

    /** Only `series` rows can draw the metric page today. */
    val hasSeries: Boolean get() = read == "series" && this.field != null
}

data class FieldAvailability(
    val field: String,
    val latestDate: String,
    /** Numbers only; words and yes/no values are shown as text. */
    val latestNumber: Double? = null,
    val latestText: String? = null,
)

data class MetricPoint(
    /** yyyy-MM-dd */
    val date: String,
    val value: Double?,
)

data class MetricSeries(val field: String, val unit: String?, val points: List<MetricPoint>)

// MARK: Sessions

data class StartedSession(
    val id: String,
    /** True when a session was already running and this answered with it. */
    val joined: Boolean = false,
    /** What the backend granted, which a tired-day limit can make shorter than asked. Null when open-ended. */
    val plannedMinutes: Int? = null,
    /** The running session's title when `joined` is true. Null when it has none. */
    val title: String? = null,
    /** The running session's kind when `joined` is true: focus, meeting, recovery, walk or brainstorm. */
    val kind: String? = null,
    /** The device the running session was started on when `joined` is true: phone, desktop, web. */
    val device: String? = null,
    /** When the running session ends when `joined` is true. Null when open-ended. */
    val endsAt: Instant? = null,
)

enum class SessionAction(val raw: String) {
    pause("pause"), resume("resume"), stop("stop"), complete("complete");

    companion object {
        fun fromRaw(raw: String): SessionAction? = entries.firstOrNull { it.raw == raw }
    }
}

data class SessionTrace(
    /** pending until the day is computed; noData without band data. */
    val status: String,
    val stressDrop: Double? = null,
)

// MARK: Quick check: POST /v1/checks

/** One Quick check as the phone ran it. The backend works out the measures and whether it counts. */
data class QuickCheckRun(
    val id: String = UUID.randomUUID().toString().uppercase(Locale.ROOT),
    val takenAt: Instant,
    val durationSeconds: Int,
    /** One per dot, in order; an unanswered dot holds its timeout. */
    val reactionTimesMs: List<Double>,
    /** Taps while no dot showed. */
    val falseStarts: Int,
    /** null when the memory part was skipped. */
    val memory: SpanResult? = null,
) {
    /** "full" with the memory part, else "pvtB". */
    val kind: String get() = if (memory == null) "pvtB" else "full"
}

/** The memory part's result: rounds answered right out of rounds answered. */
data class SpanResult(val spanCorrect: Int, val spanAttempts: Int)

/** What the backend made of a Quick check. */
data class QuickCheckResult(
    val id: String,
    val valid: Boolean,
    /**
     * The server's code when not valid: not_3_minutes, too_few_responses or too_many_false_starts. The screen turns
     * it into words.
     */
    val invalidReason: String?,
    val meanRtMs: Double,
    val medianRtMs: Double,
    val lapses: Int,
    val falseStarts: Int,
    /** 0-1, Basner's performance score. */
    val performance: Double,
    /** Counts toward your normal (a morning check 30+ min after waking, after 7+ h of sleep). */
    val qualifiesForNormal: Boolean,
    /** Today's scores after this check. */
    val scores: ScoresToday,
)

// MARK: Day zones

/** The four shades behind the calendar. The backend places them; the app only draws them. */
enum class ZoneKind(val raw: String) {
    heavyWork("heavyWork"), lightWork("lightWork"), shortRecovery("shortRecovery"), heavyRecovery("heavyRecovery");

    companion object {
        fun fromRaw(raw: String): ZoneKind? = entries.firstOrNull { it.raw == raw }
    }
}

data class Zone(val kind: ZoneKind, val start: Instant, val end: Instant) {
    /** "<kind>-<seconds since 1970>", as the Swift id. */
    val id: String
        get() {
            val seconds = BigDecimal.valueOf(start.toEpochMilli(), 3).stripTrailingZeros()
            val text = if (seconds.scale() <= 0) seconds.setScale(1).toPlainString() else seconds.toPlainString()
            return "${kind.raw}-$text"
        }
}

/** Only `ready` days have zones. `unknown` is a status this app version doesn't know yet. */
enum class ZoneStatus(val raw: String) {
    ready("ready"), needsSchedule("needsSchedule"), noScore("noScore"), dayOff("dayOff"), unknown("unknown");

    companion object {
        fun fromRaw(raw: String): ZoneStatus? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * One day from `GET /v1/planner/zones`. The score is the day's morning capacity score, which the Planner reads from
 * Capacity: the app never asks for it on its own.
 */
data class ZoneDay(
    /** yyyy-MM-dd (a `DayKey`). */
    val date: String,
    val status: ZoneStatus,
    val score: Int? = null,
    /** peak, focused, steady, shallow or depleted. */
    val level: String? = null,
    val heavyMinutes: Int = 0,
    val reason: String? = null,
    val zones: List<Zone> = emptyList(),
) {
    val id: String get() = date
}

/** The profile's work hours, which the zones are placed inside. Times are "HH:MM" on the person's own clock. */
data class WorkSchedule(
    val workdayStart: String,
    val workdayEnd: String,
    val lunchStart: String,
    /** 0 to 240. */
    val lunchMinutes: Int,
    /** 1 = Monday ... 7 = Sunday, sorted, no repeats. */
    val workDays: List<Int>,
) {
    /**
     * What's wrong, in words for the form, or null when the backend will take it. Same rules and wording as the
     * website's `scheduleProblem`, plus the backend's 240-minute lunch limit.
     */
    val problem: String?
        get() {
            val start = minutes(workdayStart)
            val end = minutes(workdayEnd)
            val lunch = minutes(lunchStart)
            if (start == null || end == null || lunch == null) return "Choose every time."
            if (end <= start) return "Work must end after it starts."
            if (lunchMinutes < 0) return "Lunch can't have a negative length."
            if (lunchMinutes > 240) return "Lunch can be at most 4 hours."
            if (lunch < start || lunch + lunchMinutes > end) return "Lunch must fit inside your work hours."
            if (workDays.isEmpty()) return "Choose at least one work day."
            if (workDays.any { it !in 1..7 } || workDays.toSet().size != workDays.size) {
                return "Choose each work day once."
            }
            return null
        }

    companion object {
        /** What the form opens with when nothing is set: the website's default. */
        val suggested = WorkSchedule(
            workdayStart = "09:00", workdayEnd = "18:00", lunchStart = "13:00",
            lunchMinutes = 60, workDays = listOf(1, 2, 3, 4, 5),
        )

        /** Minutes after midnight for "HH:MM", or null when it isn't a clock time. Two digits each, as the backend wants. */
        fun minutes(clock: String): Int? {
            val parts = clock.split(":")
            val twoDigits = parts.all { part -> part.length == 2 && part.all { it in '0'..'9' } }
            if (parts.size != 2 || !twoDigits) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0 until 24 || m !in 0 until 60) return null
            return h * 60 + m
        }

        /** "HH:MM" for minutes after midnight. */
        fun clock(minutes: Int): String = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
    }
}

// MARK: Beta day score

/** The answer to "How hard was this for you?" after a focus session. It sets the work's demand, so its cost. */
enum class Difficulty(val raw: String) {
    easy("easy"), light("light"), normal("normal"), hard("hard"), veryHard("veryHard");

    val id: String get() = raw

    /** Easy, Light, Normal, Hard, Very hard. */
    val label: String
        get() = when (this) {
            easy -> "Easy"
            light -> "Light"
            normal -> "Normal"
            hard -> "Hard"
            veryHard -> "Very hard"
        }

    companion object {
        fun fromRaw(raw: String): Difficulty? = entries.firstOrNull { it.raw == raw }
    }
}

/** What a recovery break was. Each lifts the day score for a while. */
enum class RecoveryType(val raw: String) {
    walk("walk"), nap("nap"), rest("rest"), outside("outside"), social("social");

    val id: String get() = raw

    /** Walk, Nap, Rest (no screen), Time outside, Talk with someone. */
    val label: String
        get() = when (this) {
            walk -> "Walk"
            nap -> "Nap"
            rest -> "Rest (no screen)"
            outside -> "Time outside"
            social -> "Talk with someone"
        }

    companion object {
        fun fromRaw(raw: String): RecoveryType? = entries.firstOrNull { it.raw == raw }
    }
}

/** One recorded session, as GET /v1/sessions lists it. */
data class SessionRecord(
    val id: String,
    /** focus, meeting, recovery, walk, brainstorm */
    val kind: String,
    val title: String? = null,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    /** activeMinutes when sent, else minutes, else 0. */
    val minutes: Int = 0,
    /** completed, pending, rescheduled, stopped, logged */
    val outcome: String? = null,
    val difficulty: Difficulty? = null,
    val recoveryType: RecoveryType? = null,
    /** Points the session cost (positive), when charged. */
    val capacityCost: Double? = null,
) {
    val isFocus: Boolean get() = kind == "focus"
}

data class LoggedBreak(
    val session: SessionRecord,
    /** False when the same id had been sent before. */
    val created: Boolean,
)

/** One break GET /v1/capacity/recovery-options offers, with the most it lifts the score. */
data class RecoveryOption(
    /** nap, walk, rest, outside, social */
    val id: String,
    val title: String,
    val minutes: Int,
    val maxPoints: Double,
    /** 0 for a give-back that doesn't fade. */
    val fadesAfterMinutes: Int,
    val note: String,
) {
    /** The matching type, null for an id the app doesn't know. */
    val type: RecoveryType? get() = RecoveryType.fromRaw(id)

    /** "+9, fades within 50 min"; "+10, fades within 2 h" (whole hours as h); one decimal kept only when not whole. */
    val hint: String
        get() {
            val points = "+" + pointsText(maxPoints)
            if (fadesAfterMinutes <= 0) return "$points, does not fade"
            val fades = if (fadesAfterMinutes >= 60 && fadesAfterMinutes % 60 == 0) {
                "${fadesAfterMinutes / 60} h"
            } else {
                "$fadesAfterMinutes min"
            }
            return "$points, fades within $fades"
        }

    companion object {
        /** "9" for a whole number, else one decimal, "2.5". */
        internal fun pointsText(value: Double): String {
            if (!value.isFinite() || abs(value) >= 1_000_000) return "0"
            val tenths = roundHalfAway(value * 10) / 10
            if (tenths == roundHalfAway(tenths)) return tenths.toInt().toString()
            return String.format(Locale.ROOT, "%.1f", tenths)
        }
    }
}

/** One block the Review inbox asks about. */
data class ReviewItem(
    val blockId: String,
    /** A `DayKey`. */
    val date: String,
    val title: String,
    /** focus, meeting, recovery */
    val kind: String,
    val start: Instant,
    val end: Instant,
    val minutes: Int,
) {
    val id: String get() = blockId
    val isRecovery: Boolean get() = kind == "recovery"
}

enum class ReviewIntensity(val raw: String) {
    light("light"), steady("steady"), deep("deep");

    val id: String get() = raw

    /** Light, Steady, Deep. */
    val label: String
        get() = when (this) {
            light -> "Light"
            steady -> "Steady"
            deep -> "Deep"
        }

    companion object {
        fun fromRaw(raw: String): ReviewIntensity? = entries.firstOrNull { it.raw == raw }
    }
}

/** The answer to one inbox item. A recovery block sends `recoveryType`; others send intensity and drain. */
data class ReviewAnswer(
    val item: ReviewItem,
    val happened: Boolean,
    val intensity: ReviewIntensity? = null,
    /** 1 (gave energy) ... 5 (drained). */
    val drain: Int? = null,
    val recoveryType: RecoveryType? = null,
)

/** The profile's usual sleep times, "HH:mm" each. */
data class SleepSchedule(val bedtime: String, val wakeTime: String)

/**
 * One sleep sample, mapped out of the platform's health store by the app (core doesn't import it).
 *
 * Health Connect mapping on Android: the SleepSessionRecord's own span is `inBed`; its stages map
 * LIGHT -> `asleepCore`, DEEP -> `asleepDeep`, REM -> `asleepREM`, SLEEPING and UNKNOWN -> `asleepUnspecified`,
 * AWAKE, AWAKE_IN_BED and OUT_OF_BED -> `awake`.
 */
data class SleepSample(val start: Instant, val end: Instant, val kind: Kind) {
    enum class Kind(val raw: String) {
        inBed("inBed"), asleepCore("asleepCore"), asleepDeep("asleepDeep"), asleepREM("asleepREM"),
        asleepUnspecified("asleepUnspecified"), awake("awake");

        /** True for the four asleep stages. */
        val isAsleep: Boolean
            get() = when (this) {
                asleepCore, asleepDeep, asleepREM, asleepUnspecified -> true
                inBed, awake -> false
            }

        companion object {
            fun fromRaw(raw: String): Kind? = entries.firstOrNull { it.raw == raw }
        }
    }
}

/** One night for PUT /v1/sleep/nights/{date}. */
data class SleepNight(
    /** The `DayKey` of the wake, in `timeZone`. */
    val date: String,
    /** An IANA id. */
    val timeZone: String,
    val bedtimeAt: Instant,
    val wakeAt: Instant,
    val totalSleepMin: Int,
    val awakeMin: Int? = null,
    val inBedMin: Int? = null,
)

data class SleepNightResult(
    val created: Boolean,
    /** band, appleHealth or checkin: a higher source already there is kept. */
    val source: String,
)

// MARK: Health Connect day (Android only): PUT /v1/health/days/{date}

/** One day of Health Connect data. `date` is the `DayKey` in `timeZone` (an IANA id). */
data class HealthDay(
    val date: String,
    val timeZone: String,
    val recordedAt: Instant,
    /** The apps the data came from, by package name. */
    val sources: List<String>,
    val metrics: HealthMetrics,
    val workouts: List<HealthWorkout>,
)

/** The day's totals and averages. Null when Health Connect had nothing for it. */
data class HealthMetrics(
    val stepCount: Int? = null,
    val distanceKm: Double? = null,
    val activeKcal: Double? = null,
    val exerciseMinutes: Int? = null,
    val restingHrBpm: Double? = null,
    val hrvRmssdMs: Double? = null,
    val breathsPerMin: Double? = null,
    val spo2Pct: Double? = null,
    val skinTempDeltaC: Double? = null,
    val mindfulMinutes: Int? = null,
    val weightKg: Double? = null,
    val sleepDeepMin: Int? = null,
    val sleepLightMin: Int? = null,
    val sleepRemMin: Int? = null,
    val sleepAwakeMin: Int? = null,
)

/** One exercise session of the day. */
data class HealthWorkout(
    val id: String,
    val type: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val minutes: Int,
    val activeKcal: Double?,
    val distanceKm: Double?,
    val avgHrBpm: Double?,
    val source: String?,
)

// MARK: Errors

sealed class AuraError(message: String) : Exception(message) {
    data object Offline : AuraError("You're offline. Check your connection and try again.")

    data object SignedOut : AuraError("You're signed out. Sign in again.")

    /** Health data consent is missing: the app shows the consent step, then retries. */
    data object ConsentRequired : AuraError("Aura needs your consent to read health data.")

    data class Server(val code: String, override val message: String, val status: Int) : AuraError(message)

    /** The backend's error code for `Server`, else null. */
    val serverCode: String? get() = (this as? Server)?.code
}

// MARK: Shared helpers

/** Reads and writes an `Instant` as ISO-8601 text, for types kept on the device. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("info.ibtaura.aura.core.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** Swift's `String.count`: user-perceived characters, not UTF-16 units. */
private fun String.characterCount(): Int {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(this)
    var count = 0
    while (iterator.next() != BreakIterator.DONE) count++
    return count
}

/** Swift's `Double.rounded()`: halves go away from zero. */
private fun roundHalfAway(value: Double): Double =
    if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)

private val wireDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

/** Swift `WireDate.string`: whole seconds with the phone's own offset. */
private fun wireDateString(instant: Instant): String =
    OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), ZoneId.systemDefault()).format(wireDateFormat)
