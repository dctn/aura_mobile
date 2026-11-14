package info.ibtaura.auraclarus.shell

import android.content.Context
import info.ibtaura.aura.core.AuraApi
import info.ibtaura.aura.core.AuthService
import info.ibtaura.auraclarus.health.makeHealthPort
import info.ibtaura.auraclarus.health.makeSleepPort
import info.ibtaura.auraclarus.run.makeRunPort

/**
 * The backend and sign-in: stub data with no network when `--uitesting` is passed (CI walks), else dev us-west-1.
 */
fun makeServices(context: Context, flags: LaunchFlags): Pair<AuraApi, AuthService> =
    if (flags.isUiTesting) stubServices(flags) else liveServices(context)

/** Fills AppState's port slots. Each area's factory lives in its own *Wiring.kt and returns null until it's built. */
fun installPorts(app: AppState, context: Context) {
    app.run = makeRunPort(app, context)
    app.sleep = makeSleepPort(context, app.flags)
    app.health = makeHealthPort(context, app)
}
