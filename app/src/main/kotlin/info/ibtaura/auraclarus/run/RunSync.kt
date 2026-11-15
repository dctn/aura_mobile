package info.ibtaura.auraclarus.run

import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.RecoveryType
import info.ibtaura.aura.core.RemoteSession
import info.ibtaura.aura.core.RunEngine
import info.ibtaura.aura.core.RunKind
import info.ibtaura.aura.core.SessionSpan
import info.ibtaura.aura.core.Shelf
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/** What the phone holds, as far as cross-device sync cares. */
internal data class LocalRunState(
    val engine: RunEngine,
    val sessionId: String?,
    /** The Done sheet is up: the run is over here, whatever the server says. */
    val finished: Boolean,
    /** False for a run that never asks for a backend session (a plan). */
    val createsSession: Boolean,
    /** The session version last matched; 0 before the first sync. */
    val lastVersion: Int,
)

/** One step for the controller; `decide` returns them in the order they apply. */
internal sealed interface SyncAction {
    /** Take on the remote session as this phone's run. */
    data object Adopt : SyncAction

    /** Paused elsewhere: pause here at `at`, when the other device paused, and send nothing. */
    data class Pause(val at: Instant) : SyncAction

    /** Resumed elsewhere: resume here now, and send nothing. */
    data object Resume : SyncAction

    /** Extended or shortened elsewhere: the run ends at `end` if it runs on from now. */
    data class MoveEnd(val end: Instant) : SyncAction

    /** Ended (or replaced) elsewhere: close the run here without sending stop or complete. */
    data object EndedElsewhere : SyncAction

    /** The run now matches the server at this version; always last. */
    data class Seen(val version: Int) : SyncAction
}

/**
 * Cross-device sync, as pure functions: one session runs per account, and every device shows and controls it.
 * `decide` picks what to do from the phone's run and the server's; the rest builds an adopted run.
 */
internal object RunSync {
    /** An end that differs by less than this is the same end: clocks and round trips drift by seconds. */
    const val END_SLACK_SECONDS = 60.0

    /**
     * Holding no run, a remote session is adopted unless this phone just closed it (a slow server still reports it) or
     * its time is already up. Holding one, nothing happens while our own taps may not have reached the server yet
     * (`inFlight`, or no session id), or once it is finished here.
     */
    fun decide(
        local: LocalRunState?,
        remote: RemoteSession?,
        inFlight: Boolean,
        recentlyClosed: Set<String>,
        now: Instant,
    ): List<SyncAction> {
        if (local == null) {
            return if (remote != null && canAdopt(remote, recentlyClosed, now)) listOf(SyncAction.Adopt) else emptyList()
        }
        if (local.finished || !local.createsSession || inFlight) return emptyList()
        val id = local.sessionId ?: return emptyList()
        if (remote == null || remote.id != id) {
            val adopt = remote != null && canAdopt(remote, recentlyClosed, now)
            return if (adopt) listOf(SyncAction.EndedElsewhere, SyncAction.Adopt) else listOf(SyncAction.EndedElsewhere)
        }
        val actions = mutableListOf<SyncAction>()
        var engine = local.engine
        if (remote.isPaused && !engine.isPaused) {
            val at = pausedSince(remote, now).coerceAtLeast(engine.startsAt)
            actions += SyncAction.Pause(at)
            engine = engine.pause(at)
        } else if (!remote.isPaused && engine.isPaused) {
            actions += SyncAction.Resume
            engine = engine.resume(now)
        }
        // The first sync of a run started here only takes the version as a baseline: its own start set the end.
        if (local.lastVersion > 0 && remote.version > local.lastVersion && !engine.isOpenEnded) {
            val theirs = remaining(remote, now)
            val ours = engine.snapshot(now).remaining
            if (theirs != null && ours != null && abs(theirs - ours) > END_SLACK_SECONDS) {
                actions += SyncAction.MoveEnd(now.plusMillis((theirs * 1000).toLong()))
            }
        }
        if (remote.version != local.lastVersion) actions += SyncAction.Seen(remote.version)
        return actions
    }

    private fun canAdopt(remote: RemoteSession, recentlyClosed: Set<String>, now: Instant): Boolean {
        if (remote.id in recentlyClosed) return false
        val left = remaining(remote, now) ?: return true
        return left > 0
    }

    /**
     * Seconds left on the remote session, frozen while paused; null when open-ended. The backend's endsAt leaves out
     * the open pause, while the stub's counts it so far; adding the open span's minutes and taking off the time since
     * it began gives the same answer for both.
     */
    fun remaining(remote: RemoteSession, now: Instant): Double? {
        val ends = remote.endsAt ?: return null
        if (!remote.isPaused) return seconds(now, ends)
        val open = openSpan(remote)
        val since = open?.at ?: now
        return seconds(since, ends) + (open?.minutes ?: 0.0) * 60 - seconds(since, now)
    }

    /** The engine for an adopted run: started when the session did, with its pauses, ending when it ends. */
    fun engineFor(remote: RemoteSession, now: Instant): RunEngine {
        val start = minOf(remote.startedAt, now)
        val open = if (remote.isPaused) openSpan(remote) else null
        val closedSeconds = (remote.pauses + remote.breaks).filter { it !== open }.sumOf { max(0.0, it.minutes) * 60 }
        val pausedAt = if (remote.isPaused) pausedSince(remote, now).coerceIn(start, now) else null
        val left = remaining(remote, now)
            ?: return RunEngine(duration = 0.0, hasCountdown = false, startsAt = start, pausedAt = pausedAt, pausedTotal = closedSeconds)
        val spent = max(0.0, seconds(start, pausedAt ?: now) - closedSeconds)
        // Never 0, which the engine reads as open-ended.
        val duration = max(1.0, spent + max(0.0, left))
        return RunEngine(duration = duration, hasCountdown = false, startsAt = start, pausedAt = pausedAt, pausedTotal = closedSeconds)
    }

    /** The same engine with its length changed so it ends at `end` if it runs on from `now`. */
    fun movedEnd(engine: RunEngine, end: Instant, now: Instant): RunEngine {
        if (engine.isOpenEnded) return engine
        val spent = engine.copy(duration = Double.MAX_VALUE).elapsed(now)
        return engine.copy(duration = max(1.0, spent + max(0.0, seconds(now, end))))
    }

    /**
     * The phone's mode for a remote session. A run started on a phone carries its mode's name as the title, so that
     * comes first; then the session's mode and kind; then any timed mode, so the run still shows.
     */
    fun modeFor(remote: RemoteSession, modes: List<Mode>): Mode {
        val title = remote.title?.trim().orEmpty()
        if (title.isNotEmpty()) {
            modes.firstOrNull { it.run != RunKind.plan && it.kind == remote.kind && it.name.equals(title, ignoreCase = true) }
                ?.let { return it }
        }
        val id = when {
            remote.mode == "flowtime" -> "flow"
            remote.mode == "meeting" || remote.kind == "meeting" -> "meeting"
            remote.kind == "walk" -> "urban-walk"
            remote.kind == "recovery" -> when (remote.recoveryType) {
                RecoveryType.nap -> "nap-20"
                RecoveryType.outside -> "outdoor-break"
                RecoveryType.social -> "social-break"
                else -> "recovery-break"
            }
            remote.endsAt == null && remote.kind == "focus" -> "flow"
            // Focus, brainstorm and anything newer.
            else -> "timer"
        }
        return modes.firstOrNull { it.id == id }
            ?: modes.firstOrNull { it.kind == remote.kind && it.run == RunKind.timer }
            ?: modes.firstOrNull { it.run == RunKind.timer }
            ?: Mode(id = "timer", name = "Timer", word = "Timer", shelf = Shelf.focus, minutes = 25, kind = "focus")
    }

    /** "Started on your Mac", by the device the backend names; null for a run started on this phone. */
    fun startedOnText(device: String?): String? = when (device) {
        null -> null
        "mac" -> "Started on your Mac"
        "windows" -> "Started on your PC"
        "phone" -> "Started on your other phone"
        "web" -> "Started in your browser"
        else -> "Started on your computer"
    }

    /** The open pause or break of a paused session: the one that began last. */
    private fun openSpan(remote: RemoteSession): SessionSpan? =
        if (remote.isPaused) (remote.pauses + remote.breaks).maxByOrNull { it.at } else null

    private fun pausedSince(remote: RemoteSession, now: Instant): Instant = minOf(openSpan(remote)?.at ?: now, now)

    private fun seconds(from: Instant, to: Instant): Double = Duration.between(from, to).toMillis() / 1000.0
}
