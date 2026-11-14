package info.ibtaura.aura.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Opens connections to the service hosts before the screens need them. A new connection costs a phone 1 to 1.5 s
 * (DNS, TCP, TLS); a reused one about 250 ms a call, and the hosts speak HTTP/1.1, so each call running at once needs
 * its own connection.
 *
 * Each connection is opened with an unsigned `HEAD /` to the host's root. No route matches it, so API Gateway answers
 * 404 itself and no Lambda runs; no token goes with it. Every answer and error is ignored. Home's two busiest hosts
 * (Health Data and Session, three or four calls each) get two connections, the others one. A second call within
 * [quietMillis] does nothing.
 */
internal class ConnectionWarmer(
    private val config: AppConfig,
    http: OkHttpClient,
    private val quietMillis: Long = 30_000L,
    private val ticker: () -> Long = { System.nanoTime() },
) {
    companion object {
        /** Hosts Home calls several times at once. */
        private val busy = setOf(ServiceName.healthData, ServiceName.session)
    }

    /** The same pool and dispatcher, with a short limit: a warm-up that hangs is no use. */
    private val client = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    /** When the last warm-up started, in ticker nanos; Long.MIN_VALUE before the first. */
    private val lastStart = AtomicLong(Long.MIN_VALUE)

    /** Opens the connections at once and returns when all have answered or failed. Never throws, except cancellation. */
    suspend fun warmUp() {
        if (!claim()) return
        val targets = targets()
        coroutineScope {
            targets.map { (service, root) -> async { touch(service, root) } }.awaitAll()
        }
    }

    /** One root URL per connection to open: two for the busy hosts, one for the rest; each host once. */
    internal fun targets(): List<Pair<ServiceName, HttpUrl>> {
        val seen = mutableSetOf<String>()
        val targets = mutableListOf<Pair<ServiceName, HttpUrl>>()
        for (service in ServiceName.entries) {
            val base = config.baseURL(service) ?: continue
            val root = base.newBuilder().encodedPath("/").query(null).fragment(null).build()
            if (!seen.add("${root.scheme}://${root.host}:${root.port}")) continue
            val count = if (service in busy) 2 else 1
            repeat(count) { targets.add(service to root) }
        }
        return targets
    }

    /** True for the first caller in [quietMillis]; the others return at once. */
    private fun claim(): Boolean {
        val now = ticker()
        while (true) {
            val last = lastStart.get()
            if (last != Long.MIN_VALUE && (now - last) / 1_000_000 < quietMillis) return false
            if (lastStart.compareAndSet(last, now)) return true
        }
    }

    private suspend fun touch(service: ServiceName, root: HttpUrl) {
        val started = System.nanoTime()
        val request = Request.Builder()
            .url(root)
            .head()
            .header("X-Aura-App", "${config.platform}/${config.appVersion}")
            .build()
        val outcome = try {
            "status=${answer(client.newCall(request))}"
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            "failed"
        }
        if (PerfLog.on) PerfLog.line("warm host=${root.host.substringBefore('.')} ${service.raw} ms=${PerfLog.ms(started)} $outcome")
    }

    /** The call's status code; cancelling the coroutine cancels the call. */
    private suspend fun answer(call: Call): Int = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.use { it.code }
                continuation.resume(code)
            }
        })
    }
}
