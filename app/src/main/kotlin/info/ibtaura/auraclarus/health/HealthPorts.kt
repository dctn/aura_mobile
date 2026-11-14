package info.ibtaura.auraclarus.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.AuthState
import info.ibtaura.aura.core.DailyHealthInput
import info.ibtaura.aura.core.DailyTotalsBuilder
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.HealthDay
import info.ibtaura.aura.core.HealthMetrics
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
) : HealthSleepSource {

    override fun availability(): HealthAvailability = when (reader.sdkStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NeedsInstall
        else -> HealthAvailability.Unavailable
    }

    override suspend fun requestAccess() {
        if (!reader.isAvailable) throw IllegalStateException("Health Connect isn't available")
        val requested = reader.requestedPermissions()
        if (!reader.grantedPermissions().containsAll(requested)) asker.request(requested)
        if (HealthConnectReader.SLEEP !in reader.grantedPermissions()) throw HealthAccessRefused()
    }

    override suspend fun lastNight(from: Instant, to: Instant): SleepNight? {
        if (!reader.isAvailable) return null
        if (HealthConnectReader.SLEEP !in reader.grantedPermissions()) return null
        val samples = reader.sleepSamples(from, to)
        return SleepNightBuilder.build(samples, DayKey.zone)
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
    private var lastSync: Instant? = null
    private var syncing = false

    override suspend fun isConnected(): Boolean {
        if (!app.healthConnected || !reader.isAvailable) return false
        return try {
            reader.grantedPermissions().any { it in HealthConnectReader.ALL_DAY_PERMISSIONS }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun syncRecentDays() {
        if (syncing) return
        val now = Instant.now()
        lastSync?.let { if (now.isBefore(it.plusSeconds(15 * 60))) return }
        val account = accountKey() ?: return
        syncing = true
        try {
            val backfilled = prefs.getBoolean(account, false)
            val zone = DayKey.zone
            val today = LocalDate.now(zone)
            val back = if (backfilled) 1L else BACKFILL_DAYS
            val days = (back downTo 0L).map { today.minusDays(it) }
            val inputs = try {
                reader.days(days, zone, reader.grantedPermissions(), now)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Revoked mid-way, rate-limited or not reachable: try on the next resume.
                return
            }
            val toSend = inputs.map { DailyTotalsBuilder.build(it) }.filter { it.hasData() }
            if (toSend.isNotEmpty()) {
                try {
                    app.api.putHealthDays(toSend)
                } catch (e: AuraError.Server) {
                    if (e.code == "not_available") {
                        lastSync = now
                        return
                    }
                    throw e
                }
            }
            prefs.edit().putBoolean(account, true).apply()
            lastSync = now
        } finally {
            syncing = false
        }
    }

    /** One key per signed-in account, so the next account gets its own 30 days. Only a hash of the email is kept. */
    private fun accountKey(): String? {
        val signedIn = app.authState as? AuthState.SignedIn ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest((signedIn.email ?: "account").lowercase().toByteArray())
        return "backfilled-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val BACKFILL_DAYS = 30L
    }
}

/** True when the day has any metric or workout; empty days aren't sent. */
internal fun HealthDay.hasData(): Boolean = workouts.isNotEmpty() || metrics != HealthMetrics()

/**
 * UI tests (iOS `StubSleepSource`): with `--uitesting-health-night` last night is 23:20 to 06:50 with a 10 minute
 * wake, else there's nothing. Always available, and asking always succeeds.
 */
internal class HealthStubSleepPort(private val hasNight: Boolean) : HealthSleepSource {
    override fun availability(): HealthAvailability = HealthAvailability.Available

    override suspend fun requestAccess() {}

    override suspend fun lastNight(from: Instant, to: Instant): SleepNight? {
        if (!hasNight) return null
        return SleepNightBuilder.build(stubNight(LocalDate.now(DayKey.zone), DayKey.zone), DayKey.zone)
    }
}

/** UI tests: connected only with `--uitesting-health-night`; sends today with the stub night's stages through the stub API. */
internal class HealthStubDayPort(private val app: AppState) : HealthPort {
    private var sent = false

    override suspend fun isConnected(): Boolean = app.flags.healthNight && app.healthConnected

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
