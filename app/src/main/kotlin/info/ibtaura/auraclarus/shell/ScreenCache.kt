package info.ibtaura.auraclarus.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * What the read screens loaded, kept in memory between visits (stale-while-revalidate; not in iOS AppModel). A screen
 * starts from `peek(key)`, so a page it has shown before draws at once, then calls `load(key) { api... }`, which
 * answers from memory while the entry is fresh and asks the backend otherwise. Two screens asking for the same key at
 * once share one request.
 *
 * An entry is fresh for [maxAgeMs] after it arrived, until `markStale` (AppState calls it on every `dataVersion`
 * change) or `clear` (sign-out, an account change, a new day). A stale entry still shows; the next `load` asks again.
 * A load that started before a `markStale` stores its answer as stale, and one that started before a `clear` stores
 * nothing, so a write or a sign-out is never undone by an answer already on its way.
 *
 * Memory only, never on disk: it holds health data. Use it from the main thread (the screens' coroutines are there).
 *
 * How a screen uses it:
 * ```
 * var scores by remember { mutableStateOf(app.cache.peek<ScoresToday>(CacheKeys.SCORES)) }
 * LaunchedEffect(app.dataVersion, retry) {
 *     try { scores = app.cache.load(CacheKeys.SCORES) { app.api.scoresToday() } }
 *     catch (e: CancellationException) { throw e }
 *     catch (e: Throwable) { app.note(e); failed = scores == null }   // cached data stays; no page-wide error
 * }
 * ```
 * After a local change (a rating, an answered card), `put` or `update` the entry so the next visit shows it.
 */
class ScreenCache(
    scope: CoroutineScope,
    /** How long an answer counts as fresh. UI tests pass 0, so every visit still asks the stub as before. */
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    /** Milliseconds on a clock that only goes forward. Tests pass their own. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    // `since` is the epoch the answer was asked for in, so an older answer arriving late never replaces a newer one.
    private class Entry(val value: Any?, val storedAt: Long, var stale: Boolean, val since: Int)
    private class Flight(val epoch: Int, val deferred: Deferred<Any?>)

    /** Loads run under this, so one failing never cancels the app's scope, and leaving a screen doesn't stop them. */
    private val loads = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val entries = HashMap<String, Entry>()
    private val flights = HashMap<String, Flight>()

    /** Goes up on every `markStale` and `clear`: a load started before it stores a stale entry, and isn't joined. */
    private var epoch = 0

    /** Goes up on `clear` only: a load started before it stores nothing. */
    private var generation = 0

    /** True when `key` has an entry, fresh or stale (an entry can hold null, e.g. a day with no plan). */
    fun has(key: String): Boolean = key in entries

    /** The entry's value, fresh or stale, or null when there is none. Never asks the backend. */
    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: String): T? = entries[key]?.value as T?

    /** True when `key` has an entry that `load` would answer from memory. */
    fun isFresh(key: String): Boolean {
        val entry = entries[key] ?: return false
        return !entry.stale && clock() - entry.storedAt < maxAgeMs
    }

    /**
     * The entry when fresh; otherwise `fetch` runs (or a request for the same key already on its way is joined) and
     * its answer is stored. `force` skips the fresh entry but still joins a request on its way (a retry). Throws what
     * `fetch` throws; the entry, if any, is kept.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> load(key: String, force: Boolean = false, fetch: suspend () -> T): T {
        if (!force && isFresh(key)) return entries.getValue(key).value as T
        val flight = flights[key]?.takeIf { it.epoch == epoch } ?: start(key, fetch)
        return flight.deferred.await() as T
    }

    private fun start(key: String, fetch: suspend () -> Any?): Flight {
        val startEpoch = epoch
        val startGeneration = generation
        lateinit var flight: Flight
        val deferred = loads.async(start = CoroutineStart.LAZY) {
            try {
                val value = fetch()
                if (generation == startGeneration && (entries[key]?.since ?: startEpoch) <= startEpoch) {
                    entries[key] = Entry(value, clock(), stale = epoch != startEpoch, since = startEpoch)
                }
                value
            } finally {
                if (flights[key] === flight) flights.remove(key)
            }
        }
        flight = Flight(startEpoch, deferred)
        flights[key] = flight
        deferred.start()
        return flight
    }

    /** Stores `value` as fresh: what a screen knows after a change it made itself. */
    fun <T> put(key: String, value: T) {
        entries[key] = Entry(value, clock(), stale = false, since = epoch)
    }

    /** Changes an existing entry in place, keeping its age and staleness. Nothing happens when there is no entry. */
    @Suppress("UNCHECKED_CAST")
    fun <T> update(key: String, change: (T) -> T) {
        val entry = entries[key] ?: return
        entries[key] = Entry(change(entry.value as T), entry.storedAt, entry.stale, entry.since)
    }

    /** Every entry (or only those whose key starts with `prefix`) still shows, but the next `load` asks again. */
    fun markStale(prefix: String? = null) {
        epoch += 1
        entries.forEach { (key, entry) -> if (prefix == null || key.startsWith(prefix)) entry.stale = true }
    }

    /** Forgets everything and stops the requests on their way: sign-out, an account change, a new day. */
    fun clear() {
        epoch += 1
        generation += 1
        entries.clear()
        val going = flights.values.toList()
        flights.clear()
        going.forEach { it.deferred.cancel() }
    }

    /** Suspends until no request is on its way (failed ones count as done). Home waits on it before prefetching. */
    suspend fun awaitIdle() {
        while (true) {
            val going = flights.values.map { it.deferred }
            if (going.isEmpty()) return
            going.forEach { it.join() }
        }
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS = 60_000L
    }
}

/**
 * The cache keys, one per query. Dates are `DayKey`s (yyyy-MM-dd). Screens in other areas read the same entries with
 * `app.cache.peek(key)` / `app.cached(key)` or fill them with `app.cache.load(key) { ... }`:
 *
 * - [TASKS]: `api.tasks()`, the Tasks page's list. Prefetched after Home settles.
 * - [plan] (today, tomorrow): `api.plan(day)`, nullable (no plan yet), the Tasks page's schedule lines. Prefetched.
 * - [HABITS]: `api.habits(includeArchived = false)`, Goals' habits. Prefetched.
 * - [habitLogs] (today minus 399 days to today): `api.habitLogs(from, to)`, Goals' check-offs. Prefetched when there
 *   are habits.
 * - [events] (yesterday to 13 days ahead): Calendar's events; Home's Upcoming reads today out of it. Use
 *   `app.loadCalendarEvents()`.
 */
object CacheKeys {
    const val SCORES = "scores"
    const val CATALOGUE = "catalogue"
    const val WORK_SCHEDULE = "workSchedule"
    const val REWARDS = "rewards"
    const val TASKS = "tasks"
    const val HABITS = "habits"
    const val SLEEP_SCHEDULE = "sleepSchedule"

    fun alerts(from: String, to: String) = "alerts:$from:$to"

    /** Events from the start of `from` to the end of `to`. */
    fun events(from: String, to: String) = "events:$from:$to"
    fun zones(day: String) = "zones:$day"
    fun availability(from: String, to: String) = "availability:$from:$to"
    fun series(field: String, from: String, to: String) = "series:$field:$from:$to"
    fun dailyMetrics(from: String, to: String) = "dailyMetrics:$from:$to"

    /** `api.sessions(from, to)`: Home's yesterday-and-today list. */
    fun sessions(from: String, to: String) = "sessions:$from:$to"
    fun inbox(date: String, days: Int) = "inbox:$date:$days"
    fun plan(day: String) = "plan:$day"
    fun habitLogs(from: String, to: String) = "habitLogs:$from:$to"

    /** Every session-history key starts with this (`markStale(HISTORY_PREFIX)` after a rating). */
    const val HISTORY_PREFIX = "history"

    /** Session history: one window of the list (as SessionHistoryPager loads it) and this week's header. */
    fun historyWindow(filter: String, from: String, to: String) = "historyWindow:$filter:$from:$to"
    fun historyWeek(from: String, to: String) = "historyWeek:$from:$to"
    fun session(id: String) = "session:$id"
    fun trace(id: String) = "trace:$id"
    fun recoveryOptions(minutes: Int) = "recoveryOptions:$minutes"
}
