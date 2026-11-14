package info.ibtaura.aura.core

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Timing lines for the speed pass (debug builds only). The app sets [sink] when it's debuggable; release leaves it
 * null and every call here returns at once. Lines carry route shapes and times only: never ids, query values, bodies,
 * tokens or health values.
 */
object PerfLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    val on: Boolean get() = sink != null

    fun line(text: String) {
        sink?.invoke(text)
    }

    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** Counts open API calls; "idle" marks the moment the last one ends, which the walk reads as "settled". */
    fun callStarted() {
        if (on) inFlight.incrementAndGet()
    }

    fun callEnded() {
        if (on && inFlight.decrementAndGet() <= 0) {
            inFlight.set(0)
            line("idle")
        }
    }

    fun ms(fromNanos: Long): Long = (System.nanoTime() - fromNanos) / 1_000_000

    /** Times one JSON decode and says whether it ran on the main thread (where it would stall the screen). */
    inline fun <T> decode(name: String, bytes: Int, block: () -> T): T {
        if (!on) return block()
        val start = System.nanoTime()
        val result = block()
        val thread = Thread.currentThread().name
        line("decode ${name.substringAfterLast('.')} bytes=$bytes ms=${ms(start)} thread=$thread")
        return result
    }

    private val idLike = Regex("^(?=.*\\d)[A-Za-z0-9_.:%-]{6,}$")
    private val dateLike = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** `/v1/tasks/01HX…/complete` → `/v1/tasks/{id}/complete`; dates become `{date}`. */
    fun template(path: String): String = path.split('/').joinToString("/") { part ->
        when {
            part.isEmpty() -> part
            dateLike.matches(part) -> "{date}"
            idLike.matches(part) && part != "v1" -> "{id}"
            else -> part
        }
    }
}

/** DNS, connect and TLS times per call, and whether the connection was reused. */
class PerfEventListener : EventListener() {
    private val start = System.nanoTime()
    private var dnsStart = 0L
    private var connectStart = 0L
    private var tlsStart = 0L
    private var dnsMs = -1L
    private var connectMs = -1L
    private var tlsMs = -1L

    override fun dnsStart(call: Call, domainName: String) {
        dnsStart = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsMs = PerfLog.ms(dnsStart)
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStart = System.nanoTime()
    }

    override fun secureConnectStart(call: Call) {
        tlsStart = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs = PerfLog.ms(tlsStart)
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        connectMs = PerfLog.ms(connectStart)
    }

    override fun callEnd(call: Call) = report(call, "ok")

    override fun callFailed(call: Call, ioe: java.io.IOException) = report(call, "failed")

    private fun report(call: Call, outcome: String) {
        val host = call.request().url.host.substringBefore('.')
        val fresh = connectMs >= 0
        PerfLog.line(
            "conn host=$host ${if (fresh) "new" else "reused"} dns=$dnsMs connect=$connectMs tls=$tlsMs " +
                "total=${PerfLog.ms(start)} $outcome",
        )
    }

    companion object {
        val factory = Factory { if (PerfLog.on) PerfEventListener() else NONE }
    }
}
