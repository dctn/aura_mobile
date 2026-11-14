package info.ibtaura.auraclarus.run

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.ZoneText
import info.ibtaura.auraclarus.shell.AppState

/**
 * Shown when a run asked for a session and the backend answered with one already running, maybe on another device.
 * Only one session can run at a time, so the person picks: stop that one and start this, or keep it and drop this run.
 * RootScreen shows it while the run page is hidden; RunScreen shows its own copy while it covers the tabs.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OtherSessionAlert(app: AppState, showsHere: Boolean) {
    val controller = app.runController ?: return
    val other = controller.active?.otherSession ?: return
    if (!showsHere) return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = AuraColors.card,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("other-session-alert"),
        title = { Text("Another session is running", style = AuraType.system(17, FontWeight.SemiBold), color = Color.Black) },
        text = { Text(otherSessionMessage(other), style = AuraType.callout, color = AuraColors.gray) },
        confirmButton = {
            TextButton(onClick = { controller.stopOtherAndStart() }, modifier = Modifier.testTag("other-session-stop")) {
                Text("Stop it and start mine", style = AuraType.bodyMedium, color = AuraColors.blue)
            }
        },
        dismissButton = {
            TextButton(onClick = { controller.keepOtherSession() }, modifier = Modifier.testTag("other-session-keep")) {
                Text("Keep it", style = AuraType.system(17, FontWeight.SemiBold), color = AuraColors.blue)
            }
        },
    )
}

/** "“Deep work” on your computer is still going, until 16:30. Only one session can run at a time." */
internal fun otherSessionMessage(other: OtherSession): String {
    val title = other.title?.trim().orEmpty()
    val name = when {
        title.isNotEmpty() -> "“$title”"
        !other.kind.isNullOrEmpty() -> "A ${other.kind} session"
        else -> "A session"
    }
    val place = when (other.device) {
        "phone" -> " on your phone"
        "desktop", "mac", "windows" -> " on your computer"
        "web" -> " in your browser"
        "watch" -> " on your watch"
        else -> ""
    }
    val until = other.endsAt?.let { ", until ${ZoneText.clock(it)}" } ?: ""
    return "$name$place is still going$until. Only one session can run at a time."
}
