package info.ibtaura.auraclarus.run

import androidx.compose.runtime.Composable

import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: the run cover (iOS RunScreen): stage, controls, Done sheet. Hide with nav.closeRun(). Owner: recovery+run agent. */
@Composable
fun RunScreen(app: AppState, nav: AuraNav) {
    ComingSoon("Run", onBack = { nav.closeRun() })
}
