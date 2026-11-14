package info.ibtaura.auraclarus.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.CalendarEvent
import info.ibtaura.aura.core.EventDraft
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraConfirmDialog
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.NotDesignedYet
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.hex
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val kindNames = mapOf("focus" to "Focus", "meeting" to "Meeting", "recovery" to "Recovery")

/**
 * Makes or changes one calendar event (iOS EventEditor), pushed from Calendar. The Figma has no frame for it, so it
 * follows the Work hours form. An Apple Calendar event only takes a new kind here; the Mac owns the rest.
 */
@Composable
fun EventEditorScreen(app: AppState, nav: AuraNav, route: Route.EventEditor) {
    val event = remember(route.eventId) { route.eventId?.let { app.event(it) } }
    if (route.eventId != null && event == null) {
        // The app was restarted on this page and the event isn't in memory any more.
        NotDesignedYet("Edit event", onBack = { nav.pop() })
        return
    }
    EventEditorForm(app, event, close = { nav.pop() })
}

@Composable
private fun EventEditorForm(app: AppState, event: CalendarEvent?, close: () -> Unit) {
    var draft by remember { mutableStateOf(event?.let { EventDraft(it) } ?: EventDraft.new(Instant.now())) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    val isNew = event == null
    val appleOnly = event?.isFromApple == true
    // An Apple event's other fields aren't checked: only its kind is sent.
    val problem = if (appleOnly) null else draft.problem
    val fieldsEnabled = !appleOnly && !saving

    // Why some of it can't change, or how far a change reaches.
    val note = when {
        appleOnly -> "From Apple Calendar on your Mac. Change it there; here you can set its kind."
        event?.isOccurrence == true -> "This repeats. Changes here apply to this day only."
        event?.source == "planner" -> "Find a time placed this. You can still change it."
        else -> null
    }
    val deleteTitle = if (event?.isOccurrence == true) "Delete this day" else "Delete event"
    val deleteQuestion = if (event?.isOccurrence == true) "Delete this day of the event?" else "Delete this event?"

    BackHandler(enabled = saving) {}

    fun run(write: suspend () -> Unit) {
        saving = true
        errorText = null
        scope.launch {
            try {
                write()
                saving = false
                // Every screen loads again, so Calendar and Home show the change.
                app.dataVersion += 1
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                saving = false
                errorText = errorWords(e)
                app.note(e)
            }
        }
    }

    fun setStart(new: Instant) {
        // Moving the start keeps the length, as Calendar apps do.
        val length = draft.end.epochSecond - draft.start.epochSecond
        draft = if (length > 0) draft.copy(start = new, end = new.plusSeconds(length)) else draft.copy(start = new)
    }

    AuraPage(Modifier.testTag("event-editor")) {
        Text(
            if (isNew) "New event" else "Edit event",
            style = AuraType.title,
            color = Color.Black,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        if (note != null) {
            Text(note, style = AuraType.callout, color = AuraColors.gray, modifier = Modifier.testTag("event-editor-note"))
        }

        AuraCard(Modifier.alpha(if (fieldsEnabled) 1f else 0.6f), padding = 20.dp, spacing = 0.dp) {
            EditorField(
                draft.title, { draft = draft.copy(title = it) }, "Title", "event-title", fieldsEnabled, focus,
                style = AuraType.bodyMedium,
            )
            CardDivider()
            DateRow("Starts", draft.start, "event-start", fieldsEnabled) { setStart(it) }
            CardDivider()
            DateRow("Ends", draft.end, "event-end", fieldsEnabled) { draft = draft.copy(end = it) }
        }

        AuraCard(padding = 20.dp, spacing = 14.dp) {
            Text("Kind", style = AuraType.bodyMedium, color = Color.Black)
            KindPicker(draft.kind, enabled = !saving) { draft = draft.copy(kind = it) }
        }

        AuraCard(Modifier.alpha(if (fieldsEnabled) 1f else 0.6f), padding = 20.dp, spacing = 0.dp) {
            EditorField(draft.location, { draft = draft.copy(location = it) }, "Location", "event-location", fieldsEnabled, focus)
            CardDivider()
            EditorField(
                draft.notes, { draft = draft.copy(notes = it) }, "Notes", "event-notes", fieldsEnabled, focus,
                singleLine = false,
            )
        }

        if (problem != null) {
            Text(problem, style = AuraType.calloutSemibold, color = AuraColors.burnout, modifier = Modifier.testTag("event-problem"))
        }
        errorText?.let {
            Text(it, style = AuraType.callout, color = AuraColors.red, modifier = Modifier.testTag("event-error"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                "Cancel", close, Modifier.weight(1f).testTag("event-cancel"),
                style = PillStyle.Secondary, fills = true, enabled = !saving,
            )
            PillButton(
                if (saving) "Saving..." else if (isNew) "Add" else "Save",
                {
                    focus.clearFocus()
                    val current = draft
                    run { if (event != null) app.api.updateEvent(event, current) else app.api.createEvent(current) }
                },
                Modifier.weight(1f).testTag("event-save"),
                fills = true,
                enabled = !saving && problem == null,
            )
        }
        if (!isNew && !appleOnly) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !saving, role = Role.Button) { confirmingDelete = true }
                    .testTag("event-delete"),
                contentAlignment = Alignment.Center,
            ) {
                Text(deleteTitle, style = AuraType.bodyMedium, color = AuraColors.red)
            }
        }
    }

    if (confirmingDelete && event != null) {
        AuraConfirmDialog(
            title = deleteQuestion,
            message = null,
            confirmTitle = deleteTitle,
            onConfirm = {
                confirmingDelete = false
                run { app.api.deleteEvent(event.id) }
            },
            onDismiss = { confirmingDelete = false },
            confirmTag = "event-delete-confirm",
        )
    }
}

/** A plain field inside a card: placeholder in grey, 50dp tall; Notes grows from 3 to 8 lines. */
@Composable
private fun EditorField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    enabled: Boolean,
    focus: FocusManager,
    style: TextStyle = AuraType.body,
    singleLine: Boolean = true,
) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(vertical = if (singleLine) 0.dp else 14.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = style, color = AuraColors.secondary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            maxLines = if (singleLine) 1 else 8,
            textStyle = style.copy(color = Color.Black),
            keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

private val dateChipFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeChipFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** iOS's compact DatePicker: the title, then the date and the time in grey chips that open pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(title: String, value: Instant, tag: String, enabled: Boolean, onChange: (Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    val local = value.atZone(zone)
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.weight(1f))
        DateChip(dateChipFormat.format(local), "$tag-date", enabled) { pickingDate = true }
        DateChip(timeChipFormat.format(local), "$tag-time", enabled) { pickingTime = true }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingDate = false
                    state.selectedDateMillis?.let { ms ->
                        val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(day.atTime(local.toLocalTime()).atZone(zone).toInstant())
                    }
                }) { Text("Done", color = AuraColors.blue) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text("Cancel", color = AuraColors.blue) }
            },
        ) {
            DatePicker(state)
        }
    }

    if (pickingTime) {
        val state = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            containerColor = AuraColors.card,
            confirmButton = {
                TextButton(onClick = {
                    pickingTime = false
                    val day: LocalDate = local.toLocalDate()
                    onChange(day.atTime(LocalTime.of(state.hour, state.minute)).atZone(zone).toInstant())
                }) { Text("Done", color = AuraColors.blue) }
            },
            dismissButton = {
                TextButton(onClick = { pickingTime = false }) { Text("Cancel", color = AuraColors.blue) }
            },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun DateChip(text: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(hex(0x767680, 0.12f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(text, style = AuraType.body, color = Color.Black, maxLines = 1)
    }
}

/** iOS's segmented picker: Focus, Meeting, Recovery. Each segment is tagged "event-kind-<Name>". */
@Composable
private fun KindPicker(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(hex(0x767680, 0.12f))
            .padding(2.dp)
            .selectableGroup()
            .testTag("event-kind"),
    ) {
        EventDraft.kinds.forEach { kind ->
            val isSelected = kind == selected
            val name = kindNames[kind] ?: kind
            Box(
                Modifier
                    .weight(1f)
                    .height(30.dp)
                    .then(
                        if (isSelected) {
                            Modifier
                                .shadow(2.dp, RoundedCornerShape(7.dp), ambientColor = Color.Black.copy(alpha = 0.1f),
                                    spotColor = Color.Black.copy(alpha = 0.15f))
                                .background(Color.White, RoundedCornerShape(7.dp))
                        } else Modifier,
                    )
                    .clip(RoundedCornerShape(7.dp))
                    .selectable(selected = isSelected, enabled = enabled, role = Role.Tab) { onSelect(kind) }
                    .testTag("event-kind-$name"),
                contentAlignment = Alignment.Center,
            ) {
                Text(name, style = AuraType.system(14, if (isSelected) FontWeight.SemiBold else FontWeight.Medium), color = Color.Black)
            }
        }
    }
}

private fun errorWords(error: Throwable): String = when (error) {
    AuraError.Offline -> "You're offline. Try again when you're connected."
    is AuraError.Server -> when {
        error.code == "work_limit_reached" -> "That would pass today's work limit. Pick a shorter time or another day."
        error.code == "apple_event_read_only" -> "This event belongs to Apple Calendar. Change it on your Mac."
        error.status == 412 -> "This event changed somewhere else. Close it and open it again."
        error.status == 404 -> "This event is gone. It may have been deleted somewhere else."
        else -> "Couldn't save. Try again."
    }
    else -> "Couldn't save. Try again."
}
