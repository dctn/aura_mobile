package info.ibtaura.aura.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.util.Base64

// The port of the Cognito half of AuraKitTests/StubNetwork.swift: an OkHttp interceptor that answers every request
// itself, so nothing in these tests can leave the machine and the request keeps its real Cognito URL.

sealed class CognitoAnswer {
    data class Json(val status: Int, val text: String) : CognitoAnswer()
    /** No answer at all, like a phone with no connection. */
    data object Fail : CognitoAnswer()
}

/** One request as the fake saw it. `body` is its JSON object, empty when it had none. */
class CognitoCall(val key: String, val request: Request, val body: JsonObject) {
    fun header(name: String): String? = request.header(name)

    fun string(name: String): String? = (body[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A string one level down in the body, like `AuthParameters.USERNAME`. */
    fun nested(outer: String, inner: String): String? =
        ((body[outer] as? JsonObject)?.get(inner) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Answers by route: the `X-Amz-Target` action for Cognito, else the path. A queue's last answer repeats. */
class FakeCognito : Interceptor {
    private val lock = Any()
    private val queues = mutableMapOf<String, List<CognitoAnswer>>()
    private val delays = mutableMapOf<String, Long>()
    private val recorded = mutableListOf<CognitoCall>()

    fun on(key: String, answers: List<CognitoAnswer>, delayMillis: Long = 0) = synchronized(lock) {
        queues[key] = answers
        delays[key] = delayMillis
    }

    fun calls(key: String): List<CognitoCall> = synchronized(lock) { recorded.filter { it.key == key } }

    val allCalls: List<CognitoCall> get() = synchronized(lock) { recorded.toList() }

    /** A client whose every request goes to this fake. */
    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = request.header("X-Amz-Target")?.substringAfterLast('.') ?: request.url.encodedPath
        val text = request.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() } ?: ""
        val body = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val (answer, delay) = synchronized(lock) {
            recorded.add(CognitoCall(key, request, body))
            val queue = queues[key]
            if (queue.isNullOrEmpty()) {
                CognitoAnswer.Json(500, "{\"error\":{\"code\":\"no_stub\",\"message\":\"$key\"}}") to 0L
            } else {
                if (queue.size > 1) queues[key] = queue.drop(1)
                queue.first() to (delays[key] ?: 0L)
            }
        }
        if (delay > 0) Thread.sleep(delay)
        if (chain.call().isCanceled()) throw IOException("Canceled")
        return when (answer) {
            CognitoAnswer.Fail -> throw IOException("The Internet connection appears to be offline.")
            is CognitoAnswer.Json -> Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(answer.status)
                .message("stub")
                .body(answer.text.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}

/** A token shaped like a JWT, with an unsigned payload. */
fun cognitoTestJWT(claims: Map<String, Any>): String {
    val json = JsonObject(
        claims.mapValues { (_, value) ->
            when (value) {
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        },
    )
    val body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    return "header.$body.signature"
}

/** A sign-in wired to the fake, with a fixed clock. */
class CognitoRig(tokens: AuthTokens? = tokens(), config: AppConfig = CognitoRig.config) {
    companion object {
        val now: Instant = Instant.ofEpochSecond(1_900_000_000)
        const val email = "person@example.com"
        val idToken = cognitoTestJWT(mapOf("sub" to "sub-1", "email" to email))

        val config: AppConfig
            get() = AppConfig(
                stage = "dev", region = "us-west-1", userPoolId = "us-west-1_stub", clientId = "stub-client",
                platform = "android", appVersion = "0.1.0", consentPolicyVersion = "2026-10",
                services = ServiceName.entries.associate { it.raw to "https://${it.raw}.stub.test" },
            )

        fun tokens(expiresIn: Long = 3600): AuthTokens = AuthTokens(
            idToken = idToken, accessToken = "access-1", refreshToken = "refresh-1",
            expiresAt = now.plusSeconds(expiresIn),
        )

        /** Cognito's answer to REFRESH_TOKEN_AUTH: it doesn't rotate the refresh token. */
        val refreshAnswer: CognitoAnswer
            get() = CognitoAnswer.Json(200, "{\"AuthenticationResult\":{\"AccessToken\":\"access-2\",\"IdToken\":\"$idToken\",\"ExpiresIn\":3600}}")

        /** Cognito's answer once a sign-in is complete. */
        val signedInAnswer: CognitoAnswer
            get() = CognitoAnswer.Json(200, "{\"AuthenticationResult\":{\"AccessToken\":\"access-1\",\"IdToken\":\"$idToken\",\"RefreshToken\":\"refresh-1\",\"ExpiresIn\":3600}}")

        fun refused(kind: String, message: String): CognitoAnswer =
            CognitoAnswer.Json(400, "{\"__type\":\"$kind\",\"message\":\"$message\"}")
    }

    val server = FakeCognito()
    val store = InMemoryTokenStore(tokens)
    val auth = CognitoAuth(config = config, store = store, http = server.client(), now = { now })
}
