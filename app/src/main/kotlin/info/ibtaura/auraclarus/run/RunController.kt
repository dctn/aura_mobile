@file:UseSerializers(InstantSerializer::class)

package info.ibtaura.auraclarus.run

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import info.ibtaura.aura.core.AuraApi
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.InstantSerializer
import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.RecoveryType
import info.ibtaura.aura.core.RunEngine
import info.ibtaura.aura.core.RunKind
import info.ibtaura.aura.core.RunPhase
import info.ibtaura.aura.core.SessionAction
import info.ibtaura.aura.core.SessionTrace
import info.ibtaura.aura.core.StartedSession
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.RunPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

// A port of Run/RunController.swift.

/** The run in progress. Lives in the controller so it survives switching tabs, and in prefs so it survives a restart. */
@Serializable
data class ActiveRun(
    val mode: Mode,
    val minutes: Int,
    val title: String,
    val engine: RunEngine,
    /** Sent as the new session's id. A repeated id returns the stored session, so a start can be tried again. */
    val startId: String = newId(),
    /** The id the backend answered with, which isn't `startId` when the start joined a running session. */
    val sessionId: String? = null,
    /** True once the session has been asked for, after any 3-2-1. */
    val sessionBegun: Boolean = false,
    /** The backend refused the start because today's work limit is used up. Not tried again. */
    val workLimitReached: Boolean = false,
    /** Set when the run is over: time up or Mark as done. The Done sheet shows from here. */
    val finishedAt: Instant? = null,
    /** Wind-down's "For tomorrow" text, one thing a line. */
    val tomorrowList: String = "",
    /** True once the final `complete` reached the backend, or the backend had already ended the session. */
    val completeDelivered: Boolean = false,
    /** Wind-down: the lines still to go out as tasks. Null until the run ends. */
    val tomorrowLeft: List<String>? = null,
    /** The session already running when this run asked for one. Set until the person picks in the alert. */
    val otherSession: OtherSession? = null,
    /** The recovery break's picked type, kept as text so a saved run still decodes if the types change. */
    val recoveryTypeRaw: String? = null,
) {
    /** The type a recovery session sends: the one picked, else the mode's. Null for every other kind. */
    val recoveryType: RecoveryType?
        get() {
            if (mode.kind != "recovery") return null
            recoveryTypeRaw?.let { RecoveryType.fromRaw(it) }?.let { return it }
            return mode.recoveryType
        }

    /** The Done sheet only says "Saved" when this is true. */
    val isSaved: Boolean get() = completeDelivered && (tomorrowLeft ?: emptyList()).isEmpty()

    val tomorrowLines: List<String>
        get() {
            if (mode.run != RunKind.winddown) return emptyList()
            return tomorrowList.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().lowercase(Locale.ROOT)
    }
}

/** What the backend said about the session it joined instead of starting one. */
@Serializable
data class OtherSession(
    val id: String,
    val title: String? = null,
    val kind: String? = null,
    val device: String? = null,
    val endsAt: Instant? = null,
)

/**
 * Drives every run from `RunEngine`, which works from the clock. Nothing counts: one job sleeps until the next thing
 * that has to happen (the 3-2-1 ending, the time running out), and screens draw from `engine.snapshot(at)`.
 * Everything runs on the main thread.
 */
@Stable
class RunController(
    private val app: AppState,
    private val context: Context,
) : RunPort {
    private val api: AuraApi get() = app.api
    /** UI tests: no restore, no saving, and no notifications, whose permission prompt would cover the screen. */
    private val isUiTesting = app.flags.isUiTesting
    /** `--uitesting-short-run`: every timed run lasts 3 seconds, so the walk can reach the Done sheet. */
    private val isShortRun = app.flags.shortRun
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }

    var active by mutableStateOf<ActiveRun?>(null)
        private set

    var lastError by mutableStateOf<Throwable?>(null)
        private set

    override var onError: ((Throwable) -> Unit)? = null

    override val hasActive: Boolean get() = active != null
    override val showsMiniBar: Boolean get() = active?.let { it.finishedAt == null } == true

    private var wake: Job? = null
    private var sessionStart: Deferred<String?>? = null
    /** The last thing sent. Each one waits for the one before, so they reach the backend in order. Outlives `close()`. */
    private var lastAction: Job? = null
    // What's in flight, by the run's `engine.startsAt`, so a retry doesn't send it twice.
    private var startingFor: Instant? = null
    private var completingFor: Instant? = null
    private var sendingTasksFor: Instant? = null

    private val alarms = RunEndAlarm(context)

    private val foreground = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            refresh()
        }
    }

    init {
        // A new AppState (the activity came back after being finished) gets a new controller, which restores the
        // saved run itself; the old one stops, so the run isn't driven twice.
        latest?.shutDown()
        latest = this
        if (!isUiTesting) restore()
        runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(foreground) }
    }

    /** Stops this controller without touching the saved run or its alarm. */
    private fun shutDown() {
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(foreground) }
        scope.cancel()
    }

    private var expanded: Boolean
        get() = app.runExpanded
        set(value) {
            app.runExpanded = value
        }

    // MARK: Starting and stopping

    /** One run at a time: while one is going, this brings it back instead. */
    override fun start(mode: Mode, minutes: Int, title: String?, recoveryType: RecoveryType?) {
        if (mode.run == RunKind.plan) return
        if (active != null) {
            expanded = true
            return
        }
        var engine = RunEngine(minutes, mode.hasCountdown, Instant.now())
        if (isShortRun && !engine.isOpenEnded) engine = engine.copy(duration = 3.0)
        val typed = title?.trim().orEmpty()
        active = ActiveRun(
            mode = mode,
            minutes = minutes,
            title = typed.ifEmpty { mode.name },
            engine = engine,
            recoveryTypeRaw = recoveryType?.raw,
        )
        expanded = true
        save()
        scheduleEndNotification()
        refresh()
    }

    /** Clears the run on the phone with no backend call. */
    override fun close() {
        wake?.cancel()
        wake = null
        sessionStart = null
        cancelEndNotification()
        active = null
        expanded = false
        save()
    }

    /** Sign-out, while the token still works: ends the session on the backend, waits a few seconds at most, then clears. */
    override suspend fun endForSignOut() {
        active?.let { run ->
            if (run.finishedAt != null) retryWhatFailed() else if (run.sessionBegun) send(SessionAction.stop)
        }
        lastAction?.let { job -> withTimeoutOrNull(3_000) { job.join() } }
        close()
    }

    /** The alert's "Stop it and start mine": ends the other session, then asks again with a new id. */
    fun stopOtherAndStart() {
        val run = active ?: return
        val other = run.otherSession ?: return
        active = run.copy(otherSession = null, startId = ActiveRun.newId())
        save()
        val previous = lastAction
        lastAction = scope.launch {
            previous?.join()
            try {
                api.sessionAction(other.id, SessionAction.stop)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // It already ended another way: the start below goes through all the same.
                if ((e as? AuraError)?.serverCode != "action_not_allowed") report(e)
            }
        }
        retryWhatFailed()
    }

    /** The alert's "Keep it": the other session carries on untouched, and this run ends on the phone. */
    fun keepOtherSession() {
        if (active?.otherSession == null) return
        close()
    }

    /** Cancel during the 3-2-1 starts nothing and calls nothing. */
    fun cancelCountdown() {
        if (active?.sessionBegun == true) send(SessionAction.stop)
        close()
    }

    fun togglePause() {
        val run = active ?: return
        if (run.finishedAt != null) return
        val now = Instant.now()
        if (run.engine.isPaused) {
            active = run.copy(engine = run.engine.resume(now))
            send(SessionAction.resume)
            scheduleEndNotification()
        } else {
            val paused = run.engine.pause(now)
            if (!paused.isPaused) return
            active = run.copy(engine = paused)
            send(SessionAction.pause)
            cancelEndNotification()
        }
        save()
        refresh()
    }

    /** Frame 13's Mark as done: saved as completed, then the Done sheet. */
    fun markDone() = finish(canBeep = false)

    /** Frame 13's End session: saved as stopped, with no Done sheet. */
    fun endSession() {
        val run = active ?: return
        send(SessionAction.stop)
        active = run.copy(tomorrowLeft = run.tomorrowLines)
        sendTomorrowTasks()
        close()
    }

    fun setTomorrowList(text: String) {
        val run = active ?: return
        active = run.copy(tomorrowList = text)
        save()
    }

    /** The stress change for the Done sheet, once `complete` has gone out. Null with no session or on an error. */
    suspend fun loadTrace(): SessionTrace? {
        lastAction?.join()
        val id = active?.sessionId ?: sessionStart?.await() ?: return null
        return try {
            api.sessionTrace(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            report(e)
            null
        }
    }

    // MARK: The clock

    /** Does whatever is due now, then sleeps until the next thing is. Also runs when the app comes back to the front. */
    fun refresh() {
        update(canBeep = true)
        retryWhatFailed()
    }

    private fun update(canBeep: Boolean) {
        val run = active ?: return
        if (run.finishedAt != null) return
        val now = Instant.now()
        // Before the time-up check: the whole run may have passed while away, and `complete` must follow the start.
        if (!now.isBefore(run.engine.startsAt) && !run.sessionBegun) beginSession()
        if (run.engine.snapshot(now).phase == RunPhase.TimeUp) {
            finish(canBeep)
            return
        }
        scheduleWake(now)
    }

    private fun scheduleWake(now: Instant) {
        wake?.cancel()
        wake = null
        val run = active ?: return
        if (run.finishedAt != null) return
        val due = if (now.isBefore(run.engine.startsAt)) run.engine.startsAt else run.engine.endDate() ?: return
        val delayMs = maxOf(0L, Duration.between(now, due).toMillis()) + 20
        wake = scope.launch {
            delay(delayMs)
            refresh()
        }
    }

    private fun finish(canBeep: Boolean) {
        val run = active ?: return
        if (run.finishedAt != null) return
        active = run.copy(finishedAt = Instant.now(), tomorrowLeft = run.tomorrowLines)
        wake?.cancel()
        wake = null
        cancelEndNotification()
        // A start that failed earlier goes first, or this `complete` would find no session and be skipped.
        retryStart()
        send(SessionAction.complete, isFinal = true)
        sendTomorrowTasks()
        save()
        expanded = true
        if (canBeep && isInForeground()) beep()
    }

    private fun isInForeground(): Boolean = runCatching {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }.getOrDefault(false)

    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            scope.launch {
                delay(600)
                tone.release()
            }
        }
    }

    // MARK: Backend

    /** Never blocks the run: when this fails, the run goes on and later session actions are skipped. */
    private fun beginSession() {
        val run = active ?: return
        if (run.sessionBegun) return
        val begun = run.copy(sessionBegun = true)
        active = begun
        save()
        requestSession(begun)
    }

    /** Waits for whatever is still going out, so it can't be overtaken, then asks for the session with the run's own id. */
    private fun requestSession(run: ActiveRun) {
        val id = run.startId
        val mode = run.mode
        val title = run.title
        val recoveryType = run.recoveryType
        val startsAt = run.engine.startsAt
        val planned: Int? = if (run.engine.isOpenEnded) null else run.minutes
        val previous = lastAction
        startingFor = startsAt
        sessionStart = scope.async {
            previous?.join()
            try {
                val session = api.startSession(id, mode.kind, mode.sessionMode, planned, title, recoveryType)
                sessionStarted(session, startsAt)
                // A joined session belongs to another run, so this run's actions mustn't reach it.
                if (session.joined) null else session.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                startFailed(e, startsAt)
                null
            }
        }
    }

    private fun sessionStarted(session: StartedSession, startsAt: Instant) {
        if (startingFor == startsAt) startingFor = null
        val run = active ?: return
        if (run.engine.startsAt != startsAt) return
        if (session.joined) {
            active = run.copy(
                otherSession = OtherSession(session.id, session.title, session.kind, session.device, session.endsAt),
            )
            save()
            return
        }
        var updated = run.copy(sessionId = session.id)
        val shortened = grantedLength(session, updated)
        if (shortened != null) updated = shortened
        active = updated
        save()
        if (shortened != null) {
            scheduleEndNotification()
            refresh()
        }
    }

    /** The backend can grant less than was asked, so the phone mustn't count past that. Null when nothing changes. */
    private fun grantedLength(session: StartedSession, run: ActiveRun): ActiveRun? {
        if (session.joined || isShortRun || run.engine.isOpenEnded || run.finishedAt != null) return null
        val granted = session.plannedMinutes ?: return null
        if (granted <= 0) return null
        val seconds = granted * 60.0
        if (seconds >= run.engine.duration) return null
        return run.copy(engine = run.engine.copy(duration = seconds), minutes = granted)
    }

    private fun startFailed(error: Throwable, startsAt: Instant) {
        if (startingFor == startsAt) startingFor = null
        val run = active
        if (run != null && run.engine.startsAt == startsAt && (error as? AuraError)?.serverCode == "work_limit_reached") {
            active = run.copy(workLimitReached = true)
            save()
        }
        report(error)
    }

    private fun send(action: SessionAction, isFinal: Boolean = false) {
        val savedId = active?.sessionId
        val started = sessionStart
        val previous = lastAction
        val startsAt = active?.engine?.startsAt
        if (isFinal) completingFor = startsAt
        lastAction = scope.launch {
            previous?.join()
            val id = savedId ?: started?.await()
            if (id == null) {
                if (isFinal) completeEnded(startsAt, delivered = false)
                return@launch
            }
            try {
                api.sessionAction(id, action)
                if (isFinal) completeEnded(startsAt, delivered = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The session has already ended another way, usually the sweeper's, and its minutes are recorded.
                val alreadyEnded = isFinal && (e as? AuraError)?.serverCode == "action_not_allowed"
                if (isFinal) completeEnded(startsAt, delivered = alreadyEnded)
                if (!alreadyEnded) report(e)
            }
        }
    }

    private fun completeEnded(startsAt: Instant?, delivered: Boolean) {
        val run = active ?: return
        if (run.engine.startsAt != startsAt) return
        completingFor = null
        if (!delivered) return
        active = run.copy(completeDelivered = true)
        save()
    }

    /** Wind-down: each line left becomes a task for the day after the run ended. A sent line is struck off and saved. */
    private fun sendTomorrowTasks() {
        val run = active ?: return
        val lines = run.tomorrowLeft ?: return
        if (lines.isEmpty() || sendingTasksFor == run.engine.startsAt) return
        val ended = run.finishedAt ?: Instant.now()
        val plannedFor = DayKey.string(ended.atZone(DayKey.zone).toLocalDate().plusDays(1))
        val startsAt = run.engine.startsAt
        val previous = lastAction
        sendingTasksFor = startsAt
        lastAction = scope.launch {
            previous?.join()
            for (line in lines) {
                try {
                    api.addTask(line, plannedFor)
                    tomorrowLineSent(line, startsAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    report(e)
                }
            }
            if (sendingTasksFor == startsAt) sendingTasksFor = null
        }
    }

    private fun tomorrowLineSent(line: String, startsAt: Instant) {
        val run = active ?: return
        if (run.engine.startsAt != startsAt) return
        val left = run.tomorrowLeft ?: return
        val index = left.indexOf(line)
        if (index < 0) return
        active = run.copy(tomorrowLeft = left.toMutableList().also { it.removeAt(index) })
        save()
    }

    /** On restore and on coming back to the front: tries again whatever the backend hasn't got and nothing is sending. */
    private fun retryWhatFailed() {
        val run = active ?: return
        retryStart()
        if (run.finishedAt != null && !run.completeDelivered && !run.workLimitReached && completingFor != run.engine.startsAt) {
            send(SessionAction.complete, isFinal = true)
        }
        sendTomorrowTasks()
    }

    private fun retryStart() {
        val run = active ?: return
        if (!run.sessionBegun || run.sessionId != null || run.otherSession != null || run.workLimitReached ||
            startingFor == run.engine.startsAt
        ) return
        requestSession(run)
        // The pause that went out with no session was skipped.
        if (run.engine.isPaused) send(SessionAction.pause)
    }

    private fun report(error: Throwable) {
        lastError = error
        onError?.invoke(error)
    }

    // MARK: Local notification

    private fun scheduleEndNotification() {
        if (isUiTesting) return
        cancelEndNotification()
        val run = active ?: return
        if (run.finishedAt != null) return
        val end = run.engine.endDate() ?: return
        val at = maxOf(end, Instant.now().plus(1, ChronoUnit.SECONDS))
        alarms.schedule(at, "${run.title} done", "Your time is up.")
    }

    private fun cancelEndNotification() {
        if (isUiTesting) return
        alarms.cancel()
    }

    // MARK: Saving

    private fun save() {
        if (isUiTesting) return
        val run = active
        prefs.edit {
            if (run == null) remove(STORE_KEY) else putString(STORE_KEY, json.encodeToString(ActiveRun.serializer(), run))
        }
    }

    private fun restore() {
        val text = prefs.getString(STORE_KEY, null) ?: return
        val saved = runCatching { json.decodeFromString(ActiveRun.serializer(), text) }.getOrNull()
        if (saved == null) {
            prefs.edit { remove(STORE_KEY) }
            return
        }
        active = saved
        expanded = true
        update(canBeep = false)
        retryWhatFailed()
        scope.launch { scheduleEndNotification() }
    }

    companion object {
        private const val PREFS = "aura_run"
        private const val STORE_KEY = "activeRun"

        /** The controller driving the run now; at most one per process. */
        private var latest: RunController? = null
    }
}
