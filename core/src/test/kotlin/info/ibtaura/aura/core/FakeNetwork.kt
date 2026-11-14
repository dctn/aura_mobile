package info.ibtaura.aura.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

// The Kotlin side of the iOS tests' StubNetwork.swift: an OkHttp interceptor answers every call, so nothing in these
// tests can leave the machine.

sealed class NetAnswer {
    data class Json(val status: Int, val text: String) : NetAnswer()

    /** No answer at all: the phone is offline. */
    data object Offline : NetAnswer()

    /** The call was cancelled (the screen went away). */
    data object Cancelled : NetAnswer()
}

fun netJson(status: Int, text: String): NetAnswer = NetAnswer.Json(status, text)

/** One request as the fake saw it. `body` is its JSON object, empty when it had none. */
class NetCall(val key: String, val request: Request, val bodyText: String) {
    val body: JsonObject = runCatching { Json.parseToJsonElement(bodyText) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
    val method: String get() = request.method
    val url: HttpUrl get() = request.url
    val host: String get() = request.url.host
    val address: String get() = request.url.toString()

    fun header(name: String): String? = request.header(name)

    val query: Map<String, String>
        get() = request.url.queryParameterNames.associateWith { request.url.queryParameter(it) ?: "" }

    fun string(name: String): String? = (body[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun int(name: String): Int? = (body[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    fun bool(name: String): Boolean? = (body[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    fun isNull(name: String): Boolean = body.containsKey(name) && body[name] is JsonNull
    fun obj(name: String): JsonObject? = body[name] as? JsonObject
}

/** Answers by route (the decoded path). A queue's last answer repeats. */
class NetServer {
    private val lock = Any()
    private val queues = mutableMapOf<String, MutableList<NetAnswer>>()
    private val handlers = mutableMapOf<String, (NetCall) -> NetAnswer>()
    private val delays = mutableMapOf<String, Long>()
    private val recorded = mutableListOf<NetCall>()
    private var open = 0
    private var mostOpen = 0

    fun on(key: String, answers: List<NetAnswer>, delayMs: Long = 0) = synchronized(lock) {
        queues[key] = answers.toMutableList()
        delays[key] = delayMs
    }

    fun on(key: String, vararg answers: NetAnswer) = on(key, answers.toList())

    fun respond(key: String, delayMs: Long = 0, handler: (NetCall) -> NetAnswer) = synchronized(lock) {
        handlers[key] = handler
        delays[key] = delayMs
    }

    fun calls(key: String): List<NetCall> = synchronized(lock) { recorded.filter { it.key == key } }
    val allCalls: List<NetCall> get() = synchronized(lock) { recorded.toList() }
    val peakOpen: Int get() = synchronized(lock) { mostOpen }

    private val interceptor = Interceptor { chain ->
        val request = chain.request()
        val text = request.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() } ?: ""
        val key = request.url.pathSegments.joinToString("/", prefix = "/")
        val call = NetCall(key, request, text)
        val (answer, delay) = take(call)
        try {
            if (delay > 0) Thread.sleep(delay)
        } finally {
            synchronized(lock) { open -= 1 }
        }
        when (answer) {
            is NetAnswer.Offline -> throw IOException("The Internet connection appears to be offline.")
            is NetAnswer.Cancelled -> {
                chain.call().cancel()
                throw IOException("Canceled")
            }
            is NetAnswer.Json -> Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(answer.status)
                .message("stub")
                .body(answer.text.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun take(call: NetCall): Pair<NetAnswer, Long> = synchronized(lock) {
        recorded.add(call)
        open += 1
        mostOpen = maxOf(mostOpen, open)
        val delay = delays[call.key] ?: 0
        handlers[call.key]?.let { return it(call) to delay }
        val queue = queues[call.key]
        if (queue.isNullOrEmpty()) {
            return netJson(500, "{\"error\":{\"code\":\"no_stub\",\"message\":\"${call.key}\"}}") to 0L
        }
        val first = queue.first()
        if (queue.size > 1) queue.removeAt(0)
        first to delay
    }

    /** A client whose every request goes to this fake. OkHttp's own per-host limit is lifted so the client's is tested. */
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 64
        })
        .addInterceptor(interceptor)
        .build()
}

class NetSleepLog {
    private val lock = Any()
    private val seconds = mutableListOf<Double>()

    fun add(value: Double) = synchronized(lock) { seconds.add(value) }
    val values: List<Double> get() = synchronized(lock) { seconds.toList() }
}

/**
 * Stands in for CognitoAuth: hands out "access-1", a refresh gives "access-2", and a token refused after a refresh ends
 * the session when it is still the current one.
 */
class NetTokens(first: String? = "access-1") : AccessTokenSource {
    private val lock = Any()
    private var held: String? = first
    var refreshes = 0
        private set
    var accessError: AuraError? = null
    var refreshError: AuraError? = null

    val current: String? get() = synchronized(lock) { held }

    fun replace(token: String?) = synchronized(lock) { held = token }

    override suspend fun accessToken(): String {
        accessError?.let { throw it }
        return current ?: throw AuraError.SignedOut
    }

    override suspend fun refreshedToken(rejected: String): String {
        refreshError?.let { throw it }
        synchronized(lock) {
            refreshes += 1
            held = "access-2"
        }
        return "access-2"
    }

    override suspend fun tokenRejected(token: String): Boolean = synchronized(lock) {
        if (held == token) {
            held = null
            true
        } else {
            false
        }
    }
}

/** The error a call threw, if any. Cancellation counts too. */
suspend fun netCaught(body: suspend () -> Unit): Throwable? =
    try {
        body()
        null
    } catch (error: CancellationException) {
        error
    } catch (error: Throwable) {
        error
    }

suspend fun netConcurrently(count: Int, work: suspend () -> Unit) = coroutineScope {
    (0 until count).map { async(Dispatchers.Default) { work() } }.awaitAll()
}

/** A live API wired to the fake, with a fixed clock and a sleep that returns at once. */
class NetRig(config: AppConfig = NetRig.config, val tokens: AccessTokenSource = NetTokens()) {
    companion object {
        val now: Instant = Instant.ofEpochSecond(1_900_000_000)

        val config: AppConfig
            get() = AppConfig(
                stage = "dev", region = "us-west-1", userPoolId = "us-west-1_stub", clientId = "stub-client",
                platform = "android", appVersion = "0.1.0", consentPolicyVersion = "2026-10",
                services = ServiceName.entries.associate { it.raw to "https://${it.raw}.stub.test" },
            )

        fun config(change: (MutableMap<String, String>) -> Unit): AppConfig {
            val services = config.services.toMutableMap()
            change(services)
            return config.copy(services = services)
        }
    }

    val server = NetServer()
    val sleeps = NetSleepLog()
    val http: OkHttpClient = server.client()
    val api = LiveApi(config, tokens, http, sleep = { sleeps.add(it) }, now = { now })
}

/** A JSON element as a string, for assertions. */
fun JsonElement?.netText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
