package info.ibtaura.auraclarus.metric

import androidx.compose.runtime.Composable
import info.ibtaura.auraclarus.shell.Route
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: a metric's chart and links (iOS MetricView). Owner: search+metric agent. */
@Composable
fun MetricScreen(app: AppState, nav: AuraNav, route: Route.Metric) {
    ComingSoon("${route.name}", onBack = { nav.pop() })
}
