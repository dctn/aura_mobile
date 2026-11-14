package info.ibtaura.auraclarus.home

import androidx.compose.runtime.Composable

import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: today's sessions with rating (iOS SessionsList). Owner: beta-score agent. */
@Composable
fun SessionsScreen(app: AppState, nav: AuraNav) {
    ComingSoon("Sessions", onBack = { nav.pop() })
}
