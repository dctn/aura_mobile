package info.ibtaura.auraclarus.run

import android.content.Context
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.RunPort

/**
 * The run controller (iOS `RunController`). It restores a saved run (outside UI tests) and keeps it across tabs.
 * `--uitesting-short-run` makes every timed run last 3 seconds.
 */
fun makeRunPort(app: AppState, context: Context): RunPort? = RunController(app, context.applicationContext)

/** The controller behind `app.run`, for the screens in `run/` and `recovery/`. */
internal val AppState.runController: RunController? get() = run as? RunController
