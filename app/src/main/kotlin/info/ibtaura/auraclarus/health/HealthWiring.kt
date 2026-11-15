package info.ibtaura.auraclarus.health

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import info.ibtaura.aura.core.HealthSources
import info.ibtaura.aura.core.HealthStoreStatus
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.HealthPort
import info.ibtaura.auraclarus.shell.LaunchFlags
import info.ibtaura.auraclarus.shell.SleepPort

/** Whether the morning check-in can offer Health Connect for sleep. */
enum class HealthAvailability {
    /** Installed and ready: offer "Use Health Connect for sleep". */
    Available,
    /** Not installed, or too old: offer a quiet "Install Health Connect". */
    NeedsInstall,
    /** This phone can't run it: offer nothing. */
    Unavailable;

    val storeStatus: HealthStoreStatus
        get() = when (this) {
            Available -> HealthStoreStatus.available
            NeedsInstall -> HealthStoreStatus.needsInstall
            Unavailable -> HealthStoreStatus.unavailable
        }
}

/**
 * Health Connect sleep, or a stub when `flags.isUiTesting` (a night only with `flags.healthNight`, as iOS
 * `StubSleepSource(hasNight:)`).
 */
fun makeSleepPort(context: Context, flags: LaunchFlags): SleepPort? {
    if (flags.isUiTesting) return HealthStubSleepPort(hasNight = flags.healthNight, granted = !flags.onboarding)
    // Sample mode never touches Health Connect: a made-up night, sent to the in-memory backend.
    if (flags.isSample) return HealthStubSleepPort(hasNight = true)
    val application = context.applicationContext as? Application ?: return null
    val debuggable = (application.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    return HealthConnectSleepPort(
        sharedReader(application), sharedAsker(application), debuggable, HealthSourceStore(application),
        installed = { installedHealthApps(application) },
    )
}

/** The installed apps among `HealthSources.known`; the manifest's `<queries>` lists them so they are visible. */
internal fun installedHealthApps(context: Context): Set<String> =
    HealthSources.known.filter { context.packageManager.getLaunchIntentForPackage(it) != null }.toSet()

/** Health Connect day sync; a stub that sends one stub day in UI tests. */
fun makeHealthPort(context: Context, app: AppState): HealthPort? {
    if (app.flags.isUiTesting || app.flags.isSample) return HealthStubDayPort(app)
    val application = context.applicationContext as? Application ?: return null
    return HealthConnectDayPort(application, app, sharedReader(application))
}

/** What the check-in may offer for sleep. Unavailable when no sleep port is installed. */
fun healthAvailability(app: AppState): HealthAvailability =
    (app.sleep as? HealthSleepSource)?.availability() ?: HealthAvailability.Unavailable

/** Opens Health Connect's Play Store page (onboarding after install), or the web page when there's no store. */
fun openHealthConnectInstall(context: Context) {
    val pkg = "com.google.android.apps.healthdata"
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg&url=healthconnect%3A%2F%2Fonboarding"))
        .setPackage("com.android.vending")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private var reader: HealthConnectReader? = null
private var asker: HealthPermissionAsker? = null

private fun sharedReader(application: Application): HealthConnectReader =
    reader ?: HealthConnectReader(application).also { reader = it }

private fun sharedAsker(application: Application): HealthPermissionAsker =
    asker ?: HealthPermissionAsker(application).also { asker = it }

/** The shared Health Connect reader for Settings' permission count; null in UI tests or with no application. */
internal fun healthReader(context: Context): HealthConnectReader? {
    val application = context.applicationContext as? Application ?: return null
    return sharedReader(application)
}
