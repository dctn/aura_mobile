package info.ibtaura.auraclarus.run

import androidx.compose.runtime.Composable
import info.ibtaura.auraclarus.shell.AppState

/**
 * PLACEHOLDER (owner: recovery+run agent): the "another session is running" alert (iOS OtherSessionAlert). RootScreen
 * calls it over the tabs with `showsHere = !app.runExpanded`; RunScreen shows its own copy while expanded.
 */
@Composable
fun OtherSessionAlert(app: AppState, showsHere: Boolean) {
}
