package info.ibtaura.auraclarus.home

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.DayPlan
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Find a time (iOS FindATimeSheet), a pushed page here. A row of day chips picks today or one of the next six days;
 * the page first shows a preview of that day's plan, which writes nothing, and "Add to calendar" runs it for real.
 * Today may want the Quick check first (409 `quick_check_required`): "Do the Quick check" opens the cover, "Plan anyway"
 * plans without it. On confirm: `app.dataVersion += 1`, then `nav.pop()`.
 */
@Composable
fun FindATimeSheet(app: AppState, nav: AuraNav, route: Route.FindATime) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<FindATimePhase>(FindATimePhase.Loading) }
    /** Task titles by id, for the rows. Empty when the tasks couldn't load; the rows then say "Task". */
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    /** Sent with the preview and again with the confirm, so both plan the same way. */
    var skipCheck by remember { mutableStateOf(false) }
    /** Bumped to run the preview again. */
    var attempt by remember { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf(false) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    /** The day being previewed and confirmed, as a DayKey. */
    var selectedDate by remember { mutableStateOf(route.date) }

    val choices = remember { dayChoices() }
    val selectedOffset = choices.firstOrNull { it.key == selectedDate }?.offset
    val dayPhrase = when (selectedOffset) {
        0 -> "today"
        1 -> "tomorrow"
        else -> DayKey.localDate(selectedDate)?.let { longDay(it) } ?: selectedDate
    }

    // The tasks (for titles) and a dry run load together. Only the dry run can fail the page.
    LaunchedEffect(selectedDate, attempt) {
        phase = FindATimePhase.Loading
        confirmError = null
        val day = selectedDate
        val skip = skipCheck
        val (loadedTasks, planResult) = coroutineScope {
            val tasksCall = async {
                try {
                    app.api.tasks()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
            }
            val planCall = async {
                try {
                    Result.success(app.api.allocate(date = day, dryRun = true, skipCheck = skip, taskIds = null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure<DayPlan>(e)
                }
            }
            tasksCall.await() to planCall.await()
        }
        if (loadedTasks != null) titles = titleMap(loadedTasks)
        planResult.fold(
            onSuccess = { phase = FindATimePhase.Preview(it) },
            onFailure = { error ->
                // The Quick check answer opens the check panel; anything else is "Couldn't plan your day."
                phase = if ((error as? AuraError)?.serverCode == QUICK_CHECK_CODE) {
                    FindATimePhase.NeedsCheck
                } else {
                    app.note(error)
                    FindATimePhase.Failed
                }
            },
        )
    }

    // The page can't be left while the plan is being added (iOS interactiveDismissDisabled).
    BackHandler(enabled = confirming) {}

    val needsCheck = phase == FindATimePhase.NeedsCheck
    val canConfirm = !confirming && (phase as? FindATimePhase.Preview)?.plan?.placed?.isNotEmpty() == true

    // A new day runs its own preview. The old one goes at once, so "Add to calendar" can't confirm it for the new day.
    val select: (String) -> Unit = { key ->
        if (key != selectedDate && !confirming) {
            selectedDate = key
            confirmError = null
            phase = FindATimePhase.Loading
        }
    }

    // A check that counts lets the Planner use it, so the preview runs again with it. Stopping early or a check
    // that didn't count leaves the Quick check panel as it was.
    val quickCheckEnded: (QuickCheckResult?) -> Unit = { result ->
        if (result != null && result.valid) {
            skipCheck = false
            attempt += 1
        }
    }

    // The real run for the selected day, with the same skipCheck as the preview. The page stays open if it fails.
    val confirm: () -> Unit = {
        val day = selectedDate
        val skip = skipCheck
        confirming = true
        confirmError = null
        scope.launch {
            try {
                app.api.allocate(date = day, dryRun = false, skipCheck = skip, taskIds = null)
                confirming = false
                app.dataVersion += 1
                nav.pop()
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
            FindATimeDayRow(choices, selectedDate, enabled = !confirming, onSelect = select)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when (val current = phase) {
                    FindATimePhase.Loading -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                    }
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

        // The buttons stay at the bottom, above the tab bar.
        Box(
            Modifier
                .fillMaxWidth()
                .background(AuraColors.background)
                .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 12.dp + bottomPadding()),
        ) {
            if (needsCheck) {
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
                            enabled = !confirming,
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

private const val QUICK_CHECK_CODE = "quick_check_required"

/** Today plus the next six days. */
private const val DAY_COUNT = 7

private sealed interface FindATimePhase {
    data object Loading : FindATimePhase
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
                    Text(task.detail, style = AuraType.caption, color = AuraColors.gray)
                }
            }
        }
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
            "Planning today uses this morning's Quick check, the 3-minute tap test. You can do it now, or plan without it.",
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
