package info.ibtaura.auraclarus.metric

import androidx.compose.runtime.Composable
import info.ibtaura.auraclarus.shell.Route
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: data sources and access (iOS MetricSourcesView). Owner: search+metric agent. */
@Composable
fun SourcesScreen(app: AppState, nav: AuraNav, route: Route.Sources) {
    ComingSoon("${route.name}", onBack = { nav.pop() })
}
