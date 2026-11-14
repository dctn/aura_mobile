package info.ibtaura.auraclarus.search

import androidx.compose.runtime.Composable
import info.ibtaura.auraclarus.shell.Route
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: one catalogue section (iOS SearchCategoryView). Owner: search+metric agent. */
@Composable
fun SearchCategoryScreen(app: AppState, nav: AuraNav, route: Route.SearchCategory) {
    ComingSoon("${route.title}", onBack = { nav.pop() })
}
