@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package info.ibtaura.auraclarus.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.CalendarEvent
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.EventDraft
import info.ibtaura.aura.core.EventMoveTargets
import info.ibtaura.aura.core.EventWorkTypes
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.aura.core.WorkLimitDetails
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraConfirmDialog
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.hex
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import info.ibtaura.auraclarus.shell.rememberSaveMessenger
import info.ibtaura.auraclarus.shell.saveInBackground
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val kindNames = mapOf("focus" to "Focus", "meeting" to "Meeting", "recovery" to "Recovery")

/**
 * Makes or changes one calendar event (iOS EventEditor), pushed from Calendar's grid and lists and Home's Upcoming. The
 * Figma has no frame for it, so it follows the Work hours form. Quick moves (next free slot, tomorrow, next workday),
 * work type, high stakes and the linked task sit under the times. An Apple Calendar event only takes a new kind, work
 * type and stakes here; the Mac owns the rest. Changing, moving or deleting a Find a time block unpins its task, so the
 * next plan leaves it alone.
 */
@Composable
fun EventEditorScreen(app: AppState, nav: AuraNav, route: Route.EventEditor) {
    val id = route.eventId
    var event by remember(id) { mutableStateOf(id?.let { app.event(it) }) }
    if (id == null || event != null) {
        EventEditorForm(app, event, close = { nav.pop() }, newDay = route.day)
        return
    }
    // The app was restarted on this page, so the event isn't in memory any more: read it by its id.
    var gone by remember(id) { mutableStateOf(false) }
    var failed by remember(id) { mutableStateOf(false) }
    var retry by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, retry) {
        failed = false
        try {
            val found = app.api.event(id)
            if (found == null) {
                gone = true
            } else {
                app.rememberEvents(listOf(found))
                event = found
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = true
        }
    }
    AuraPage(title = "Edit event", onBack = { nav.pop() }) {
        when {
            gone -> EventGone { nav.pop() }
            failed -> AuraCard(padding = 20.dp) {
                HomeRetryLine("Couldn't load this event.", { retry += 1 }, Modifier.testTag("event-editor-retry"))
            }
            else -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = AuraColors.gray,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp).testTag("event-editor-loading"),
                )
            }
        }
    }
}

/** The event was deleted (here or on another device) while this page was away. */
@Composable
private fun EventGone(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp, start = 12.dp, end = 12.dp).testTag("event-gone"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(AuraColors.card), contentAlignment = Alignment.Center) {
            Icon(auraIcon("calendar"), contentDescription = null, tint = AuraColors.gray, modifier = Modifier.size(30.dp))
        }
        Text("This event was deleted.", style = AuraType.bodyMedium, color = Color.Black, textAlign = TextAlign.Center)
        Text("It's no longer on your calendar.", style = AuraType.callout, color = AuraColors.gray, textAlign = TextAlign.Center)
        PillButton("Back", onBack, Modifier.testTag("event-gone-back"), style = PillStyle.Secondary)
    }
}

@Composable
private fun EventEditorForm(app: AppState, event: CalendarEvent?, close: () -> Unit, newDay: String? = null) {
    // The event as last read or moved; a quick move replaces it, so a later Save sends the moved version.
    var base by remember { mutableStateOf(event) }
    var draft by remember { mutableStateOf(event?.let { EventDraft(it) } ?: EventDraft.new(newEventAfter(newDay))) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // A quick move's result ("Moved to ..."), shown until the next action.
    var movedText by remember { mutableStateOf<String?>(null) }
    // True once a planner block was moved here, so Aura stops placing its task.
    var unpinned by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var showingTask by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    // A background save's refusal shows in the error line while this page shows, else as a toast.
    val messenger = rememberSaveMessenger { errorText = it }

    val current = base
    val isNew = current == null
    val appleOnly = current?.isFromApple == true
    val planner = current?.isPlannerBlock == true
    val linkedTaskId = current?.taskId
    // An Apple event's other fields aren't checked: only its kind, work type and stakes are sent.
    val problem = if (appleOnly) null else draft.problem
    val fieldsEnabled = !appleOnly && !saving
    val timeChanged = current != null && (draft.start != current.start || draft.end != current.end)

    // The task this block does, for the "Task:" row and the planner caption.
    var linkedTask by remember { mutableStateOf<TaskItem?>(null) }
    var taskGone by remember { mutableStateOf(false) }
    // Goes up when a task save finds the task changed elsewhere, so the latest copy loads.
    var taskReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(linkedTaskId, taskReload) {
        linkedTask = null
        taskGone = false
        val id = linkedTaskId ?: return@LaunchedEffect
        try {
            val found = app.api.task(id)
            linkedTask = found
            taskGone = found == null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
        }
    }

    // Why some of it can't change, how far a change reaches, or who placed it.
    val note = when {
        appleOnly -> "From Apple Calendar on your Mac. Change its time there; here you can set its kind, work type and stakes."
        current?.isOccurrence == true -> "This repeats. Changes here apply to this day only."
        planner && linkedTaskId == null -> "Placed by Aura as a break."
        planner -> "Placed by Aura for ${linkedTask?.title ?: if (taskGone) "a deleted task" else "a task"}"
        else -> null
    }
    // A planner block changed by hand: its task is unpinned, so the next plan leaves it where it is.
    val pinNote = if (planner && linkedTaskId != null && (timeChanged || unpinned)) "Aura won't move this block again." else null
    val deleteTitle = if (current?.isOccurrence == true) "Delete this day" else "Delete event"
    val deleteQuestion = if (current?.isOccurrence == true) "Delete this day of the event?" else "Delete this event?"

    BackHandler(enabled = saving) {}

    // Stops Find a time placing the task again. A failure here doesn't undo the change already saved.
    suspend fun unpin(taskId: String) {
        try {
            // The linked task's version is already here, so the PATCH goes without reading the task first.
            app.api.unpinTaskFromPlanner(taskId, linkedTask?.takeIf { it.id == taskId }?.version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
        }
    }

    /**
     * Closes the editor at once and saves in the app's scope. Every screen loads again when it lands, so Calendar and
     * Home show the change; a refusal is told (on this page if it still shows, else as a toast) and the screens load
     * again, so nothing that didn't save stays on them.
     */
    fun run(action: String, write: suspend () -> Unit) {
        errorText = null
        movedText = null
        close()
        app.saveInBackground(
            write = write,
            onFailed = { e ->
                messenger.tell(eventErrorWords(e, action))
                app.dataVersion += 1
            },
            onSaved = { app.dataVersion += 1 },
        )
    }

    /**
     * A quick move (POST .../move), instant like the other saves: the editor closes at once and the move runs in the
     * app's scope. The server picks the new time ("Next free slot" can't be known here), so it is told when it lands
     * ("Moved to ...", a toast once the editor has gone); a refusal is told the same way, and the screens load again.
     */
    fun move(target: String) {
        val original = base ?: return
        focus.clearFocus()
        errorText = null
        movedText = null
        close()
        app.saveInBackground(
            write = { app.api.moveEvent(original.id, to = target) },
            onFailed = { e ->
                messenger.tell(eventErrorWords(e, "move"))
                app.dataVersion += 1
            },
            onSaved = { moved ->
                val taskId = original.taskId
                // The move is saved; unpinning its task goes on behind it.
                if (original.isPlannerBlock && taskId != null) app.launch { unpin(taskId) }
                app.rememberEvents(listOf(moved))
                val local = moved.start.atZone(ZoneId.systemDefault())
                messenger.tell("Moved to ${dateChipFormat.format(local)}, ${timeChipFormat.format(local)}.")
                app.dataVersion += 1
            },
        )
    }

    fun setStart(new: Instant) {
        // Moving the start keeps the length, as Calendar apps do.
        val length = draft.end.epochSecond - draft.start.epochSecond
        draft = if (length > 0) draft.copy(start = new, end = new.plusSeconds(length)) else draft.copy(start = new)
    }

    AuraPage(
        Modifier.testTag("event-editor"),
        title = if (isNew) "New event" else "Edit event",
        onBack = { if (!saving) close() },
    ) {
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
        movedText?.let {
            Text(it, style = AuraType.callout, color = AuraColors.gray, modifier = Modifier.testTag("event-moved"))
        }
        pinNote?.let {
            Text(it, style = AuraType.callout, color = AuraColors.gray, modifier = Modifier.testTag("event-pin-note"))
        }

        if (current != null && current.canMove) {
            AuraCard(padding = 20.dp, spacing = 12.dp) {
                Text("Quick moves", style = AuraType.bodyMedium, color = Color.Black)
                EventQuickMoves(enabled = !saving, onMove = { move(it) })
            }
        }

        AuraCard(padding = 20.dp, spacing = 14.dp) {
            Text("Kind", style = AuraType.bodyMedium, color = Color.Black)
            KindPicker(draft.kind, enabled = !saving) { draft = draft.copy(kind = it) }
            Text("Work type", style = AuraType.bodyMedium, color = Color.Black)
            WorkTypeChips(draft.workType, enabled = !saving) { draft = draft.copy(workType = it) }
            CardDivider()
            HighStakesRow(draft.highStakes, enabled = !saving) { draft = draft.copy(highStakes = it) }
        }

        if (linkedTaskId != null) {
            LinkedTaskRow(linkedTask, taskGone, enabled = !saving) { showingTask = true }
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
                    val edited = draft
                    val original = base
                    run("save") {
                        if (original == null) {
                            app.api.createEvent(edited)
                        } else {
                            app.api.updateEvent(original, edited)
                            val taskId = original.taskId
                            val moved = edited.start != original.start || edited.end != original.end
                            if (original.isPlannerBlock && taskId != null && moved) unpin(taskId)
                        }
                    }
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

    if (confirmingDelete && current != null) {
        AuraConfirmDialog(
            title = deleteQuestion,
            message = if (planner && linkedTaskId != null) "Aura won't move this block again." else null,
            confirmTitle = deleteTitle,
            onConfirm = {
                confirmingDelete = false
                run("delete") {
                    app.api.deleteEvent(current.id)
                    val taskId = current.taskId
                    if (current.isPlannerBlock && taskId != null) unpin(taskId)
                }
            },
            onDismiss = { confirmingDelete = false },
            confirmTag = "event-delete-confirm",
        )
    }

    val task = linkedTask
    if (showingTask && task != null) {
        // The task editor from the Tasks page, as a sheet over this page.
        TaskEditorSheet(
            app = app,
            task = task,
            horizon = task.horizon,
            onClose = { showingTask = false },
            // Shown at once; every screen loads again once the save lands.
            onDone = { edit ->
                when (edit) {
                    is TaskEdit.Saved -> linkedTask = edit.task
                    is TaskEdit.Deleted -> {
                        linkedTask = null
                        taskGone = true
                    }
                }
            },
            onSaved = { edit ->
                (edit as? TaskEdit.Saved)?.let { linkedTask = it.task }
                app.dataVersion += 1
            },
            onFailed = { failure ->
                failure.undo.before?.let {
                    linkedTask = it
                    taskGone = false
                }
                messenger.tell(failure.words)
                if (failure.reload) {
                    taskReload += 1
                    app.dataVersion += 1
                }
            },
        )
    }
}

/** "Next free slot", "Tomorrow" and "Next workday": Calendar's move targets, wrapping onto a second line on a narrow phone. */
@Composable
private fun EventQuickMoves(enabled: Boolean, onMove: (String) -> Unit) {
    val moves = listOf(
        EventMoveTargets.NEXT_FREE to "Next free slot",
        EventMoveTargets.TOMORROW to "Tomorrow",
        EventMoveTargets.NEXT_WORKDAY to "Next workday",
    )
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        moves.forEach { (target, title) ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(hex(0x767680, 0.12f))
                    .clickable(enabled = enabled, role = Role.Button) { onMove(target) }
                    .testTag("event-move-$target")
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(title, style = AuraType.system(15, FontWeight.Medium), color = AuraColors.blue, maxLines = 1)
            }
        }
    }
}

private val workTypeNames = mapOf(
    "deepFocus" to "Deep focus",
    "detailHeavy" to "Detail-heavy",
    "ideas" to "Ideas",
    "routine" to "Routine",
    "learning" to "Learning",
)

/** The work types and None, the picked one blue. Each chip is tagged "event-worktype-<type>" ("-none" for None). */
@Composable
private fun WorkTypeChips(selected: String?, enabled: Boolean, onSelect: (String?) -> Unit) {
    val choices: List<String?> = EventWorkTypes.all + listOf<String?>(null)
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { type ->
            val isSelected = type == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) AuraColors.startBlue else hex(0x767680, 0.12f))
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(type) }
                    .testTag("event-worktype-${type ?: "none"}")
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    type?.let { workTypeNames[it] ?: it } ?: "None",
                    style = AuraType.system(15, FontWeight.Medium),
                    color = if (isSelected) Color.White else Color.Black,
                    maxLines = 1,
                )
            }
        }
    }
}

/** "High stakes" with a switch: the whole row toggles it. */
@Composable
private fun HighStakesRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag("event-high-stakes"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("High stakes", style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = AuraColors.green, checkedThumbColor = Color.White),
        )
    }
}

/** "Task: <title>" with a chevron; tapping opens the task editor. Grey and still while it loads or when it's gone. */
@Composable
private fun LinkedTaskRow(task: TaskItem?, gone: Boolean, enabled: Boolean, onOpen: () -> Unit) {
    val title = when {
        task != null -> "Task: ${task.title}"
        gone -> "Task: deleted"
        else -> "Task: ..."
    }
    AuraCard(padding = 0.dp, spacing = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clickable(enabled = enabled && task != null, role = Role.Button, onClickLabel = "Opens the task", onClick = onOpen)
                .testTag("event-task")
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = AuraType.bodyMedium,
                color = if (task != null) Color.Black else AuraColors.gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (task != null) {
                Icon(auraIcon("chevron.right"), contentDescription = null, tint = AuraColors.footnote, modifier = Modifier.size(16.dp))
            }
        }
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

/** The words for a failed save, move or delete (`action`), from Calendar's error codes. */
private fun eventErrorWords(error: Throwable, action: String): String {
    WorkLimitDetails.of(error)?.let { limit ->
        val day = if (limit.date == null || limit.date == DayKey.string(Instant.now())) "today's" else "that day's"
        return "That would put you over $day work limit (${limit.wouldBeMinutes} of ${limit.maxWorkMinutes} min)."
    }
    val fallback = when (action) {
        "move" -> "Couldn't move it. Try again."
        "delete" -> "Couldn't delete it. Try again."
        else -> "Couldn't save. Try again."
    }
    return when (error) {
        AuraError.Offline -> "You're offline. Try again when you're connected."
        is AuraError.Server -> when {
            error.code == "work_limit_reached" -> "That would put you over the day's work limit. Pick a shorter time or another day."
            error.code == "apple_event_read_only" -> "This event belongs to Apple Calendar. Change it on your Mac."
            error.code == "no_free_slot" -> "There's no free slot in your work hours in the next 3 weeks."
            error.code == "move_an_occurrence" -> "This is the whole repeating event. Open one day of it to move that day."
            error.code == "event_version_missing" -> "Couldn't read this event's version. Close it and open it again."
            error.code == "conflict" -> "That time clashes with something else on your calendar."
            error.status == 412 -> "This event changed somewhere else. Close it and open it again to see the latest."
            error.status == 404 -> "This event is gone. It may have been deleted somewhere else."
            else -> fallback
        }
        else -> fallback
    }
}

/** Where a new event starts: the next half hour today, or 09:00 on the day the Calendar was showing. */
private fun newEventAfter(day: String?): Instant {
    val zone = java.time.ZoneId.systemDefault()
    val date = day?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    if (date == null || date == java.time.LocalDate.now(zone)) return Instant.now()
    // EventDraft.new rounds up to the next half hour, so 08:59:59 gives 09:00.
    return date.atTime(9, 0).atZone(zone).toInstant().minusSeconds(1)
}
