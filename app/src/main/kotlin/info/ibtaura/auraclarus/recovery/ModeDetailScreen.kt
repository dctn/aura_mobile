package info.ibtaura.auraclarus.recovery

import androidx.compose.runtime.Composable
import info.ibtaura.auraclarus.shell.Route
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: a mode's page with Start (iOS ModeDetailView). Owner: recovery+run agent. */
@Composable
fun ModeDetailScreen(app: AppState, nav: AuraNav, route: Route.ModeDetail) {
    ComingSoon("${app.mode(route.modeId)?.name ?: route.headline ?: "Mode"}", onBack = { nav.pop() })
}
