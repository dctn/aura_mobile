package info.ibtaura.aura.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

// The logbook's routes, called from LiveApi (MARK: Logbook and MARK: Settings (logbook)). Kept apart to keep merges
// with the other features' LiveApi additions small.

// MARK: Wire shapes (health-data.ts logView, checkinView; identity.ts profile view)

@Serializable
internal data class WireLogView(
    val id: String,
    val type: String? = null,
    val date: String? = null,
    val takenAt: String? = null,
    val data: JsonObject? = null,
    val createdAt: String? = null,
) {
    /** Null for a type this app doesn't know or a log without a date. */
    val model: LogEntry?
        get() {
            val kind = LogType.of(type) ?: return null
            val day = date?.takeIf { DayKey.localDate(it) != null } ?: return null
            return LogEntry(id = id, type = kind, date = day.take(10), takenAt = WireDate.parse(takenAt),
                data = data.orEmpty(), createdAt = WireDate.parse(createdAt))
        }
}

@Serializable
internal data class WireLogList(val logs: List<JsonElement>? = null) {
    val models: List<LogEntry> get() = logs.lossyItems(WireLogView.serializer()).mapNotNull { it.model }
}

@Serializable
internal data class WireLogAnswer(val log: WireLogView, val created: Boolean? = null)

@Serializable
internal data class WireCheckinView(
    val id: String,
    val kind: String? = null,
    val takenAt: String? = null,
    val date: String? = null,
    val alertness: Int? = null,
    val stress: Int? = null,
    val exhaustion: Int? = null,
    val insomniaScore: Int? = null,
    val effort: Int? = null,
    val mood: Int? = null,
    val connection: Int? = null,
    val fellAsleepAt: String? = null,
    val wokeAt: String? = null,
) {
    /** Null for a kind this app doesn't know. A missing date falls back to the local day of `takenAt`. */
    val model: Checkin?
        get() {
            val kind = CheckinKind.of(kind) ?: return null
            val at = WireDate.parse(takenAt)
            val day = date?.takeIf { DayKey.localDate(it) != null }?.take(10) ?: at?.let { DayKey.string(it) } ?: return null
            return Checkin(id = id, kind = kind, takenAt = at, date = day, alertness = alertness, stress = stress,
                exhaustion = exhaustion, insomniaScore = insomniaScore, effort = effort, mood = mood,
                connection = connection, fellAsleepAt = fellAsleepAt, wokeAt = wokeAt)
        }
}

@Serializable
internal data class WireCheckinList(val checkins: List<JsonElement>? = null) {
    val models: List<Checkin> get() = checkins.lossyItems(WireCheckinView.serializer()).mapNotNull { it.model }
}

@Serializable
internal data class WireCheckinAnswer(val checkin: WireCheckinView, val created: Boolean? = null)

/** The parts of GET /v1/me (and the PUT /v1/me/settings answer) the logbook reads. */
@Serializable
internal data class WireLogbookMe(
    val settings: JsonObject? = null,
    /** Lossy: only `waterGoalMl` is read. */
    val healthGoals: JsonElement? = null,
) {
    val waterGoalMl: Int? get() = healthGoals.lossy(WireWaterGoal.serializer())?.ml?.takeIf { it > 0 }
}

@Serializable
internal data class WireWaterGoal(val waterGoalMl: Double? = null) {
    val ml: Int? get() = waterGoalMl?.let { Math.round(it).toInt() }
}

// MARK: Calls

internal class LiveLogbook(private val client: ServiceClient) {
    companion object {
        /** `data` text is at most 500 characters on the server. */
        private const val maxText = 500

        /** Keys the check-in body sets itself, so an answer can't replace them. */
        private val reservedKeys = setOf("id", "kind", "takenAt")

        private fun <T> decode(serializer: KSerializer<T>, text: String): T =
            try {
                wireJson.decodeFromString(serializer, text)
            } catch (_: Exception) {
                throw AuraError.Server("bad_response", "Aura sent an answer this app can't read.", 200)
            }

        /** Text longer than the server takes is cut; everything else goes as it is. */
        internal fun cleaned(data: Map<String, JsonElement>): JsonObject = JsonObject(
            data.mapValues { (_, value) ->
                if (value is JsonPrimitive && value.isString && value.content.length > maxText) {
                    JsonPrimitive(value.content.take(maxText))
                } else {
                    value
                }
            },
        )
    }

    suspend fun logs(from: String, to: String, type: LogType?): List<LogEntry> {
        val query = mutableMapOf("from" to from, "to" to to)
        type?.let { query["type"] = it.raw }
        val text = client.send(ServiceName.healthData, "GET", "/v1/logs", query = query)
        return decode(WireLogList.serializer(), text).models
    }

    suspend fun addLog(id: String, type: LogType, date: String, takenAt: Instant?, data: Map<String, JsonElement>): LogEntry {
        val body = buildJsonObject {
            put("id", id)
            put("type", type.raw)
            put("date", date)
            takenAt?.let { put("takenAt", WireDate.string(it)) }
            put("data", cleaned(data))
        }
        val text = client.send(ServiceName.healthData, "POST", "/v1/logs", body = body.toString())
        return decode(WireLogAnswer.serializer(), text).log.model
            ?: throw AuraError.Server("bad_response", "Aura sent an answer this app can't read.", 200)
    }

    suspend fun checkins(from: String, to: String, kind: CheckinKind?): List<Checkin> {
        val query = mutableMapOf("from" to from, "to" to to)
        kind?.let { query["kind"] = it.raw }
        val text = client.send(ServiceName.healthData, "GET", "/v1/checkins", query = query)
        return decode(WireCheckinList.serializer(), text).models
    }

    suspend fun addCheckin(id: String, kind: CheckinKind, takenAt: Instant, answers: Map<String, JsonElement>): Checkin {
        val body = buildJsonObject {
            put("id", id)
            put("kind", kind.raw)
            put("takenAt", WireDate.string(takenAt))
            for ((key, value) in answers) {
                if (key in reservedKeys || value is JsonNull) continue
                put(key, value)
            }
        }
        val text = client.send(ServiceName.healthData, "POST", "/v1/checkins", body = body.toString())
        return decode(WireCheckinAnswer.serializer(), text).checkin.model
            ?: throw AuraError.Server("bad_response", "Aura sent an answer this app can't read.", 200)
    }

    // MARK: Settings and goals (Identity)

    suspend fun meSettings(): Map<String, JsonElement> = me().settings.orEmpty()

    /** PUT /v1/me/settings: merged key by key on the server, a null value removes the key. Nothing goes for no changes. */
    suspend fun putMeSettings(changes: Map<String, JsonElement?>) {
        if (changes.isEmpty()) return
        val body = JsonObject(changes.mapValues { it.value ?: JsonNull })
        client.send(ServiceName.identity, "PUT", "/v1/me/settings", body = body.toString())
    }

    suspend fun waterGoalMl(): Int? = me().waterGoalMl

    private suspend fun me(): WireLogbookMe {
        val text = client.send(ServiceName.identity, "GET", "/v1/me")
        return decode(WireLogbookMe.serializer(), text)
    }
}
