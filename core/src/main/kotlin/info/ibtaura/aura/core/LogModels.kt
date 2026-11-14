package info.ibtaura.aura.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant

// The logbook: manual logs (POST/GET /v1/logs) and check-ins (POST/GET /v1/checkins) on the Health Data service.
// Shapes follow packages/contracts/src/health-data.ts (submitLog, logView, submitCheckin, checkinView) in aws_docs.
// The server keeps each entry as first sent: there is no edit and no delete for either.

/** What a manual log is (`LOG_TYPES`). */
enum class LogType(val raw: String) {
    measurement("measurement"),
    strength("strength"),
    food("food"),
    water("water"),
    mood("mood"),
    custom("custom");

    companion object {
        fun of(raw: String?): LogType? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * One manual log as the server keeps it. `data` holds numbers, text (at most 500 characters), true/false or null;
 * read it with [number], [text] and [flag]. `date` is a `DayKey`.
 */
data class LogEntry(
    val id: String,
    val type: LogType,
    val date: String,
    val takenAt: Instant?,
    val data: Map<String, JsonElement>,
    val createdAt: Instant?,
) {
    /** When it happened: `takenAt`, else when it was saved. */
    val at: Instant? get() = takenAt ?: createdAt

    fun number(key: String): Double? = (data[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    fun text(key: String): String? = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun flag(key: String): Boolean? = (data[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    fun has(key: String): Boolean = data[key] != null && data[key] !is kotlinx.serialization.json.JsonNull
}

/** What a check-in is (`CHECKIN_KINDS`). */
enum class CheckinKind(val raw: String) {
    morning("morning"),
    weekly("weekly"),
    insomnia("insomnia"),
    block("block"),
    mood("mood"),
    connection("connection");

    /**
     * The answers the server needs at least one of (journal.ts `NEEDS`); a check-in without any is refused with 400
     * `invalid_request`.
     */
    val needs: List<String>
        get() = when (this) {
            morning -> listOf("alcohol", "ill", "drowsyMedicine", "alertness", "stress", "fellAsleepAt", "wokeAt")
            weekly -> listOf("exhaustion")
            insomnia -> listOf("insomnia")
            block -> listOf("effort", "stress")
            mood -> listOf("mood")
            connection -> listOf("connection")
        }

    companion object {
        fun of(raw: String?): CheckinKind? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * One check-in as the server lists it. Each answer is null when not given. `date` is the local day the server filed it
 * under (the profile's time zone). `insomniaScore` is the 7 items' total, 0 to 28.
 */
data class Checkin(
    val id: String,
    val kind: CheckinKind,
    val takenAt: Instant?,
    val date: String,
    val alertness: Int? = null,
    val stress: Int? = null,
    val exhaustion: Int? = null,
    val insomniaScore: Int? = null,
    val effort: Int? = null,
    val mood: Int? = null,
    val connection: Int? = null,
    val fellAsleepAt: String? = null,
    val wokeAt: String? = null,
)

/** Ids for logs and check-ins are made on the phone, so resending one is safe. */
object LogIds {
    fun new(): String = java.util.UUID.randomUUID().toString().lowercase(java.util.Locale.ROOT)
}

/** The Insomnia Severity Index's bands for a 0-28 total (Bastien 2001). */
object InsomniaScale {
    /** The seven items, each answered 0 to 4. */
    val items: List<String> = listOf(
        "Trouble falling asleep",
        "Trouble staying asleep",
        "Waking up too early",
        "How satisfied are you with your sleep?",
        "How much do sleep problems show to others?",
        "How worried are you about your sleep?",
        "How much does sleep get in the way of your day?",
    )

    fun band(total: Int): String = when {
        total <= 7 -> "No insomnia"
        total <= 14 -> "Below the line"
        total <= 21 -> "Moderate insomnia"
        else -> "Severe insomnia"
    }
}
