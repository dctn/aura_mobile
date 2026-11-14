package info.ibtaura.auraclarus.home

import androidx.compose.runtime.Composable

import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: blocks to review (iOS ReviewInbox). Owner: beta-score agent. */
@Composable
fun ReviewInboxScreen(app: AppState, nav: AuraNav) {
    ComingSoon("Review", onBack = { nav.pop() })
}
