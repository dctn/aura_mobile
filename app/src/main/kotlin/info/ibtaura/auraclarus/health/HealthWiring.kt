package info.ibtaura.auraclarus.health

import android.content.Context
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.HealthPort
import info.ibtaura.auraclarus.shell.LaunchFlags
import info.ibtaura.auraclarus.shell.SleepPort

/**
 * PLACEHOLDER (owner: beta-score+health agent): Health Connect sleep, or a stub when `flags.isUiTesting` (a night
 * only with `flags.healthNight`, as iOS `StubSleepSource(hasNight:)`).
 */
fun makeSleepPort(context: Context, flags: LaunchFlags): SleepPort? = null

/** PLACEHOLDER (owner: beta-score+health agent): Health Connect day sync; null in UI tests. */
fun makeHealthPort(context: Context, app: AppState): HealthPort? = null
