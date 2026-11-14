package info.ibtaura.auraclarus.home

import androidx.compose.runtime.Composable

import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: Home > Work > Task (iOS HomeTaskList). Owner: home agent. */
@Composable
fun TasksScreen(app: AppState, nav: AuraNav) {
    ComingSoon("Task", onBack = { nav.pop() })
}
