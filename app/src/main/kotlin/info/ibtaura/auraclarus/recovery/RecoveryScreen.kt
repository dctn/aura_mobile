package info.ibtaura.auraclarus.recovery

import androidx.compose.runtime.Composable

import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: the Recovery tab (Now cards, shelves, take/log a break; iOS RecoveryView). `pushed` is true when reached from Search > Modes: show a back bar. Owner: recovery+run agent. */
@Composable
fun RecoveryScreen(app: AppState, nav: AuraNav, pushed: Boolean = false) {
    ComingSoon("Recovery", onBack = if (pushed) ({ nav.pop() }) else null)
}
