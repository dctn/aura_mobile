package info.ibtaura.auraclarus.shell

import android.content.Intent

/**
 * The UI-test switches, the same strings as the iOS launch arguments. Instrumentation passes them as the string
 * extra `aura.flags`, space-separated: `--uitesting --uitesting-short-run`. The app itself launches with none.
 */
data class LaunchFlags(val raw: Set<String> = emptySet()) {
    fun has(flag: String): Boolean = flag in raw

    /** `--uitesting`: stub data, no network, a separate preferences file. */
    val isUiTesting: Boolean get() = has(UITESTING)
    /** `--uitesting-short-run`: every timed run lasts 3 seconds, so a walk can reach the Done sheet. */
    val shortRun: Boolean get() = has(SHORT_RUN)
    /** `--uitesting-no-schedule`: starts with no work hours, as a new account does. */
    val noSchedule: Boolean get() = has(NO_SCHEDULE)
    /** `--uitesting-joined-session`: a session is already running elsewhere, so a run's start joins it. */
    val joinedSession: Boolean get() = has(JOINED_SESSION)

    /** `--uitesting-remote-session`: a session runs on another device at launch, and `activeSession()` reports it. */
    val remoteSession: Boolean get() = has(REMOTE_SESSION)
    /** `--uitesting-needs-check`: Find a time for today asks for the Quick check first, until "Plan anyway". */
    val needsCheck: Boolean get() = has(NEEDS_CHECK)
    /** `--uitesting-short-check`: the Quick check runs for seconds, and any check with a reaction counts. */
    val shortCheck: Boolean get() = has(SHORT_CHECK)
    /** `--uitesting-health-night`: health data is connected and has last night, so launch sends it. */
    val healthNight: Boolean get() = has(HEALTH_NIGHT)
    /** `--uitesting-checkin`: the morning check-in shows on Home. Other walks start with it already sent. */
    val checkin: Boolean get() = has(CHECKIN)
    /** `--uitesting-onboarding`: the sleep-source onboarding gate shows (other walks start past it). */
    val onboarding: Boolean get() = has(ONBOARDING)
    /** `--uitesting-collecting`: today's morning score is an estimate with no nights yet, so Home shows the banner. */
    val collecting: Boolean get() = has(COLLECTING)
    /** `--uitesting-signed-out`: StubAuth starts signed out, so a walk begins on Sign in (other walks start signed in). */
    val signedOut: Boolean get() = has(SIGNED_OUT)
    /** `--sample-data`: set by Settings' "Use sample data" switch (debug builds), never by tests. Made-up data, offline. */
    val isSample: Boolean get() = has(SAMPLE)

    fun with(flag: String): LaunchFlags = LaunchFlags(raw + flag)

    companion object {
        const val EXTRA = "aura.flags"

        const val UITESTING = "--uitesting"
        const val SHORT_RUN = "--uitesting-short-run"
        const val NO_SCHEDULE = "--uitesting-no-schedule"
        const val JOINED_SESSION = "--uitesting-joined-session"
        const val REMOTE_SESSION = "--uitesting-remote-session"
        const val NEEDS_CHECK = "--uitesting-needs-check"
        const val SHORT_CHECK = "--uitesting-short-check"
        const val HEALTH_NIGHT = "--uitesting-health-night"
        const val CHECKIN = "--uitesting-checkin"
        const val ONBOARDING = "--uitesting-onboarding"
        const val COLLECTING = "--uitesting-collecting"
        const val SIGNED_OUT = "--uitesting-signed-out"
        const val SAMPLE = "--sample-data"

        fun parse(text: String?): LaunchFlags =
            LaunchFlags(text.orEmpty().split(' ', '\t', '\n').filter { it.isNotBlank() }.toSet())

        fun from(intent: Intent?): LaunchFlags = parse(intent?.getStringExtra(EXTRA))
    }
}
