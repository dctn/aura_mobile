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
    /** `--uitesting-needs-check`: Find a time for today asks for the Quick check first, until "Plan anyway". */
    val needsCheck: Boolean get() = has(NEEDS_CHECK)
    /** `--uitesting-short-check`: the Quick check runs for seconds, and any check with a reaction counts. */
    val shortCheck: Boolean get() = has(SHORT_CHECK)
    /** `--uitesting-health-night`: health data is connected and has last night, so launch sends it. */
    val healthNight: Boolean get() = has(HEALTH_NIGHT)
    /** `--uitesting-checkin`: the morning check-in shows on Home. Other walks start with it already sent. */
    val checkin: Boolean get() = has(CHECKIN)

    companion object {
        const val EXTRA = "aura.flags"

        const val UITESTING = "--uitesting"
        const val SHORT_RUN = "--uitesting-short-run"
        const val NO_SCHEDULE = "--uitesting-no-schedule"
        const val JOINED_SESSION = "--uitesting-joined-session"
        const val NEEDS_CHECK = "--uitesting-needs-check"
        const val SHORT_CHECK = "--uitesting-short-check"
        const val HEALTH_NIGHT = "--uitesting-health-night"
        const val CHECKIN = "--uitesting-checkin"

        fun parse(text: String?): LaunchFlags =
            LaunchFlags(text.orEmpty().split(' ', '\t', '\n').filter { it.isNotBlank() }.toSet())

        fun from(intent: Intent?): LaunchFlags = parse(intent?.getStringExtra(EXTRA))
    }
}
