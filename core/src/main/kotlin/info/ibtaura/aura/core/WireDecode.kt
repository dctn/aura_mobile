package info.ibtaura.aura.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

// JSON decoding for the live routes, off the main thread. LiveApi, LiveGoals and LiveLogbook decode through here.

/** What a body that can't be read becomes. */
internal val wireBadResponse: AuraError
    get() = AuraError.Server("bad_response", "Aura sent an answer this app can't read.", 200)

/**
 * Decodes one answer on a background thread, so a big one (the 175 KB catalogue) can't stall a screen. Still timed by
 * `PerfLog.decode`, whose line names the thread it ran on. A body that doesn't fit throws `bad_response`.
 */
internal suspend fun <T> wireDecode(serializer: KSerializer<T>, text: String): T = withContext(Dispatchers.Default) {
    try {
        PerfLog.decode(serializer.descriptor.serialName, text.length) { wireJson.decodeFromString(serializer, text) }
    } catch (_: Exception) {
        throw wireBadResponse
    }
}

/** A body read as a plain JSON object, on a background thread. Not an object (or not JSON) throws `bad_response`. */
internal suspend fun wireObject(text: String): JsonObject = withContext(Dispatchers.Default) {
    val parsed = try {
        PerfLog.decode("object", text.length) { wireJson.parseToJsonElement(text) as? JsonObject }
    } catch (_: Exception) {
        null
    }
    parsed ?: throw wireBadResponse
}
