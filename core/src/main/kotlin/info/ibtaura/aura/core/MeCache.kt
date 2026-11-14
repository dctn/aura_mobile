package info.ibtaura.aura.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * GET /v1/me, read once and shared. Calendar, Logging, Goals, Settings, Calorie and Find a time all read the profile,
 * often several times a screen. Callers that ask at the same time share one request, and its answer is kept for
 * [keepMillis]. Each caller decodes the text into the shape it needs.
 *
 * Every write to the profile goes through [write], which keeps the answer (PUT /v1/me/profile and /v1/me/settings both
 * answer with the whole profile, like the GET) or, when the write fails, forgets what was kept. A 412 on a write means
 * the kept copy was stale: the caller asks again with `fresh = true`. Sign-out calls [clear].
 *
 * With [keepMillis] 0 nothing is kept or shared and every read is its own request (the tests' default).
 */
internal class MeCache(
    private val keepMillis: Long,
    private val ticker: () -> Long = { System.nanoTime() },
    private val fetch: suspend () -> String,
) {
    companion object {
        /** How long a profile read is kept in the app. */
        const val KEEP_MILLIS = 60_000L
    }

    private val lock = Any()

    /** Shared reads run here, so one caller leaving doesn't cancel the read the others wait on. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var text: String? = null
    private var storedAt = 0L

    /** Bumped by every new read, write and clear, so an older read finishing late can't overwrite newer text. */
    private var generation = 0L
    private var running: Deferred<String>? = null

    private val keeps: Boolean get() = keepMillis > 0

    /** The profile's JSON text: the kept copy when it's young enough, else one shared read. `fresh` skips the copy. */
    suspend fun text(fresh: Boolean = false): String {
        if (!keeps) return fetch()
        if (fresh) clear()
        val read = synchronized(lock) {
            held()?.let { return it }
            running ?: start().also { running = it }
        }
        return read.await()
    }

    /** Runs a write to the profile; keeps its answer, or forgets the kept copy when it fails. */
    suspend fun write(send: suspend () -> String): String {
        val answer = try {
            send()
        } catch (error: Throwable) {
            clear()
            throw error
        }
        store(answer)
        return answer
    }

    /** Keeps `answer` (the whole profile) as the newest copy. */
    fun store(answer: String) {
        if (!keeps) return
        synchronized(lock) {
            generation += 1
            running = null
            text = answer
            storedAt = ticker()
        }
    }

    /** Forgets the kept copy and any read in flight, so the next caller reads again. */
    fun clear() {
        synchronized(lock) {
            generation += 1
            running = null
            text = null
        }
    }

    /** Call with the lock held. */
    private fun held(): String? {
        val kept = text ?: return null
        val ageMillis = (ticker() - storedAt) / 1_000_000
        return if (ageMillis in 0 until keepMillis) kept else null
    }

    /** Call with the lock held: the caller sets `running` before letting go, before the read can finish. */
    private fun start(): Deferred<String> {
        generation += 1
        val mine = generation
        return scope.async {
            try {
                val answer = fetch()
                synchronized(lock) {
                    if (generation == mine) {
                        text = answer
                        storedAt = ticker()
                        running = null
                    }
                }
                answer
            } catch (error: Throwable) {
                // A failed read isn't kept: the next caller tries again.
                synchronized(lock) { if (generation == mine) running = null }
                throw error
            }
        }
    }
}
