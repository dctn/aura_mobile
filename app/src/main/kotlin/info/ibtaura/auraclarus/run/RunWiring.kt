package info.ibtaura.auraclarus.run

import android.content.Context
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.RunPort

/**
 * PLACEHOLDER (owner: recovery+run agent): returns the run controller, e.g. `RunController(app.api, shortRun =
 * app.flags.shortRun, ...)`. Null means no runs yet: `app.startRun` does nothing.
 */
fun makeRunPort(app: AppState, context: Context): RunPort? = null
