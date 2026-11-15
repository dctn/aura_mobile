package info.ibtaura.auraclarus.shell

import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.RecoveryType
import info.ibtaura.aura.core.RemoteSession
import info.ibtaura.aura.core.HealthStoreStatus
import info.ibtaura.aura.core.SleepHistory
import info.ibtaura.aura.core.SleepNight
import java.time.Instant

// What AppState holds for parts other agents build. Each is a slot on AppState (`app.run`, `app.sleep`,
// `app.health`), filled at launch by the factory in that area's *Wiring.kt file. Implementations keep their
// observable fields in Compose state (`mutableStateOf`) so screens recompose when they change.

/**
 * The run controller (iOS `RunController`): one run at a time, kept across tabs. Implemented in `run/`. Screens outside
 * `run/` start runs with `app.startRun(...)` and read only this interface; `run/` may cast to its own class.
 */
interface RunPort {
    /** True while a run is held: counting down, running, paused, or finished and showing Done. */
    val hasActive: Boolean

    /** True while a held run isn't finished. RootScreen shows the mini bar when this is true and `app.runExpanded` false. */
    val showsMiniBar: Boolean

    /** Called with every error the controller catches; AppState routes it to `note`. */
    var onError: ((Throwable) -> Unit)?

    /** Starts a run (and its backend session). `title` null or blank uses the mode's name. */
    fun start(mode: Mode, minutes: Int, title: String? = null, recoveryType: RecoveryType? = null)

    /** Forgets the run with no backend call (after Done, and on sign-out). */
    fun close()

    /** Ends a running session on the backend before sign-out, while the token still works. */
    suspend fun endForSignOut()

    /**
     * Cross-device sync with the account's running session (`api.activeSession()`; null when none runs anywhere).
     * Holding no run, this picks up a session started on another device. Holding one, it matches it: paused, resumed
     * or extended elsewhere, or ended elsewhere (closed here, with nothing sent). AppState's sync loop calls it.
     */
    fun syncWith(remote: RemoteSession?) {}
}

/**
 * Last night's sleep from the phone's health store (iOS `SleepSource`, HealthKit there; Health Connect here).
 * Implemented in `health/`. UI tests use a stub that has a night when `--uitesting-health-night` is set.
 */
interface SleepPort {
    /** Asks for permission to read sleep. Throws when the health store can't ask (not installed, refused). */
    suspend fun requestAccess()

    /**
     * The night that ended this morning, built from the samples between `from` and `to` (with core's sleep-night
     * builder), or null when there isn't one.
     */
    suspend fun lastNight(from: Instant, to: Instant): SleepNight?

    /** Onboarding: whether Health Connect is on this phone, installable, or not possible. */
    fun storeStatus(): HealthStoreStatus

    /** True when Aura may read sleep. A permission check only, never a data read, so it is safe on every resume. */
    suspend fun sleepGranted(): Boolean

    /** Onboarding only, after a tap with the app in front: the nights of the last `nights` days, oldest first. */
    suspend fun history(nights: Int): SleepHistory

    /** The installed apps among `HealthSources.known` (package names). */
    fun installedApps(): Set<String>
}

/** Health Connect day data (steps, heart rate, workouts) sent to `putHealthDays`. Implemented in `health/`. */
interface HealthPort {
    /** True when Health Connect is installed and the read permissions are granted. */
    suspend fun isConnected(): Boolean

    /** Sends the days not yet sent. Throws AuraError on a backend failure; AppState notes it. */
    suspend fun syncRecentDays()

    /** Sign-out: forgets which app each metric comes from (and the user's own picks), so the next account starts fresh. */
    fun forgetSources() {}
}
