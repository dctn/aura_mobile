package info.ibtaura.aura.core

import kotlinx.coroutines.delay
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Waits between retries, in seconds. Tests pass one that returns at once. */
typealias AuraSleep = suspend (seconds: Double) -> Unit

/** The clock. Tests pass a fixed one. */
typealias AuraNow = () -> Instant

val realSleep: AuraSleep = { seconds -> delay((seconds * 1000).toLong()) }
val realNow: AuraNow = { Instant.now() }

/** What the HTTP client needs from the sign-in layer (CognitoAuth implements it). */
interface AccessTokenSource {
    /** A token that's good now, refreshed first when it's close to expiry. */
    suspend fun accessToken(): String

    /** The API answered 401 to [rejected]: a newer token, refreshing only if nobody already has. */
    suspend fun refreshedToken(rejected: String): String

    /**
     * The API refused a token that had just been refreshed. True when that ended the session, false when a newer
     * token has already replaced it and the session goes on.
     */
    suspend fun tokenRejected(token: String): Boolean
}

/**
 * One OkHttp client for the API and sign-in: 25 s timeouts and no disk cache, so no health data is kept on disk.
 *
 * The services are about ten API Gateway hosts that speak HTTP/1.1 only, so every call running at once needs its own
 * connection, and a new one costs a second or more on a phone. The pool keeps up to 16 idle connections for five
 * minutes (OkHttp's default is 5), and the dispatcher lets 16 calls run per host (its default of 5 sat under
 * `ServiceClient.maxInFlight`).
 */
fun auraHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
    .dispatcher(Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    })
    .connectTimeout(25, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .writeTimeout(25, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .eventListenerFactory(PerfEventListener.factory)
    .build()
