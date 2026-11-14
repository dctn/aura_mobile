package info.ibtaura.auraclarus.home

import androidx.compose.runtime.Composable
import info.ibtaura.auraclarus.shell.Route
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.ComingSoon

/** PLACEHOLDER: new or changed calendar event (iOS EventEditor). Owner: home agent. */
@Composable
fun EventEditorScreen(app: AppState, nav: AuraNav, route: Route.EventEditor) {
    ComingSoon("${if (route.eventId == null) "New event" else "Edit event"}", onBack = { nav.pop() })
}
