package info.ibtaura.auraclarus.health

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.AuthState
import info.ibtaura.aura.core.DailyHealthInput
import info.ibtaura.aura.core.DailyTotalsBuilder
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.HealthDay
import info.ibtaura.aura.core.HealthMetric
import info.ibtaura.aura.core.HealthMetrics
import info.ibtaura.aura.core.HealthSourceGroup
import info.ibtaura.aura.core.HealthSourcePicker
import info.ibtaura.aura.core.HealthSources
import info.ibtaura.aura.core.HealthStoreStatus
import info.ibtaura.aura.core.SleepHistory
import info.ibtaura.aura.core.SleepHistoryBuilder
import info.ibtaura.aura.core.SleepNight
import info.ibtaura.aura.core.SleepNightBuilder
import info.ibtaura.aura.core.SleepSample
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.HealthPort
import info.ibtaura.auraclarus.shell.SleepPort
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A sleep port that can also say whether Health Connect can be offered (iOS `SleepSource.isAvailable`). */
internal interface HealthSleepSource : SleepPort {
    fun availability(): HealthAvailability
}

/** Thrown by `requestAccess` when sleep wasn't granted. */
internal class HealthAccessRefused : Exception("Health Connect sleep access wasn't granted")

/** Last night from Health Connect's sleep sessions. Asks for every read Aura uses in one go, and never writes. */
internal class HealthConnectSleepPort(
    private val reader: HealthConnectReader,
    private val asker: HealthPermissionAsker,
    private val debuggable: Boolean = false,
    /** Which app the night comes from when several wrote it; null keeps every app's samples (merged by time). */
    private val sources: HealthSourceStore? = null,
    /** The installed apps among `HealthSources.known`. */
    private val installed: () -> Set<String> = { emptySet() },
) : HealthSleepSource {

    override fun storeStatus(): HealthStoreStatus = availability().storeStatus

    override suspend fun sleepGranted(): Boolean =
        reader.isAvailable && HealthConnectReader.SLEEP in reader.grantedPermissions()

    override fun installedApps(): Set<String> = installed()

    /**
     * One read of every sleep session from `nights` days ago to now, for onboarding (a tap, app in front). Each night
     * keeps one app, as last night does. Counts and package names only in the log.
     */
    override suspend fun history(nights: Int): SleepHistory {
        if (!sleepGranted()) return SleepHistory(emptyList(), null)
        val now = Instant.now()
        val from = LocalDate.now(DayKey.zone).minusDays(nights.toLong()).atTime(18, 0).atZone(DayKey.zone).toInstant()
        val all = reader.sleepSamples(from, now)
        val history = SleepHistoryBuilder.build(
            all, DayKey.zone, sources?.choices()?.get(HealthMetric.sleep), sources?.overrides()?.get(HealthSourceGroup.sleep),
        )
        if (debuggable) Log.i("AuraHealth", "history samples=${all.size} nights=${history.nights.size} from=${history.source}")
        return history
    }

    override fun availability(): HealthAvailability = when (reader.sdkStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NeedsInstall
        else -> HealthAvailability.Unavailable
    }

    /** No read before this; a night that isn't there yet is looked for again in 15 minutes, not on every resume. */
    private var nextTry: Instant = Instant.EPOCH

    override suspend fun requestAccess() {
        nextTry = Instant.EPOCH
        if (!reader.isAvailable) throw IllegalStateException("Health Connect isn't available")
        val requested = reader.requestedPermissions()
        if (!reader.grantedPermissions().containsAll(requested)) asker.request(requested)
        if (HealthConnectReader.SLEEP !in reader.grantedPermissions()) throw HealthAccessRefused()
    }

    override suspend fun lastNight(from: Instant, to: Instant): SleepNight? {
        val now = Instant.now()
        if (now.isBefore(nextTry)) return null
        if (!reader.isAvailable) return null
        val previous = nextTry
        nextTry = now.plusSeconds(15 * 60)
        try {
            if (HealthConnectReader.SLEEP !in reader.grantedPermissions()) {
                if (debuggable) Log.i("AuraHealth", "sleep: permission not granted")
                return null
            }
            val all = reader.sleepSamples(from, to)
            // One app's night only, the same app the day sync keeps for sleep.
            val samples = sources?.let {
                HealthSourcePicker.nightSamples(all, it.choices()[HealthMetric.sleep], it.overrides()[HealthSourceGroup.sleep])
            } ?: all
            val night = SleepNightBuilder.build(samples, DayKey.zone)
            if (debuggable) {
                val apps = samples.map { it.source }.distinct()
                Log.i("AuraHealth", "sleep: samples=${samples.size}/${all.size} from=$apps night=${night != null}")
            }
            return night
        } catch (e: CancellationException) {
            // A read cut short (e.g. the launch effect restarting as sign-in settles) didn't look, so it mustn't
            // start the 15-minute wait: the next call reads straight away.
            nextTry = previous
            throw e
        } catch (e: Throwable) {
            if (isRateLimit(e)) nextTry = now.plusSeconds(60 * 60)
            if (debuggable) Log.i("AuraHealth", "sleep read failed: ${e.javaClass.simpleName}: ${e.message?.take(160)}${if (isRateLimit(e)) " (rate limited)" else ""}")
            throw e
        }
    }
}

/**
 * Health Connect days for PUT /v1/health-connect/days: yesterday and today on every sync (at most every 15 minutes),
 * and the last 30 days the first time an account syncs. A backend without the route yet (`not_available`) is skipped
 * quietly and tried again next time.
 */
internal class HealthConnectDayPort(
    context: Context,
    private val app: AppState,
    private val reader: HealthConnectReader,
) : HealthPort {
    private val prefs = context.getSharedPreferences("aura_health", Context.MODE_PRIVATE)
    /** Which app each metric comes from (HealthSourcePicker's choices and the user's own from Settings). */
    val sources = HealthSourceStore(prefs)
    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Debug builds only, and never a health value: counts, source apps and why a sync stopped. */
    private fun debugLog(message: String) {
        if (debuggable) Log.i(TAG, message)
    }
    private var syncing = false

    override suspend fun isConnected(): Boolean {
        // No debug read here: it ran on every launch and, with many reinstalls, used up Health Connect's request quota
        // so the real sync was always rate-limited. The sync logs its own counts (days read / with data / sent).
        if (!app.healthConnected || !reader.isAvailable) return false
        return try {
            reader.grantedPermissions().any { it in HealthConnectReader.ALL_DAY_PERMISSIONS }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isRateLimit(e)) retryAfter(RATE_LIMIT_WAIT, WAIT_RATE_LIMIT)
            false
        }
    }

    /**
     * Yesterday and today every time, plus the next 7 older days until 30 are covered, so the first connect never
     * reads a month in one go. A failure waits before the next try (an hour when Health Connect rate-limits us, 6 hours
     * when the server has no Health Connect route yet), and the wait is kept across launches. Health Connect has a
     * request quota per app; trying again on every resume used it up.
     */
    override suspend fun syncRecentDays() {
        if (syncing) return
        val now = Instant.now()
        val account = accountKey() ?: return
        val retryAt = prefs.getLong("$account-retryAt", 0L)
        if (now.toEpochMilli() < retryAt) {
            debugLog("waiting ${(retryAt - now.toEpochMilli()) / 60_000} min before the next Health Connect read")
            return
        }
        syncing = true
        try {
            // An older build kept a plain flag for a finished 30-day backfill.
            val covered = if (prefs.getBoolean(account, false)) BACKFILL_DAYS.toInt() else prefs.getInt("$account-covered", 0)
            val chunkEnd = minOf(covered + CHUNK_DAYS, BACKFILL_DAYS.toInt())
            val zone = DayKey.zone
            val today = LocalDate.now(zone)
            val back = (setOf(0L, 1L) + (covered + 1..chunkEnd).map { it.toLong() }).sortedDescending()
            val days = back.map { today.minusDays(it) }
            val inputs = try {
                reader.days(days, zone, reader.grantedPermissions(), now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val limited = isRateLimit(e)
                debugLog("day read failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}${if (limited) " (rate limited)" else ""}")
                retryAfter(if (limited) RATE_LIMIT_WAIT else FAILURE_WAIT, if (limited) WAIT_RATE_LIMIT else WAIT_FAILURE)
                return
            }
            // One app per metric: the same HRV or night from two apps would be averaged or sent twice.
            val picked = HealthSourcePicker.pickDays(inputs, sources.choices(), sources.overrides())
            val toSend = picked.inputs.map { DailyTotalsBuilder.build(it) }.filter { it.hasData() }
            debugLog("days read=${inputs.size} withData=${toSend.size} covered=$covered->$chunkEnd")
            debugLog("sources " + picked.choices.entries.joinToString { "${it.key.raw}=${it.value.source}" } +
                " seen=" + picked.seen.entries.joinToString { "${it.key.raw}:${it.value.size}" } +
                " totalsFrom=" + inputs.flatMap { it.sources }.distinct())
            if (toSend.isNotEmpty()) {
                try {
                    app.api.putHealthDays(toSend)
                } catch (e: AuraError.Server) {
                    if (e.code == "not_available") {
                        debugLog("server has no Health Connect day route yet (not_available); next try in 6 h")
                        retryAfter(NO_ROUTE_WAIT, WAIT_NO_ROUTE)
                        return
                    }
                    retryAfter(FAILURE_WAIT, WAIT_FAILURE)
                    throw e
                }
            }
            debugLog("days sent=${toSend.size}")
            // Names only, never a value: which kinds of data each sent day carried.
            toSend.forEach { d -> debugLog("sent ${d.date} metrics=${metricNames(d.metrics)} workouts=${d.workouts.size}") }
            sources.save(picked)
            // The reader lists the totals' (and mindful) apps per day before picking; Settings shows them.
            sources.saveTotals(inputs.flatMap { i -> i.sources.filter { it.isNotBlank() }.map { it to i.day } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, days) -> days.max() })
            prefs.edit()
                .putInt("$account-covered", maxOf(chunkEnd, 1))
                .putLong("$account-retryAt", now.toEpochMilli() + FAILURE_WAIT * 1000)
                .putString("$account-waitReason", WAIT_SYNCED)
                .putLong("$account-lastSync", now.toEpochMilli())
                .putInt("$account-lastSent", toSend.size)
                .apply()
        } finally {
            syncing = false
        }
    }

    private fun retryAfter(seconds: Long, reason: String) {
        val account = accountKey() ?: return
        prefs.edit()
            .putLong("$account-retryAt", System.currentTimeMillis() + seconds * 1000)
            .putString("$account-waitReason", reason)
            .apply()
    }

    /** What Settings shows: when days last went to the server, and why and until when the next read waits. */
    fun status(): HealthSyncStatus {
        val account = accountKey() ?: return HealthSyncStatus()
        val covered = if (prefs.getBoolean(account, false)) BACKFILL_DAYS.toInt() else prefs.getInt("$account-covered", 0)
        return HealthSyncStatus(
            lastSync = prefs.getLong("$account-lastSync", 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
            lastSent = prefs.getInt("$account-lastSent", 0),
            nextTry = prefs.getLong("$account-retryAt", 0L).takeIf { it > System.currentTimeMillis() }?.let { Instant.ofEpochMilli(it) },
            waitReason = prefs.getString("$account-waitReason", null),
            coveredDays = covered,
        )
    }

    /**
     * Sync now from Settings: drops a wait after a sync or an ordinary failure. A Health Connect rate limit is kept,
     * since reading again early only extends it.
     */
    suspend fun syncNow(): Boolean {
        val account = accountKey() ?: return false
        if (prefs.getString("$account-waitReason", null) == WAIT_RATE_LIMIT && status().nextTry != null) return false
        prefs.edit().remove("$account-retryAt").apply()
        syncRecentDays()
        return true
    }

    /** One key per signed-in account, so the next account gets its own 30 days. Only a hash of the email is kept. */
    private fun accountKey(): String? {
        val signedIn = app.authState as? AuthState.SignedIn ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest((signedIn.email ?: "account").lowercase().toByteArray())
        return "backfilled-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val BACKFILL_DAYS = 30L
        private const val CHUNK_DAYS = 7
        private const val FAILURE_WAIT = 15 * 60L
        private const val RATE_LIMIT_WAIT = 60 * 60L
        private const val NO_ROUTE_WAIT = 6 * 60 * 60L
        private const val TAG = "AuraHealth"
        const val WAIT_SYNCED = "synced"
        const val WAIT_FAILURE = "failure"
        const val WAIT_RATE_LIMIT = "rateLimit"
        const val WAIT_NO_ROUTE = "noRoute"
    }
}

/** Health Connect day sync state for Settings. Times are null when there's nothing to show. */
internal data class HealthSyncStatus(
    val lastSync: Instant? = null,
    val lastSent: Int = 0,
    val nextTry: Instant? = null,
    val waitReason: String? = null,
    val coveredDays: Int = 0,
)

/** Health Connect's per-app request quota ran out ("Rate limited request quota has been exceeded"). */
internal fun isRateLimit(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.take(4).any { it.message?.contains("rate limit", ignoreCase = true) == true }

/** The metric names that have a value (debug logs; the data class's own toString, names kept, values dropped). */
internal fun metricNames(m: HealthMetrics): List<String> =
    m.toString().substringAfter('(').substringBeforeLast(')').split(", ")
        .filterNot { it.endsWith("=null") }.map { it.substringBefore('=') }.filter { it.isNotBlank() }

/** True when the day has any metric or workout; empty days aren't sent. */
internal fun HealthDay.hasData(): Boolean = workouts.isNotEmpty() || metrics != HealthMetrics()

/**
 * UI tests (iOS `StubSleepSource`): with `--uitesting-health-night` last night is 23:20 to 06:50 with a 10 minute
 * wake, else there's nothing. Always available, and asking always succeeds.
 */
internal class HealthStubSleepPort(
    private val hasNight: Boolean,
    /** False until `requestAccess`, for the onboarding walk; true everywhere else. */
    private var granted: Boolean = true,
) : HealthSleepSource {
    override fun availability(): HealthAvailability = HealthAvailability.Available

    override suspend fun requestAccess() {
        granted = true
    }

    override fun storeStatus(): HealthStoreStatus = HealthStoreStatus.available

    override suspend fun sleepGranted(): Boolean = granted

    /** With a night: the stub night, from Samsung Health. Without: none, so onboarding is collecting. */
    override suspend fun history(nights: Int): SleepHistory {
        val night = lastNight(Instant.EPOCH, Instant.now()) ?: return SleepHistory(emptyList(), null)
        return SleepHistory(listOf(night), HealthSources.SAMSUNG)
    }

    override fun installedApps(): Set<String> = setOf(HealthSources.SAMSUNG)

    override suspend fun lastNight(from: Instant, to: Instant): SleepNight? {
        if (!hasNight) return null
        return SleepNightBuilder.build(stubNight(LocalDate.now(DayKey.zone), DayKey.zone), DayKey.zone)
    }
}

/** UI tests: connected only with `--uitesting-health-night`; sends today with the stub night's stages through the stub API. */
internal class HealthStubDayPort(private val app: AppState) : HealthPort {
    private var sent = false

    override suspend fun isConnected(): Boolean = (app.flags.healthNight || app.flags.isSample) && app.healthConnected

    override suspend fun syncRecentDays() {
        if (sent) return
        val zone = DayKey.zone
        val today = LocalDate.now(zone)
        val input = DailyHealthInput(
            day = today, zone = zone, recordedAt = Instant.now(), sources = listOf("info.ibtaura.auraclarus.stub"),
            steps = 4200, distanceMetres = 3100.0, activeKcal = 180.0, sleep = stubNight(today, zone),
        )
        app.api.putHealthDays(listOf(DailyTotalsBuilder.build(input)))
        sent = true
    }
}

/** The night before `day`: in bed 23:10 to 06:55, asleep 23:20 to 06:50, awake 03:00 to 03:10. */
internal fun stubNight(day: LocalDate, zone: ZoneId): List<SleepSample> {
    val midnight = day.atStartOfDay(zone).toInstant()
    // Minutes from midnight; negative is the evening before.
    val parts = listOf(
        Triple(-50, 415, SleepSample.Kind.inBed),
        Triple(-40, 30, SleepSample.Kind.asleepCore),
        Triple(30, 90, SleepSample.Kind.asleepDeep),
        Triple(90, 180, SleepSample.Kind.asleepCore),
        Triple(180, 190, SleepSample.Kind.awake),
        Triple(190, 270, SleepSample.Kind.asleepREM),
        Triple(270, 410, SleepSample.Kind.asleepCore),
    )
    return parts.map { (from, to, kind) ->
        SleepSample(midnight.plusSeconds(from * 60L), midnight.plusSeconds(to * 60L), kind)
    }
}
