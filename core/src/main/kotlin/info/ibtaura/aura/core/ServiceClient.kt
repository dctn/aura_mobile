package info.ibtaura.aura.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// A port of AuraKit/HTTPClient.swift.

internal object URLEscape {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val HEX = "0123456789ABCDEF".toCharArray()

    /** Stricter than most encoders, which leave `+` alone: API Gateway would read that as a space, and cursors can hold it. */
    fun component(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val char = (byte.toInt() and 0xFF).toChar()
            if (byte >= 0 && UNRESERVED.indexOf(char) >= 0) {
                out.append(char)
            } else {
                val value = byte.toInt() and 0xFF
                out.append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
            }
        }
        return out.toString()
    }
}

/** An error body is `{"error": {code, message}}`; API Gateway's own are `{"message"}`. */
private class ServiceFailure(status: Int, text: String) {
    val hasErrorBody: Boolean
    val code: String
    val message: String

    init {
        val json = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val error = json?.get("error") as? JsonObject
        if (error != null) {
            hasErrorBody = true
            code = stringOf(error["code"]) ?: "http_$status"
            message = stringOf(error["message"]) ?: "HTTP $status"
        } else {
            hasErrorBody = false
            code = "http_$status"
            message = stringOf(json?.get("message")) ?: "HTTP $status"
        }
    }

    private fun stringOf(value: Any?): String? {
        val primitive = value as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}

/** One HTTP client for the services, each at its own URL. */
class ServiceClient(
    private val config: AppConfig,
    private val tokens: AccessTokenSource,
    private val http: OkHttpClient,
    private val sleep: AuraSleep,
    private val now: AuraNow,
) {
    companion object {
        val throttleDelays: List<Double> = listOf(1.0, 2.0, 4.0, 8.0)
        val consentWaits: List<Double> = listOf(1.0, 2.0, 3.0, 4.0)

        /** How long after a grant a `consent_required` answer is taken to be Health Data's copy lagging. */
        val consentWindow: Duration = Duration.ofSeconds(30)

        /** Dev's account runs few Lambdas at a time, so leave room for the backend. */
        const val maxInFlight = 6

        private val jsonType = "application/json".toMediaType()

        /** OkHttp refuses these without a body, so one with nothing to send gets an empty one. */
        private val methodsWithBody = setOf("POST", "PUT", "PATCH")
    }

    private val limiter = Semaphore(maxInFlight)

    @Volatile
    private var consentGrantedAt: Instant? = null

    fun noteConsentGranted() {
        consentGrantedAt = now()
    }

    /** One call: returns the body of a 2xx, throws `AuraError` for anything else. `path` must already be escaped. */
    suspend fun send(
        service: ServiceName,
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val url = makeURL(service, path, query)
        var refreshed = false
        var triedNewer = false
        var throttled = 0
        var consentTries = 0
        while (true) {
            val token = tokens.accessToken()
            val request = makeRequest(url, method, body, token, headers)
            val reply = transmit(request)
            if (reply.status in 200..299) return reply.text
            if (reply.status == 401) {
                if (!refreshed) {
                    tokens.refreshedToken(token)
                    refreshed = true
                    continue
                }
                val ended = tokens.tokenRejected(token)
                if (ended) throw AuraError.SignedOut
                // Another call refreshed while this one was out, so the session is fine: one more go with the newer token.
                if (!triedNewer) {
                    triedNewer = true
                    continue
                }
            }
            val failure = ServiceFailure(reply.status, reply.text)
            if (reply.status == 503 && !failure.hasErrorBody && throttled < throttleDelays.size) {
                sleep(throttleDelays[throttled])
                throttled += 1
                continue
            }
            if (reply.status == 403 && failure.code == "consent_required") {
                if (!consentJustGranted() || consentTries >= consentWaits.size) throw AuraError.ConsentRequired
                sleep(consentWaits[consentTries])
                consentTries += 1
                continue
            }
            throw AuraError.Server(failure.code, failure.message, reply.status)
        }
    }

    private fun consentJustGranted(): Boolean {
        val grantedAt = consentGrantedAt ?: return false
        return Duration.between(grantedAt, now()) < consentWindow
    }

    private fun makeURL(service: ServiceName, path: String, query: Map<String, String>): HttpUrl {
        val base = config.services[service.raw]
        if (base == null || config.baseURL(service) == null) {
            throw AuraError.Server("bad_config", "No address is set for ${service.raw}.", 0)
        }
        var text = base.trimEnd('/')
        text += path
        if (query.isNotEmpty()) {
            text += "?" + query.keys.sorted().joinToString("&") { key ->
                URLEscape.component(key) + "=" + URLEscape.component(query[key] ?: "")
            }
        }
        return text.toHttpUrlOrNull() ?: throw AuraError.Server("bad_config", "That address isn't valid.", 0)
    }

    private fun makeRequest(
        url: HttpUrl,
        method: String,
        body: String?,
        token: String,
        headers: Map<String, String>,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("X-Time-Zone", ZoneId.systemDefault().id)
            .header("X-Aura-App", "${config.platform}/${config.appVersion}")
        for ((name, value) in headers) builder.header(name, value)
        // The byte-array form keeps Content-Type exactly "application/json", without a charset.
        val requestBody: RequestBody? = when {
            body != null -> body.toByteArray(Charsets.UTF_8).toRequestBody(jsonType)
            method.uppercase() in methodsWithBody -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        builder.method(method, requestBody)
        return builder.build()
    }

    private class Reply(val status: Int, val text: String)

    /** Holds a slot only while the call is open, never while waiting to retry. */
    private suspend fun transmit(request: Request): Reply {
        try {
            return limiter.withPermit { execute(http.newCall(request)) }
        } catch (error: IOException) {
            // A call going away with its screen isn't the same as being offline.
            currentCoroutineContext().ensureActive()
            throw AuraError.Offline
        }
    }

    /** OkHttp's call as a suspending one: cancelling the coroutine cancels the call. */
    private suspend fun execute(call: Call): Reply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    continuation.resumeWithException(CancellationException("The call was cancelled.", e))
                } else {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { Reply(it.code, it.body.string()) }
                } catch (e: IOException) {
                    onFailure(call, e)
                    return
                }
                continuation.resume(reply)
            }
        })
    }
}
