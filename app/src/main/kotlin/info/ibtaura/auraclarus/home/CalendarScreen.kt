package info.ibtaura.auraclarus.home

import androidx.compose.runtime.Composable

import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: Home > Work > Calendar (iOS HomeCalendarList with zones, hour grid). Owner: home agent. */
@Composable
fun CalendarScreen(app: AppState, nav: AuraNav) {
    ComingSoon("Calendar", onBack = { nav.pop() })
}
