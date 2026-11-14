package info.ibtaura.auraclarus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import java.time.LocalTime
import java.util.Locale

/**
 * SwiftUI's compact hour-and-minute `DatePicker`: the time in a grey capsule; a tap opens a 24-hour clock to change it.
 * Shared by the morning check-in and the log-a-break sheet. Tagged `tag`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun BetaTimeField(title: String, time: LocalTime, tag: String, onChange: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val text = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
    Text(
        text,
        style = AuraType.system(17),
        color = if (open) AuraColors.blue else Color.Black,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AuraColors.track)
            .clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = "$title, $text" }
            .testTag(tag)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
    if (open) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = AuraColors.card,
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text(title, style = AuraType.calloutSemibold, color = Color.Black) },
            text = {
                TimePicker(
                    state = state,
                    modifier = Modifier.testTag("$tag-clock"),
                    colors = TimePickerDefaults.colors(
                        selectorColor = AuraColors.blue,
                        timeSelectorSelectedContainerColor = AuraColors.blue.copy(alpha = 0.15f),
                        timeSelectorSelectedContentColor = AuraColors.blue,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChange(LocalTime.of(state.hour, state.minute))
                        open = false
                    },
                    modifier = Modifier.testTag("$tag-done"),
                ) { Text("Done", style = AuraType.bodyMedium, color = AuraColors.blue) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) {
                    Text("Cancel", style = AuraType.bodyMedium, color = AuraColors.blue)
                }
            },
        )
    }
}
