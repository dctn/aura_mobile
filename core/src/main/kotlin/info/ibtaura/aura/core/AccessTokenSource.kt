package info.ibtaura.aura.core

import kotlinx.coroutines.delay
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

/** One OkHttp client for the API and sign-in: 25 s timeouts and no disk cache, so no health data is kept on disk. */
fun auraHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(25, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .writeTimeout(25, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()
