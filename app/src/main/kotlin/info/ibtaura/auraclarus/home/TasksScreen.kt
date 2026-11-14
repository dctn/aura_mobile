package info.ibtaura.auraclarus.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.TaskHorizon
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.aura.core.TaskPriority
import info.ibtaura.aura.core.TaskStatus
import info.ibtaura.aura.core.withStatus
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.GlassCircleButton
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

/** Which task the editor sheet has open: a new one, or the one tapped. */
private sealed class EditorTarget {
    data object New : EditorTarget()
    data class Existing(val task: TaskItem) : EditorTarget()
}

/**
 * Home > Work > Task: the Today, Week and Month lists (Task's `horizon`). Tick a task off or back on (shown at once,
 * put back with a message if the save fails), tap one to edit or delete it, "+" to add one, pull down to reload.
 * Completed tasks wait under Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(app: AppState, nav: AuraNav) {
    var tasks by remember { mutableStateOf<List<TaskItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var horizonRaw by rememberSaveable { mutableStateOf(TaskHorizon.today.raw) }
    val horizon = TaskHorizon.from(horizonRaw)
    var showDone by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // Tasks with a tick on its way, so a second tap waits for the first.
    var busy by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(app.dataVersion, retry) {
        try {
            tasks = app.api.tasks()
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = tasks == null
            if (tasks != null) message = "Couldn't reload your tasks."
        } finally {
            refreshing = false
        }
    }

    fun toggle(task: TaskItem) {
        if (task.id in busy) return
        val reopening = task.done
        val shown = task.withStatus(if (reopening) TaskStatus.pending else TaskStatus.completed)
        tasks = tasks?.replacing(shown)
        busy = busy + task.id
        message = null
        scope.launch {
            try {
                if (reopening) {
                    val saved = app.api.reopenTask(task.id, task.version)
                    tasks = tasks?.replacing(saved)
                } else {
                    val done = app.api.completeTask(task.id)
                    var list = tasks?.replacing(done.task)
                    // A repeating task's next copy joins the list.
                    done.next?.let { list = list?.replacing(it) }
                    tasks = list
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Put it back as it was.
                tasks = tasks?.replacing(task)
                message = TaskText.errorWords(e, if (reopening) "reopen it" else "complete it")
                app.note(e)
            } finally {
                busy = busy - task.id
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            message = null
            retry += 1
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        AuraPage(
            Modifier.testTag("tasks-page"),
            title = "Tasks",
            onBack = { nav.pop() },
            spacing = 16.dp,
            trailing = {
                GlassCircleButton("plus", { editing = EditorTarget.New }, Modifier.testTag("tasks-add"),
                    contentDescription = "Add task")
            },
        ) {
            val loaded = tasks
            val counts = loaded?.let { TaskSections.openCounts(it) }.orEmpty()
            TaskSegments(
                options = TaskHorizon.entries,
                selected = horizon,
                label = { h ->
                    val name = TaskText.horizonNames[h] ?: h.raw
                    val count = counts[h] ?: 0
                    if (count > 0) "$name  $count" else name
                },
                tag = { "tasks-horizon-${it.raw}" },
                enabled = true,
                onSelect = { horizonRaw = it.raw },
            )

            message?.let {
                Text(it, style = AuraType.callout, color = AuraColors.red, modifier = Modifier.testTag("tasks-message"))
            }

            when {
                failed -> HomeRetryLine("Couldn't load your tasks.", retry = { retry += 1 })
                loaded == null -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                }
                else -> {
                    val sections = TaskSections.of(loaded, horizon)
                    val now = Instant.now()
                    if (sections.open.isEmpty()) {
                        EmptyList(horizon) { editing = EditorTarget.New }
                    } else {
                        TaskCard(Modifier.testTag("tasks-open")) {
                            sections.open.forEachIndexed { i, task ->
                                if (i > 0) CardDivider(Modifier.padding(start = 44.dp))
                                TaskRow(task, now, busy = task.id in busy, onCheck = { toggle(task) },
                                    onOpen = { editing = EditorTarget.Existing(task) })
                            }
                        }
                    }
                    if (sections.done.isNotEmpty()) {
                        DoneHeader(sections.done.size, showDone) { showDone = !showDone }
                        if (showDone) {
                            TaskCard(Modifier.testTag("tasks-done")) {
                                sections.done.forEachIndexed { i, task ->
                                    if (i > 0) CardDivider(Modifier.padding(start = 44.dp))
                                    TaskRow(task, now, busy = task.id in busy, onCheck = { toggle(task) },
                                        onOpen = { editing = EditorTarget.Existing(task) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { target ->
        val task = (target as? EditorTarget.Existing)?.task?.let { t -> tasks?.firstOrNull { it.id == t.id } ?: t }
        TaskEditorSheet(
            app = app,
            task = task,
            horizon = horizon,
            onClose = { editing = null },
            onDone = { edit ->
                when (edit) {
                    is TaskEdit.Saved -> {
                        tasks = tasks?.replacing(edit.task)
                        // A new or moved task shows on the list it's now on.
                        horizonRaw = edit.task.horizon.raw
                    }
                    is TaskEdit.Deleted -> tasks = tasks?.filterNot { it.id == edit.id }
                }
                message = null
            },
        )
    }
}

/** A white card for rows: 20dp at the sides, the rows bring their own height. */
@Composable
private fun TaskCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AuraCard(modifier, padding = 0.dp, spacing = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) { content() }
    }
}

@Composable
private fun EmptyList(horizon: TaskHorizon, onAdd: () -> Unit) {
    val name = TaskText.horizonNames[horizon] ?: horizon.raw
    AuraCard(Modifier.testTag("tasks-empty"), padding = 24.dp, spacing = 6.dp) {
        Text("Nothing on $name", style = AuraType.bodyMedium, color = Color.Black)
        Text(
            "Add a task",
            style = AuraType.calloutMedium,
            color = AuraColors.blue,
            modifier = Modifier.clickable(role = Role.Button, onClick = onAdd).padding(vertical = 4.dp),
        )
    }
}

/** "Done 3" with a chevron that turns; tapping shows or hides the completed tasks. */
@Composable
private fun DoneHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    val angle by animateFloatAsState(if (open) 90f else 0f, label = "done-chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (open) "Shown" else "Hidden" }
            .testTag("tasks-done-toggle")
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Done", style = AuraType.heading, color = Color.Black)
        Spacer(Modifier.width(8.dp))
        Text("$count", style = AuraType.callout, color = AuraColors.gray, modifier = Modifier.weight(1f))
        Icon(auraIcon("chevron.right"), contentDescription = null, tint = AuraColors.gray,
            modifier = Modifier.size(18.dp).rotate(angle))
    }
}

/**
 * One task: the round tick, then the title (struck through when done) over its due line and estimate, then its flag
 * and priority. The tick and the rest are separate targets.
 */
@Composable
private fun TaskRow(task: TaskItem, now: Instant, busy: Boolean, onCheck: () -> Unit, onOpen: () -> Unit) {
    val due = TaskText.dueLine(task, now)
    val details = listOfNotNull(due?.text, task.estimateMinutes?.let { TaskText.minutes(it) })
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("task-${task.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(task, busy, onCheck)
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 60.dp)
                .clickable(role = Role.Button, onClickLabel = "Edit", onClick = onOpen)
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.title,
                    style = AuraType.bodyMedium,
                    color = if (task.done) AuraColors.gray else Color.Black,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (details.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        due?.let {
                            Text(it.text, style = AuraType.caption,
                                color = if (it.overdue && !task.done) AuraColors.red else AuraColors.gray, maxLines = 1)
                        }
                        if (due != null && task.estimateMinutes != null) {
                            Text("·", style = AuraType.caption, color = AuraColors.gray)
                        }
                        task.estimateMinutes?.let {
                            Text(TaskText.minutes(it), style = AuraType.caption, color = AuraColors.gray, maxLines = 1)
                        }
                    }
                }
            }
            if (task.flagged) {
                Icon(Icons.Filled.Flag, contentDescription = "Flagged", tint = AuraColors.orange,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
            if (task.priority != TaskPriority.none) PriorityMark(task.priority)
        }
    }
}

/** "!!!", "!!", "!" or a dot, in the priority's colour (Reminders' marks), described in words. */
@Composable
private fun PriorityMark(priority: TaskPriority) {
    val marks = when (priority) {
        TaskPriority.urgent -> "!!!"
        TaskPriority.high -> "!!"
        TaskPriority.medium -> "!"
        else -> "•"
    }
    val name = TaskText.priorityNames[priority] ?: priority.raw
    Text(
        marks,
        style = AuraType.calloutSemibold,
        color = taskPriorityColor(priority),
        modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "$name priority" },
    )
}

/** A 26dp ring that fills blue with a tick when done, inside a 44dp target. */
@Composable
private fun CheckCircle(task: TaskItem, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = !busy, role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = if (task.done) "Mark ${task.title} not done" else "Complete ${task.title}"
                stateDescription = if (task.done) "Done" else "Not done"
            }
            .testTag("task-check-${task.id}"),
        contentAlignment = Alignment.Center,
    ) {
        val ring = if (task.priority == TaskPriority.none || task.done) AuraColors.separator else taskPriorityColor(task.priority)
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .then(
                    if (task.done) Modifier.background(AuraColors.startBlue)
                    else Modifier.border(2.dp, ring, CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (task.done) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}
