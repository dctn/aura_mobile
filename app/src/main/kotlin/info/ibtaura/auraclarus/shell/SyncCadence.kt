package info.ibtaura.auraclarus.shell

import info.ibtaura.aura.core.Checkin
import info.ibtaura.aura.core.CheckinKind
import info.ibtaura.aura.core.RemoteSession
import kotlinx.coroutines.CancellationException

/**
 * The cross-device sync loop's rules, kept pure so they can be tested on the JVM (AppState's "Cross-device sync" block
 * runs them). One account, many devices: the backend allows one running session per account and has no push channel,
 * so each device polls while it is in front.
 */
object SyncCadence {
    /** Between session checks while a run shows here: a pause or end on another device shows within this. */
    const val RUN_INTERVAL_MS = 15_000L

    /** Between session checks with no run: a session started elsewhere shows within this. */
    const val IDLE_INTERVAL_MS = 60_000L

    /** Open screens load again after this long in front with no other reload. */
    const val DATA_REFRESH_MS = 5 * 60_000L

    /** Coming back after more than this in the background loads every screen again. */
    const val AWAY_REFRESH_MS = 30_000L

    /** How long to wait before the next session check. */
    fun interval(hasRun: Boolean): Long = if (hasRun) RUN_INTERVAL_MS else IDLE_INTERVAL_MS

    /** True when the app was away (in the background) long enough that what the screens show may be out of date. */
    fun refreshOnReturn(awayMs: Long): Boolean = awayMs > AWAY_REFRESH_MS

    /** True when the screens last loaded `sinceMs` ago, long enough for a quiet reload. */
    fun refreshDue(sinceMs: Long): Boolean = sinceMs >= DATA_REFRESH_MS

    /** True when `checkins` hold a morning check-in filed for `today` (a DayKey): done here or on another device. */
    fun morningCheckinDone(checkins: List<Checkin>, today: String): Boolean =
        checkins.any { it.kind == CheckinKind.morning && it.date == today }

    /**
     * Reads the account's running session and hands it to `apply`, only after a successful read: a failed read must
     * never look like "no session" (that would close a run here as ended elsewhere). `stamp` is the local run's state
     * (whether one shows); when it changed while the read was on its way (a Start or End tapped here), the answer is
     * already out of date and is dropped. Returns the read's error, or null.
     */
    suspend fun pullSession(
        read: suspend () -> RemoteSession?,
        stamp: () -> Any?,
        apply: (RemoteSession?) -> Unit,
    ): Throwable? {
        val before = stamp()
        val remote = try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return e
        }
        if (stamp() == before) apply(remote)
        return null
    }
}
