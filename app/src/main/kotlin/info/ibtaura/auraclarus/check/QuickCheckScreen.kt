package info.ibtaura.auraclarus.check

import androidx.compose.runtime.Composable

import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: the Quick check cover (iOS QuickCheckScreen). Ends with nav.finishQuickCheck(result). Back is routed by RootScreen to finishQuickCheck(null) until this screen adds its own BackHandler (Stop the check?). Owner: check agent. */
@Composable
fun QuickCheckScreen(app: AppState, nav: AuraNav) {
    ComingSoon("Quick check", onBack = { nav.finishQuickCheck(null) })
}
