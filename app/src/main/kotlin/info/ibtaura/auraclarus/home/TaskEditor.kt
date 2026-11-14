package info.ibtaura.auraclarus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.TaskDemand
import info.ibtaura.aura.core.TaskDraft
import info.ibtaura.aura.core.TaskHorizon
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.aura.core.TaskPriority
import info.ibtaura.aura.core.TaskWorkMode
import info.ibtaura.aura.core.TaskWorkType
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraConfirmDialog
import info.ibtaura.auraclarus.design.AuraSheet
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.hex
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.OwnSaves
import info.ibtaura.auraclarus.shell.saveInBackground
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Makes or changes one task, in a sheet over the Tasks page: title, notes, priority, list, due date with an optional
 * time, estimate and flag. Editing adds Delete. The Figma has no frame for it, so it follows the event editor.
 * `task` null makes a new one on `horizon`.
 *
 * Auto-allocation: Work kind (Focus or Meeting), "Auto-schedule" (on for a new focus task; a meeting is never placed),
 * and when it's on, How hard and Work type for the Planner. "Schedule" saves the task with Auto-schedule on, closes
 * the sheet and calls `onSchedule` with it, which opens Find a time for just that task. Deleting a task also removes
 * its future planner blocks.
 *
 * Instant saves: Add, Save and Delete show the change through `onDone` and close the sheet at once; the write runs in
 * the app's scope. `onSaved` then gets the server's version, or `onFailed` what to put back and the words to show.
 * The title check still keeps the sheet open. Schedule is instant too: it shows the task, closes and opens Find a
 * time at once, and Find a time waits for the save (`TaskSaveGate`) before the Planner places the task. `own` marks the planner-block clean-up's data bump as the page's own, so the page doesn't load again.
 */
@Composable
internal fun TaskEditorSheet(
    app: AppState,
    task: TaskItem?,
    horizon: TaskHorizon,
    onClose: () -> Unit,
    onDone: (TaskEdit) -> Unit,
    onSaved: (TaskEdit) -> Unit = {},
    onFailed: (TaskSaveFailure) -> Unit = {},
    onSchedule: (TaskItem) -> Unit = {},
    own: OwnSaves? = null,
) {
    var draft by remember(task?.id) { mutableStateOf(task?.let { TaskDraft(it) } ?: TaskDraft(horizon = horizon)) }
    // Chosen once, so a create retried after a lost answer gives back the same task.
    val newId = remember { TaskDraft.newId() }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var triedSave by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val isNew = task == null
    val problem = draft.problem

    /**
     * Shows `edit` at once, closes the sheet and saves in the app's scope; a failure puts `undo` back. `landed`, when
     * given, answers whether the save went through (Find a time waits on it after Schedule).
     */
    fun showAndSave(
        action: String,
        edit: TaskEdit,
        undo: TaskUndo,
        landed: CompletableDeferred<Boolean>? = null,
        write: suspend () -> TaskEdit,
    ) {
        onDone(edit)
        onClose()
        val job = app.saveInBackground(
            write = write,
            onFailed = { e ->
                landed?.complete(false)
                onFailed(TaskSaveFailure(undo, TaskText.errorWords(e, action), taskNeedsReload(e)))
            },
            onSaved = {
                landed?.complete(true)
                onSaved(it)
            },
        )
        // Cancelled without an answer: never leave Find a time waiting.
        landed?.let { gate -> job.invokeOnCompletion { gate.complete(false) } }
    }

    AuraSheet(onDismiss = { if (!saving) onClose() }, dismissible = !saving) {
        val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 16.dp + navBar)
                .testTag("task-editor"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (isNew) "New task" else "Edit task",
                style = AuraType.title,
                color = Color.Black,
                modifier = Modifier.semantics { heading() },
            )

            AuraCard(Modifier.alpha(if (saving) 0.6f else 1f), padding = 20.dp, spacing = 0.dp) {
                TaskField(draft.title, { draft = draft.copy(title = it) }, "Title", "task-title-field", !saving, focus,
                    style = AuraType.bodyMedium)
                CardDivider()
                TaskField(draft.notes, { draft = draft.copy(notes = it) }, "Notes", "task-notes-field", !saving, focus,
                    singleLine = false)
            }

            AuraCard(padding = 20.dp, spacing = 12.dp) {
                Text("List", style = AuraType.bodyMedium, color = Color.Black)
                TaskSegments(
                    options = TaskHorizon.entries,
                    selected = draft.horizon,
                    label = { TaskText.horizonNames[it] ?: it.raw },
                    tag = { "task-horizon-${it.raw}" },
                    enabled = !saving,
                    onSelect = { draft = draft.copy(horizon = it) },
                )
                Text("Priority", style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.padding(top = 4.dp))
                TaskSegments(
                    options = TaskText.priorityChoices,
                    selected = draft.priority,
                    label = { TaskText.priorityNames[it] ?: it.raw },
                    tag = { "task-priority-${it.raw}" },
                    enabled = !saving,
                    onSelect = { draft = draft.copy(priority = it) },
                    selectedColor = { if (it == TaskPriority.none) Color.Black else taskPriorityColor(it) },
                )
            }

            AuraCard(padding = 20.dp, spacing = 0.dp) {
                DueRows(draft, enabled = !saving) { draft = it }
            }

            AuraCard(padding = 20.dp, spacing = 12.dp) {
                Text("Estimate", style = AuraType.bodyMedium, color = Color.Black)
                EstimateChips(draft.estimateMinutes, enabled = !saving) { draft = draft.copy(estimateMinutes = it) }
            }

            PlanningCard(draft, enabled = !saving) { draft = it }

            AuraCard(padding = 20.dp, spacing = 0.dp) {
                SwitchRow("Flag", draft.flagged, "task-flag", enabled = !saving) { draft = draft.copy(flagged = it) }
            }

            task?.let { existing -> TaskFacts(existing) }

            if (problem != null && (triedSave || draft.title.isNotEmpty())) {
                Text(problem, style = AuraType.calloutSemibold, color = AuraColors.burnout, modifier = Modifier.testTag("task-problem"))
            }
            errorText?.let {
                Text(it, style = AuraType.callout, color = AuraColors.red, modifier = Modifier.testTag("task-error"))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Cancel", onClose, Modifier.weight(1f).testTag("task-cancel"),
                    style = PillStyle.Secondary, fills = true, enabled = !saving)
                PillButton(
                    if (saving) "Saving..." else if (isNew) "Add" else "Save",
                    {
                        triedSave = true
                        focus.clearFocus()
                        if (problem != null) return@PillButton
                        val current = draft
                        if (task == null) {
                            // The id is chosen here, so the row shown now is the one the server answers for.
                            val shown = current.previewTask(newId)
                            showAndSave("save", TaskEdit.Saved(shown), TaskUndo(newId, null)) {
                                TaskEdit.Saved(app.api.createTask(current, newId))
                            }
                        } else {
                            val changes = current.changes(task)
                            if (changes.isEmpty) {
                                onClose()
                                return@PillButton
                            }
                            val shown = changes.appliedTo(task)
                            showAndSave("save", TaskEdit.Saved(shown), TaskUndo(task.id, task)) {
                                TaskEdit.Saved(app.api.updateTask(task.id, task.version, changes) ?: shown)
                            }
                        }
                    },
                    Modifier.weight(1f).testTag("task-save"),
                    fills = true,
                    enabled = !saving && problem == null,
                )
            }

            if (draft.workMode == TaskWorkMode.focus && task?.done != true) {
                PillButton(
                    "Schedule",
                    {
                        triedSave = true
                        focus.clearFocus()
                        if (problem != null) return@PillButton
                        val current = draft.copy(autoAllocate = true)
                        draft = current
                        // Shown and closed at once; Find a time opens now and waits for the save before planning.
                        val landed = CompletableDeferred<Boolean>()
                        if (task == null) {
                            val shown = current.previewTask(newId)
                            TaskSaveGate.track(app, newId, landed)
                            showAndSave("save", TaskEdit.Saved(shown), TaskUndo(newId, null), landed) {
                                TaskEdit.Saved(app.api.createTask(current, newId))
                            }
                            onSchedule(shown)
                        } else {
                            val changes = current.changes(task)
                            if (changes.isEmpty) {
                                onClose()
                                onSchedule(task)
                            } else {
                                val shown = changes.appliedTo(task)
                                TaskSaveGate.track(app, task.id, landed)
                                showAndSave("save", TaskEdit.Saved(shown), TaskUndo(task.id, task), landed) {
                                    TaskEdit.Saved(app.api.updateTask(task.id, task.version, changes) ?: shown)
                                }
                                onSchedule(shown)
                            }
                        }
                    },
                    Modifier.testTag("task-schedule"),
                    style = PillStyle.Secondary,
                    fills = true,
                    enabled = !saving && problem == null,
                )
            }

            if (task != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !saving, role = Role.Button) { confirmingDelete = true }
                        .testTag("task-delete"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Delete task", style = AuraType.bodyMedium, color = AuraColors.red)
                }
            }
        }
    }

    if (confirmingDelete && task != null) {
        AuraConfirmDialog(
            title = "Delete this task?",
            message = "“${task.title}” goes from every device.",
            confirmTitle = "Delete",
            onConfirm = {
                confirmingDelete = false
                showAndSave("delete", TaskEdit.Deleted(task.id), TaskUndo(task.id, task)) {
                    app.api.deleteTask(task.id)
                    // Planner leaves a deleted task's blocks on the calendar. The task is gone either way, so this
                    // runs on its own and a failure here is only noted.
                    app.launch { clearPlannerBlocks(app, task, own) }
                    TaskEdit.Deleted(task.id)
                }
            },
            onDismiss = { confirmingDelete = false },
            confirmTag = "task-delete-confirm",
        )
    }
}

/**
 * Work kind, Auto-schedule and, when it's on, How hard and Work type. A meeting turns Auto-schedule off and greys it:
 * Find a time only places focus work.
 */
@Composable
private fun PlanningCard(draft: TaskDraft, enabled: Boolean, onChange: (TaskDraft) -> Unit) {
    AuraCard(Modifier.testTag("task-planning"), padding = 20.dp, spacing = 12.dp) {
        Text("Work kind", style = AuraType.bodyMedium, color = Color.Black)
        TaskSegments(
            options = TaskWorkMode.entries,
            selected = draft.workMode,
            label = { TaskPlanning.workModeNames[it] ?: it.raw },
            tag = { "task-workmode-${it.raw}" },
            enabled = enabled,
            onSelect = { onChange(draft.copy(workMode = it)) },
        )
        SwitchRow("Auto-schedule", draft.placesItself, "task-auto", enabled = enabled && draft.workMode == TaskWorkMode.focus) {
            onChange(draft.copy(autoAllocate = it))
        }
        TaskPlanning.captions(draft).forEach {
            Text(it, style = AuraType.callout, color = AuraColors.gray)
        }
        if (draft.placesItself) {
            Text("How hard", style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.padding(top = 4.dp))
            val demand = TaskDemand.from(draft.demand)
            TaskSegments(
                options = TaskDemand.entries,
                selected = demand,
                label = { TaskPlanning.demandNames[it] ?: it.name },
                tag = { "task-demand-${it.name}" },
                enabled = enabled,
                // Tapping the shown choice keeps a stored value that sits between the three.
                onSelect = { if (it != demand) onChange(draft.copy(demand = it.value)) },
            )
            Text("Work type", style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.padding(top = 4.dp))
            WorkTypeChips(draft.workType, enabled) { onChange(draft.copy(workType = it)) }
        }
    }
}

/** Any, then the five kinds of work; wraps onto a second line on a narrow phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkTypeChips(selected: TaskWorkType?, enabled: Boolean, onSelect: (TaskWorkType?) -> Unit) {
    val choices: List<TaskWorkType?> = listOf<TaskWorkType?>(null) + TaskWorkType.entries
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
                    .testTag("task-worktype-${type?.raw ?: "any"}")
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    type?.let { TaskPlanning.workTypeNames[it] } ?: "Any",
                    style = AuraType.system(15, FontWeight.Medium),
                    color = if (isSelected) Color.White else Color.Black,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The colour that marks a priority: red, orange, yellow, blue; none is grey. */
internal fun taskPriorityColor(priority: TaskPriority): Color = when (priority) {
    TaskPriority.urgent -> AuraColors.red
    TaskPriority.high -> AuraColors.orange
    TaskPriority.medium -> hex(0xFFCC00)
    TaskPriority.low -> AuraColors.blue
    TaskPriority.none -> AuraColors.gray
}

/** A plain field inside a card: placeholder in grey, 50dp tall; Notes grows from 3 to 8 lines. */
@Composable
private fun TaskField(
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
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

/** iOS's segmented picker, the event editor's look. The chosen segment can take its own text colour. */
@Composable
internal fun <T> TaskSegments(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    tag: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    selectedColor: (T) -> Color = { Color.Black },
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(hex(0x767680, 0.12f))
            .padding(2.dp)
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .then(
                        if (isSelected) {
                            Modifier
                                .shadow(2.dp, RoundedCornerShape(7.dp), ambientColor = Color.Black.copy(alpha = 0.1f),
                                    spotColor = Color.Black.copy(alpha = 0.15f))
                                .background(Color.White, RoundedCornerShape(7.dp))
                        } else Modifier,
                    )
                    .clip(RoundedCornerShape(7.dp))
                    .selectable(selected = isSelected, enabled = enabled, role = Role.Tab) { onSelect(option) }
                    .testTag(tag(option)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = AuraType.system(14, if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (isSelected) selectedColor(option) else Color.Black,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, tag: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = AuraColors.green, checkedThumbColor = Color.White),
        )
    }
}

private val dueDateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val dueTimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** "Due date" on/off; when on, the day, then "Time" on/off and the time. Turning the date on starts at today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueRows(draft: TaskDraft, enabled: Boolean, onChange: (TaskDraft) -> Unit) {
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val day = draft.dueDay
    val today = LocalDate.now(DayKey.zone)

    SwitchRow("Due date", day != null, "task-due-toggle", enabled) { on ->
        onChange(if (on) draft.copy(dueDay = today) else draft.copy(dueDay = null, dueTime = null))
    }
    if (day != null) {
        CardDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Day", style = AuraType.body, color = Color.Black, modifier = Modifier.weight(1f))
            ChipButton(TaskText.day(day, today).let { if (it.contains(' ')) dueDateFormat.format(day) else it },
                "task-due-date", enabled) { pickingDate = true }
        }
        CardDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Time", style = AuraType.body, color = Color.Black, modifier = Modifier.weight(1f))
            val time = draft.dueTime
            if (time != null) {
                ChipButton(dueTimeFormat.format(time), "task-due-time", enabled) { pickingTime = true }
                Box(Modifier.padding(start = 10.dp))
            }
            Switch(
                checked = time != null,
                onCheckedChange = { on ->
                    onChange(draft.copy(dueTime = if (on) LocalTime.of(9, 0) else null))
                },
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = AuraColors.green, checkedThumbColor = Color.White),
                modifier = Modifier.testTag("task-time-toggle"),
            )
        }
    }

    if (pickingDate && day != null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingDate = false
                    state.selectedDateMillis?.let { ms ->
                        onChange(draft.copy(dueDay = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()))
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
        val start = draft.dueTime ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            containerColor = AuraColors.card,
            confirmButton = {
                TextButton(onClick = {
                    pickingTime = false
                    onChange(draft.copy(dueTime = LocalTime.of(state.hour, state.minute)))
                }) { Text("Done", color = AuraColors.blue) }
            },
            dismissButton = {
                TextButton(onClick = { pickingTime = false }) { Text("Cancel", color = AuraColors.blue) }
            },
            text = { TimePicker(state) },
        )
    }
}

/** None, then the presets (and the task's own value); wraps onto a second line on a narrow phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EstimateChips(selected: Int?, enabled: Boolean, onSelect: (Int?) -> Unit) {
    val choices: List<Int?> = listOf<Int?>(null) + TaskText.estimateChoices(selected)
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { minutes ->
            val isSelected = minutes == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) AuraColors.startBlue else hex(0x767680, 0.12f))
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(minutes) }
                    .testTag("task-estimate-${minutes ?: "none"}")
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    minutes?.let { TaskText.minutes(it) } ?: "None",
                    style = AuraType.system(15, FontWeight.Medium),
                    color = if (isSelected) Color.White else Color.Black,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ChipButton(text: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
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

/** Read-only facts about a saved task: when it's planned for, its calendar block, Capacity's cost, when it was done. */
@Composable
private fun TaskFacts(task: TaskItem) {
    val zone = DayKey.zone
    val today = LocalDate.now(zone)
    val lines = buildList {
        task.plannedFor?.let { key -> DayKey.localDate(key)?.let { add("Planned for ${TaskText.day(it, today)}") } }
        task.scheduledAt?.let {
            add("On your calendar ${TaskText.day(it.atZone(zone).toLocalDate(), today)}, ${TaskText.time(it, zone)}")
        }
        task.costPoints?.let { add("Expected cost: ${"%.1f".format(it)} capacity points") }
        if (task.done) task.completedAt?.let { add("Completed ${TaskText.day(it.atZone(zone).toLocalDate(), today)}") }
    }
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("task-facts"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Text(it, style = AuraType.callout, color = AuraColors.gray) }
    }
}
