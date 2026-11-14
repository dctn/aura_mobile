package info.ibtaura.auraclarus.shell

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * "Use sample data" (debug builds only): the whole app runs on made-up data held in memory on the phone, so it can be
 * judged as if the account had weeks of use. Nothing is read from or sent to the backend or Health Connect.
 * The switch lives in its own preferences file; turning it on or off restarts the app.
 */
object SampleMode {
    private const val PREFS = "aura_sample_mode"
    private const val KEY_ON = "on"

    /** Only debug builds offer it. */
    fun allowed(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun isOn(context: Context): Boolean =
        allowed(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    /** Saves the switch and restarts the app, so Services picks the matching backend. */
    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).commit()
        restart(context)
    }

    /** Adds `--sample-data` to the app's own launch when the switch is on. UI tests keep their stub data. */
    fun applyTo(flags: LaunchFlags, context: Context): LaunchFlags =
        if (!flags.isUiTesting && isOn(context)) flags.with(LaunchFlags.SAMPLE) else flags

    private fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        // AppState lives as long as the process; a fresh one builds the other backend.
        Runtime.getRuntime().exit(0)
    }
}
