package info.ibtaura.auraclarus.perf

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.navigation.NavController
import info.ibtaura.aura.core.PerfLog

/**
 * Debug-only timing marks for the speed pass, all under logcat tag `AuraPerf`: API calls (core's PerfLog), page
 * changes, and disk reads on the main thread. Release builds never set the sink, so nothing here runs.
 */
object PerfMarks {
    private const val TAG = "AuraPerf"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        PerfLog.sink = { Log.i(TAG, it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PerfLog.line("start activity sinceProcess=${SystemClock.uptimeMillis() - Process.getStartUptimeMillis()}")
        }
        // Disk work on the main thread shows up as "StrictMode" lines (logged, never a crash).
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build(),
        )
    }

    /** Logs each page a tab's controller shows, so the walk can time from here to the next "idle". */
    fun destinationListener(tab: String): NavController.OnDestinationChangedListener? {
        if (!PerfLog.on) return null
        return NavController.OnDestinationChangedListener { _, destination, _ ->
            val route = destination.route?.substringAfterLast('.')?.substringBefore('/')?.substringBefore('?') ?: "?"
            PerfLog.line("screen tab=$tab route=$route")
        }
    }
}
