package info.ibtaura.auraclarus.home

import androidx.compose.runtime.Composable
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav

/** Yesterday's and today's sessions on a page of their own, with rating (the iOS SessionsList that Home shows inline). */
@Composable
fun SessionsScreen(app: AppState, nav: AuraNav) {
    AuraPage(title = "Sessions", onBack = { nav.pop() }) {
        SessionsListContent(app, showsHeader = false)
    }
}
