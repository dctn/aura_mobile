package info.ibtaura.auraclarus.metric

import androidx.compose.runtime.Composable
import info.ibtaura.auraclarus.shell.Route
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: all recorded values (iOS MetricRecordedView). Owner: search+metric agent. */
@Composable
fun RecordedScreen(app: AppState, nav: AuraNav, route: Route.Recorded) {
    ComingSoon("${route.name}", onBack = { nav.pop() })
}
