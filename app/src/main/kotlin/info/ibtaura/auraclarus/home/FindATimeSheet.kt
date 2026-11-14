package info.ibtaura.auraclarus.home

import androidx.compose.runtime.Composable
import info.ibtaura.auraclarus.shell.Route
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: preview and confirm a day plan (iOS FindATimeSheet); opens the Quick check with nav.openQuickCheck. Owner: check agent. */
@Composable
fun FindATimeSheet(app: AppState, nav: AuraNav, route: Route.FindATime) {
    ComingSoon("Find a time", onBack = { nav.pop() })
}
