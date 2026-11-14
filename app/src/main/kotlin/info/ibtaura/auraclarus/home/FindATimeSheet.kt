package info.ibtaura.auraclarus.home

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.DayPlan
import info.ibtaura.aura.core.PLANNER_DEFAULT_MINUTES
import info.ibtaura.aura.core.PlanBlock
import info.ibtaura.aura.core.QuickCheckResult
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.aura.core.UnplacedTask
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.ZoneText
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.bottomPadding
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Find a time (iOS FindATimeSheet), a pushed page here. A row of day chips picks today or one of the next six days;
 * the page first shows a preview of that day's plan, which writes nothing, and "Add to calendar" runs it for real.
 * Today may want the Quick check first (409 `quick_check_required`): "Do the Quick check" opens the cover, "Plan anyway"
 * plans without it. On confirm: `app.dataVersion += 1`, "Added N blocks" for a moment, then `nav.pop()`.
 *
 * Auto-allocation: the open tasks marked Auto-schedule are listed with a tick each, ticked per `route.taskIds` (one
 * task from its Schedule) or all (Plan my day, Calendar). The preview and Add to calendar both send the ticked tasks,
 * so what is previewed is what is added. With none marked, the page says how to mark one and links to Tasks.
 */
@Composable
fun FindATimeSheet(app: AppState, nav: AuraNav, route: Route.FindATime) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<FindATimePhase>(FindATimePhase.Loading) }
    /** The tasks, once loaded. Null while loading or when they couldn't load. */
    var tasks by remember { mutableStateOf<List<TaskItem>?>(null) }
    /** False until the task load has finished, either way. Nothing is previewed before. */
    var tasksTried by remember { mutableStateOf(false) }
    /** The ticked task ids. */
    var selected by remember { mutableStateOf(emptySet<String>()) }
    /** Sent with the preview and again with the confirm, so both plan the same way. */
    var skipCheck by remember { mutableStateOf(false) }
    /** Bumped to run the preview again. */
    var attempt by remember { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf(false) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    /** How many blocks Add to calendar wrote; set, the page says so and closes. */
    var added by remember { mutableStateOf<Int?>(null) }
    /** The day being previewed and confirmed, as a DayKey. */
    var selectedDate by remember { mutableStateOf(route.date) }

    val choices = remember { dayChoices() }
    val selectedOffset = choices.firstOrNull { it.key == selectedDate }?.offset
    val dayPhrase = when (selectedOffset) {
        0 -> "today"
        1 -> "tomorrow"
        else -> DayKey.localDate(selectedDate)?.let { longDay(it) } ?: selectedDate
    }
    val titles = remember(tasks) { tasks?.let { titleMap(it) }.orEmpty() }
    val candidates = remember(tasks) { tasks?.let { TaskPlanning.candidates(it) } }
    // Without the task list, the tasks asked for (null: every marked task, the Planner's own choice).
    val requestIds: List<String>? = candidates?.let { TaskPlanning.requestIds(it, selected) }
        ?: route.taskIds?.takeIf { it.isNotEmpty() }
    val noTasks = candidates?.isEmpty() == true
    val noneTicked = candidates?.isNotEmpty() == true && selected.isEmpty()

    // The tasks come first: they decide what's ticked and give the rows their titles. The list Tasks just showed is
    // used as it is (TasksMemo), so Plan my day and Schedule go straight to the preview; else it loads.
    LaunchedEffect(Unit) {
        try {
            val loaded = TasksMemo.tasks(app) ?: app.api.tasks()
            tasks = loaded
            selected = TaskPlanning.initialSelection(TaskPlanning.candidates(loaded), route.taskIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
        } finally {
            tasksTried = true
        }
    }

    // A dry run for the day and the ticked tasks. A new day or a new tick runs it again.
    LaunchedEffect(tasksTried, selectedDate, attempt, requestIds) {
        if (!tasksTried) return@LaunchedEffect
        confirmError = null
        if (noTasks || noneTicked) {
            phase = FindATimePhase.Idle
            return@LaunchedEffect
        }
        phase = FindATimePhase.Loading
        try {
            phase = FindATimePhase.Preview(
                app.api.allocate(date = selectedDate, dryRun = true, skipCheck = skipCheck, taskIds = requestIds),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            // The Quick check answer opens the check panel; anything else is "Couldn't plan your day."
            phase = if ((error as? AuraError)?.serverCode == QUICK_CHECK_CODE) {
                FindATimePhase.NeedsCheck
            } else {
                app.note(error)
                FindATimePhase.Failed
            }
        }
    }

    // "Added N blocks" stays a moment, then the page closes.
    LaunchedEffect(added) {
        if (added == null) return@LaunchedEffect
        delay(ADDED_MILLIS)
        nav.pop()
    }

    // The page can't be left while the plan is being added (iOS interactiveDismissDisabled).
    BackHandler(enabled = confirming || added != null) {}

    val needsCheck = phase == FindATimePhase.NeedsCheck
    val busy = confirming || added != null
    val canConfirm = !busy && (phase as? FindATimePhase.Preview)?.plan?.placed?.isNotEmpty() == true

    // A new day runs its own preview. The old one goes at once, so "Add to calendar" can't confirm it for the new day.
    val select: (String) -> Unit = { key ->
        if (key != selectedDate && !busy) {
            selectedDate = key
            confirmError = null
            phase = FindATimePhase.Loading
        }
    }

    // Ticking or unticking a task drops the old preview at once, for the same reason.
    val tick: (String) -> Unit = { id ->
        if (!busy) {
            selected = if (id in selected) selected - id else selected + id
            confirmError = null
            phase = FindATimePhase.Loading
        }
    }

    // Plan my day and Schedule came from Tasks, so back is there; Calendar's Find a time opens Tasks.
    val goToTasks: () -> Unit = {
        nav.pop()
        if (route.taskIds == null) nav.push(Route.Tasks)
    }

    // A check that counts lets the Planner use it, so the preview runs again with it. Stopping early or a check
    // that didn't count leaves the Quick check panel as it was.
    val quickCheckEnded: (QuickCheckResult?) -> Unit = { result ->
        if (result != null && result.valid) {
            skipCheck = false
            attempt += 1
        }
    }

    // The real run for the selected day and tasks, with the same skipCheck as the preview. The page stays open if it
    // fails.
    val confirm: () -> Unit = {
        val day = selectedDate
        val skip = skipCheck
        val ids = requestIds
        confirming = true
        confirmError = null
        scope.launch {
            try {
                val plan = app.api.allocate(date = day, dryRun = false, skipCheck = skip, taskIds = ids)
                confirming = false
                app.dataVersion += 1
                added = plan.placed.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                confirming = false
                if ((e as? AuraError)?.serverCode == QUICK_CHECK_CODE) {
                    phase = FindATimePhase.NeedsCheck
                } else {
                    app.note(e)
                    confirmError = "Couldn't add the plan. Try again."
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AuraColors.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            FindATimeHeader(dayPhrase)
            FindATimeDayRow(choices, selectedDate, enabled = !busy, onSelect = select)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (!candidates.isNullOrEmpty()) {
                    FindATimeTaskList(candidates, selected, enabled = !busy, onTick = tick)
                }
                when {
                    !tasksTried -> FindATimeSpinner()
                    noTasks -> FindATimeNoTasks(goToTasks)
                    noneTicked -> Text(
                        "Tick a task to plan it.",
                        style = AuraType.system(17, FontWeight.SemiBold),
                        color = Color.Black,
                        modifier = Modifier.testTag("find-time-none-ticked"),
                    )
                    else -> when (val current = phase) {
                        FindATimePhase.Loading, FindATimePhase.Idle -> FindATimeSpinner()
                        is FindATimePhase.Preview -> FindATimePreview(
                            plan = current.plan,
                            emptyText = emptyText(dayPhrase, selectedOffset),
                            title = { id -> titleFor(titles, id) },
                        )
                        FindATimePhase.NeedsCheck -> FindATimeCheckPanel()
                        FindATimePhase.Failed -> AuraCard(padding = 20.dp) {
                            FindATimeRetryLine("Couldn't plan your day.") { attempt += 1 }
                        }
                    }
                }
            }
        }

        // The buttons stay at the bottom, above the tab bar.
        Box(
            Modifier
                .fillMaxWidth()
                .background(AuraColors.background)
                .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 12.dp + bottomPadding()),
        ) {
            val count = added
            if (count != null) {
                Text(
                    if (count == 1) "Added 1 block" else "Added $count blocks",
                    style = AuraType.system(17, FontWeight.SemiBold),
                    color = AuraColors.green,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("find-time-added"),
                )
            } else if (needsCheck) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        "Do the Quick check",
                        onClick = { nav.openQuickCheck(quickCheckEnded) },
                        fills = true,
                        modifier = Modifier.testTag("find-time-do-check"),
                    )
                    PillButton(
                        "Plan anyway",
                        onClick = {
                            // Plans without the Quick check, and keeps that for the confirm.
                            skipCheck = true
                            attempt += 1
                        },
                        style = PillStyle.Secondary,
                        fills = true,
                        modifier = Modifier.testTag("find-time-plan-anyway"),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                            .clickable(role = Role.Button) { nav.pop() }
                            .testTag("find-time-not-now"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Not now", style = AuraType.bodyMedium, color = AuraColors.blue)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    confirmError?.let {
                        Text(
                            it,
                            style = AuraType.callout,
                            color = AuraColors.red,
                            modifier = Modifier.fillMaxWidth().testTag("find-time-error"),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(
                            "Cancel",
                            onClick = { nav.pop() },
                            style = PillStyle.Secondary,
                            fills = true,
                            enabled = !busy,
                            modifier = Modifier.weight(1f).testTag("find-time-cancel"),
                        )
                        PillButton(
                            if (confirming) "Adding..." else "Add to calendar",
                            onClick = confirm,
                            fills = true,
                            enabled = canConfirm,
                            modifier = Modifier.weight(1f).testTag("find-time-confirm"),
                        )
                    }
                }
            }
        }
    }
}

/** How long "Added N blocks" shows before the page closes. */
private const val ADDED_MILLIS = 300L

private const val QUICK_CHECK_CODE = "quick_check_required"

/** Today plus the next six days. */
private const val DAY_COUNT = 7

private sealed interface FindATimePhase {
    data object Loading : FindATimePhase
    /** Nothing to preview: no task is marked Auto-schedule, or none is ticked. */
    data object Idle : FindATimePhase
    data class Preview(val plan: DayPlan) : FindATimePhase
    /** The Planner wants today's Quick check first (409 `quick_check_required`). */
    data object NeedsCheck : FindATimePhase
    data object Failed : FindATimePhase
}

/** One chip in the day row. `offset` is days after today (0 is today); `key` the DayKey sent to the Planner. */
private data class FindATimeDay(val offset: Int, val key: String, val day: LocalDate)

private fun dayChoices(): List<FindATimeDay> {
    val today = LocalDate.now(DayKey.zone)
    return (0 until DAY_COUNT).map { offset ->
        val day = today.plusDays(offset.toLong())
        FindATimeDay(offset, DayKey.string(day), day)
    }
}

/** "Wed 11", in the phone's own order and words. */
private fun chipDay(day: LocalDate): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEd"), locale).format(day)
}

/** "Wednesday, 11 Nov", in the phone's own order and words. */
private fun longDay(day: LocalDate): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMM"), locale).format(day)
}

private fun chipTitle(choice: FindATimeDay): String = when (choice.offset) {
    0 -> "Today"
    1 -> "Tomorrow"
    else -> chipDay(choice.day)
}

/** "Nothing to place today.", "... tomorrow." or "... on Wednesday, 11 Nov." */
private fun emptyText(phrase: String, offset: Int?): String {
    val needsOn = offset?.let { it > 1 } ?: true
    return if (needsOn) "Nothing to place on $phrase." else "Nothing to place $phrase."
}

private fun titleMap(tasks: List<TaskItem>): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    for (task in tasks) if (task.id !in map) map[task.id] = task.title
    return map
}

private fun titleFor(titles: Map<String, String>, taskId: String?): String {
    val title = taskId?.let { titles[it] }
    return if (title.isNullOrEmpty()) "Task" else title
}

@Composable
private fun FindATimeHeader(dayPhrase: String) {
    Column(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionTitle("Find a time", Modifier.testTag("find-time-sheet"))
        Text(
            "A preview of $dayPhrase. Nothing is added until you confirm.",
            style = AuraType.callout,
            color = AuraColors.gray,
        )
    }
}

/**
 * Today, Tomorrow, then "Wed 11" and on: seven chips that scroll sideways when they don't fit, edge to edge. It stays
 * above the Quick check panel too, so another day can be picked from there.
 */
@Composable
private fun FindATimeDayRow(choices: List<FindATimeDay>, selectedKey: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (choice in choices) {
            val isSelected = choice.key == selectedKey
            // TalkBack reads the whole date for the later days.
            val spoken = if (choice.offset < 2) chipTitle(choice) else longDay(choice.day)
            Box(
                Modifier
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) AuraColors.blue else AuraColors.card)
                    .clickable(enabled = enabled, role = Role.Button) { onSelect(choice.key) }
                    .semantics {
                        contentDescription = spoken
                        selected = isSelected
                    }
                    .testTag("find-time-day-${choice.offset}")
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    chipTitle(choice),
                    style = AuraType.system(15, if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (isSelected) Color.White else Color.Black,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.FindATimePreview(plan: DayPlan, emptyText: String, title: (String?) -> String) {
    val blocks = plan.placed.sortedBy { it.start }
    if (blocks.isEmpty()) {
        Text(
            emptyText,
            style = AuraType.system(17, FontWeight.SemiBold),
            color = Color.Black,
            modifier = Modifier.testTag("find-time-empty"),
        )
    } else {
        AuraCard(padding = 14.dp, spacing = 10.dp) {
            for (block in blocks) FindATimeBlockRow(block, title(block.taskId))
        }
    }
    plan.warnings.note?.let { FindATimeWarningCard(it) }
    if (plan.unplaced.isNotEmpty()) FindATimeUnplaced(plan.unplaced, title)
}

/** One block: time, title and the Planner's reason, on a blue tint for work or green for a rest. */
@Composable
private fun FindATimeBlockRow(block: PlanBlock, taskTitle: String) {
    val color = if (block.isRecovery) AuraColors.green else AuraColors.blue
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            // The 4 dp bar down the leading edge.
            .drawBehind { drawRect(color, size = Size(4.dp.toPx(), size.height)) }
            .semantics(mergeDescendants = true) {}
            .testTag("find-time-block"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                ZoneText.range(block.start, block.end),
                style = AuraType.caption.copy(fontFeatureSettings = "tnum"),
                color = AuraColors.gray,
            )
            Text(block.title(taskTitle), style = AuraType.system(16, FontWeight.Medium), color = Color.Black)
            if (block.isRecovery) {
                Text("Rest, not work", style = AuraType.system(13, FontWeight.SemiBold), color = AuraColors.green)
            }
            if (block.reason.isNotEmpty()) {
                Text(block.reason, style = AuraType.caption, color = AuraColors.gray)
            }
        }
    }
}

@Composable
private fun FindATimeWarningCard(note: String) {
    AuraCard(padding = 20.dp, modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("find-time-warning")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(auraIcon("info.circle.fill"), contentDescription = null, tint = AuraColors.blue, modifier = Modifier.size(22.dp))
            Text(note, style = AuraType.callout, color = Color.Black)
        }
    }
}

/** "Didn't fit": the tasks the Planner couldn't place, with its sentence for each (iOS ZoneCardSection). */
@Composable
private fun FindATimeUnplaced(tasks: List<UnplacedTask>, title: (String?) -> String) {
    Column(Modifier.fillMaxWidth().testTag("find-time-unplaced"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Didn't fit", style = AuraType.heading, color = Color.Black, modifier = Modifier.semantics { heading() })
        AuraCard(padding = 20.dp, spacing = 12.dp) {
            tasks.forEachIndexed { index, task ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(AuraColors.separator))
                Column(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title(task.taskId), style = AuraType.system(16, FontWeight.Medium), color = Color.Black)
                    val words = TaskPlanning.unplacedWords(task.reason, task.detail)
                    Text(words, style = AuraType.system(13, FontWeight.SemiBold), color = AuraColors.orange)
                    val detail = task.detail.trim()
                    if (detail.isNotEmpty() && detail.removeSuffix(".") != words) {
                        Text(detail, style = AuraType.caption, color = AuraColors.gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun FindATimeSpinner() {
    Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
    }
}

/** "Tasks to place": each marked task with a tick; the ticked ones are previewed and added. */
@Composable
private fun FindATimeTaskList(tasks: List<TaskItem>, selected: Set<String>, enabled: Boolean, onTick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("find-time-tasks"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Tasks to place", style = AuraType.heading, color = Color.Black, modifier = Modifier.semantics { heading() })
        AuraCard(padding = 0.dp, spacing = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                tasks.forEachIndexed { index, task ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(AuraColors.separator))
                    FindATimeTaskRow(task, task.id in selected, enabled) { onTick(task.id) }
                }
            }
        }
    }
}

/** A tick, the title and its length (Aura plans 30 min when it has none). */
@Composable
private fun FindATimeTaskRow(task: TaskItem, ticked: Boolean, enabled: Boolean, onTick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = ticked, enabled = enabled, role = Role.Checkbox, onValueChange = { onTick() })
            .semantics(mergeDescendants = true) { stateDescription = if (ticked) "Ticked" else "Not ticked" }
            .testTag("find-time-task-${task.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (ticked) Modifier.background(AuraColors.startBlue)
                    else Modifier.border(2.dp, AuraColors.separator, CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (ticked) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            task.title,
            style = AuraType.bodyMedium,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            TaskText.minutes(task.estimateMinutes ?: PLANNER_DEFAULT_MINUTES),
            style = AuraType.caption,
            color = AuraColors.gray,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** No open task is marked Auto-schedule: how to mark one, and the way to Tasks. */
@Composable
private fun FindATimeNoTasks(goToTasks: () -> Unit) {
    AuraCard(Modifier.testTag("find-time-no-tasks"), padding = 20.dp, spacing = 10.dp) {
        Text("No tasks to place", style = AuraType.system(17, FontWeight.SemiBold), color = Color.Black,
            modifier = Modifier.semantics { heading() })
        Text(
            "Turn on Auto-schedule for a task, and Aura finds a time for it when you plan your day.",
            style = AuraType.callout,
            color = AuraColors.gray,
        )
        PillButton("Go to Tasks", goToTasks, Modifier.testTag("find-time-go-tasks"), style = PillStyle.Secondary)
    }
}

@Composable
private fun FindATimeCheckPanel() {
    AuraCard(padding = 20.dp, spacing = 6.dp) {
        Text(
            "Take today's Quick check first",
            style = AuraType.system(17, FontWeight.SemiBold),
            color = Color.Black,
            modifier = Modifier.semantics { heading() }.testTag("find-time-check"),
        )
        Text(
            "Planning today uses this morning's Quick check, the 45-second tap test. You can do it now, or plan without it.",
            style = AuraType.callout,
            color = AuraColors.gray,
        )
    }
}

/** "Couldn't plan your day. Try again" (iOS HomeRetryLine). */
@Composable
private fun FindATimeRetryLine(text: String, onRetry: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = AuraType.callout, color = AuraColors.footnote)
        Text(
            "Try again",
            style = AuraType.calloutMedium,
            color = AuraColors.blue,
            modifier = Modifier.clickable(role = Role.Button, onClick = onRetry),
        )
    }
}
