package info.ibtaura.auraclarus.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.Habit
import info.ibtaura.aura.core.HabitDraft
import info.ibtaura.aura.core.HabitFrequency
import info.ibtaura.aura.core.HabitTargetType
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraConfirmDialog
import info.ibtaura.auraclarus.design.AuraMenuPicker
import info.ibtaura.auraclarus.design.AuraSheet
import info.ibtaura.auraclarus.design.AuraTextField
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.auraIcon

/** "0088FF" → a Compose colour; green when the hex is odd. */
internal fun habitColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    val value = clean.toLongOrNull(16) ?: return AuraColors.green
    return if (clean.length == 6) Color(0xFF000000 or value) else AuraColors.green
}

/** What today shows for one habit: how many check-offs, and whether that keeps the day. */
internal data class HabitToday(val count: Int, val done: Boolean)

/** One habit: today's check circle, the name and schedule, the streak and the goal. Tapping the text opens the editor. */
@Composable
internal fun HabitRow(
    habit: Habit,
    today: HabitToday,
    streak: Int,
    totalLogs: Int?,
    busy: Boolean,
    onCheck: () -> Unit,
    onOpen: () -> Unit,
) {
    val color = habitColor(habit.colorHex)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val checkLabel = when {
            habit.isPaused -> "Paused"
            today.done -> "Done today"
            habit.targetType == HabitTargetType.count -> "${today.count} of ${habit.targetCount} today"
            else -> "Not done today"
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(enabled = !busy && !habit.isPaused, role = Role.Checkbox, onClick = onCheck)
                .semantics {
                    contentDescription = "Check off ${habit.name}"
                    stateDescription = checkLabel
                }
                .testTag("habit-check-${habit.id}"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                auraIcon(if (today.done) "checkmark.circle.fill" else "circle"),
                contentDescription = null,
                tint = if (habit.isPaused) AuraColors.gray else color,
                modifier = Modifier.size(32.dp),
            )
        }
        Column(
            Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen).testTag("habit-row-${habit.id}"),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(auraIcon(habit.icon), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Text(
                    habit.name,
                    style = AuraType.bodyMedium,
                    color = if (habit.isPaused) AuraColors.metricGrey else Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(habitDetail(habit, today), style = AuraType.caption, color = AuraColors.metricGrey, maxLines = 2)
            if (habit.goal.enabled && totalLogs != null) {
                val goal = habit.goal.targetCount.coerceAtLeast(1)
                GoalBar(totalLogs.toDouble() / goal, color, Modifier.padding(top = 4.dp, end = 8.dp))
                Text("Goal: $totalLogs of $goal check-offs", style = AuraType.caption, color = AuraColors.metricGrey)
            }
        }
        if (streak > 0 && habit.showStreak) {
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(auraIcon("flame.fill"), contentDescription = null, tint = AuraColors.orange, modifier = Modifier.size(14.dp))
                    Text("$streak", style = AuraType.calloutSemibold, color = Color.Black)
                }
                Text(if (streak == 1) habit.streakUnit else "${habit.streakUnit}s", style = AuraType.caption, color = AuraColors.metricGrey)
            }
        }
    }
}

private fun habitDetail(habit: Habit, today: HabitToday): String {
    val target = when (habit.targetType) {
        HabitTargetType.count -> "${today.count} of ${habit.targetCount} today"
        HabitTargetType.minutes -> "${habit.targetMinutes} min"
        else -> null
    }
    val parts = listOfNotNull(
        if (habit.isPaused) "Paused" else null,
        habit.scheduleText,
        target,
        habit.cue.takeIf { it.isNotBlank() },
    )
    return parts.joinToString(" · ")
}

private val frequencyLabels = mapOf(
    HabitFrequency.daily to "Every day",
    HabitFrequency.specificDays to "Some days",
    HabitFrequency.perWeek to "Times a week",
    HabitFrequency.weekly to "Once a week",
)

private val targetLabels = mapOf(
    HabitTargetType.yesNo to "Done or not",
    HabitTargetType.count to "A number of times",
    HabitTargetType.minutes to "Minutes",
)

private val habitIcons = listOf("star.fill", "drop.fill", "figure.walk", "moon.fill", "leaf.fill", "heart.fill", "timer", "fork.knife")
private val habitColors = listOf("34C759", "0088FF", "FF9F0A", "FF3B30", "AF52DE", "5856D6")

/**
 * Add or edit a habit. `habit` null adds one. Save checks the draft first; Delete asks before it goes.
 */
@Composable
internal fun HabitEditorSheet(
    habit: Habit?,
    saving: Boolean,
    error: String?,
    onSave: (HabitDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var draft by remember(habit?.id) { mutableStateOf(habit?.let { HabitDraft(it) } ?: HabitDraft()) }
    var tried by remember(habit?.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val problem = draft.problem

    AuraSheet(onDismiss = onDismiss, dismissible = !saving) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(if (habit == null) "New habit" else "Edit habit", style = AuraType.heading, color = Color.Black)
            AuraCard(padding = 18.dp, spacing = 12.dp) {
                AuraTextField(
                    draft.name,
                    onValueChange = { draft = draft.copy(name = it.take(80)) },
                    placeholder = "Name, like Drink a glass of water",
                    modifier = Modifier.testTag("habit-name"),
                )
                AuraTextField(
                    draft.cue,
                    onValueChange = { draft = draft.copy(cue = it.take(120)) },
                    placeholder = "Cue (optional), like After I wake up",
                    modifier = Modifier.testTag("habit-cue"),
                )
                Text("Icon", style = AuraType.caption, color = AuraColors.metricGrey)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    habitIcons.forEach { symbol ->
                        val picked = draft.icon == symbol
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (picked) habitColor(draft.colorHex) else AuraColors.background)
                                .clickable(role = Role.RadioButton) { draft = draft.copy(icon = symbol) }
                                .semantics { contentDescription = symbol; stateDescription = if (picked) "Chosen" else "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                auraIcon(symbol),
                                contentDescription = null,
                                tint = if (picked) Color.White else AuraColors.metricGrey,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Text("Colour", style = AuraType.caption, color = AuraColors.metricGrey)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    habitColors.forEach { hex ->
                        val picked = draft.colorHex.equals(hex, ignoreCase = true)
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(habitColor(hex))
                                .clickable(role = Role.RadioButton) { draft = draft.copy(colorHex = hex) }
                                .semantics { contentDescription = "Colour $hex"; stateDescription = if (picked) "Chosen" else "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (picked) Icon(auraIcon("checkmark"), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            AuraCard(padding = 18.dp, spacing = 10.dp) {
                EditorLine("How often") {
                    AuraMenuPicker(
                        options = HabitFrequency.all,
                        selected = draft.frequency,
                        label = { frequencyLabels[it] ?: it },
                        onSelect = { draft = draft.copy(frequency = it) },
                        tag = "habit-frequency",
                    )
                }
                when (draft.frequency) {
                    HabitFrequency.specificDays -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (0..6).forEach { day ->
                            val on = day in draft.specificDays
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (on) AuraColors.blue else AuraColors.background)
                                    .clickable(role = Role.Checkbox) {
                                        draft = draft.copy(
                                            specificDays = if (on) draft.specificDays - day else (draft.specificDays + day).sorted(),
                                        )
                                    }
                                    .semantics {
                                        contentDescription = Habit.weekdayLong(day)
                                        stateDescription = if (on) "On" else "Off"
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(Habit.weekdayShort(day).take(1), style = AuraType.calloutMedium, color = if (on) Color.White else Color.Black)
                            }
                        }
                    }
                    HabitFrequency.perWeek -> CountLine("Times a week", draft.timesPerWeek, 1, 7, "habit-times") {
                        draft = draft.copy(timesPerWeek = it)
                    }
                    HabitFrequency.weekly -> EditorLine("On") {
                        AuraMenuPicker(
                            options = (0..6).toList(),
                            selected = draft.weeklyDay,
                            label = { Habit.weekdayLong(it) },
                            onSelect = { draft = draft.copy(weeklyDay = it) },
                            tag = "habit-weekday",
                        )
                    }
                    else -> Unit
                }
                CardDivider()
                EditorLine("Track") {
                    AuraMenuPicker(
                        options = targetLabels.keys.toList(),
                        selected = if (draft.targetType in targetLabels) draft.targetType else HabitTargetType.yesNo,
                        label = { targetLabels[it] ?: it },
                        onSelect = { draft = draft.copy(targetType = it) },
                        tag = "habit-target",
                    )
                }
                when (draft.targetType) {
                    HabitTargetType.count -> CountLine("Times a day", draft.targetCount, 1, 50, "habit-count") {
                        draft = draft.copy(targetCount = it)
                    }
                    HabitTargetType.minutes -> CountLine("Minutes", draft.targetMinutes, 5, 240, "habit-minutes", step = 5) {
                        draft = draft.copy(targetMinutes = it)
                    }
                    else -> Unit
                }
            }

            AuraCard(padding = 18.dp, spacing = 10.dp) {
                SwitchLine("A habit to break", draft.isNegative, "habit-negative") { draft = draft.copy(isNegative = it) }
                Text(
                    "Checking it off then means you avoided it today.",
                    style = AuraType.caption,
                    color = AuraColors.metricGrey,
                )
                CardDivider()
                SwitchLine("Goal", draft.goalEnabled, "habit-goal") { draft = draft.copy(goalEnabled = it) }
                if (draft.goalEnabled) {
                    CountLine("Check-offs", draft.goalTargetCount, 5, 365, "habit-goal-count", step = 5) {
                        draft = draft.copy(goalTargetCount = it)
                    }
                }
                if (habit != null) {
                    CardDivider()
                    SwitchLine("Paused", draft.isPaused, "habit-paused") { draft = draft.copy(isPaused = it) }
                }
            }

            val shown = error ?: if (tried) problem else null
            if (shown != null) Text(shown, style = AuraType.callout, color = AuraColors.red, modifier = Modifier.padding(horizontal = 20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(
                    "Cancel",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    style = PillStyle.Secondary,
                    fills = true,
                    enabled = !saving,
                )
                PillButton(
                    if (saving) "Saving…" else "Save",
                    onClick = {
                        tried = true
                        if (problem == null) onSave(draft)
                    },
                    modifier = Modifier.weight(1f).testTag("habit-save"),
                    fills = true,
                    enabled = !saving,
                )
            }
            if (onDelete != null) {
                Text(
                    "Delete habit",
                    style = AuraType.bodyMedium,
                    color = AuraColors.red,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(enabled = !saving, role = Role.Button) { confirmDelete = true }
                        .padding(12.dp)
                        .testTag("habit-delete"),
                )
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        AuraConfirmDialog(
            title = "Delete ${habit?.name ?: "this habit"}?",
            message = "Its check-offs and streak go too.",
            confirmTitle = "Delete",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
            confirmTag = "habit-delete-confirm",
        )
    }
}

@Composable
private fun EditorLine(title: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AuraType.body, color = Color.Black)
        Spacer(Modifier.weight(1f))
        content()
    }
}

@Composable
private fun CountLine(title: String, value: Int, min: Int, max: Int, tag: String, step: Int = 1, onChange: (Int) -> Unit) {
    EditorLine(title) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StepButton("minus", "Fewer", enabled = value > min, tag = "$tag-minus") { onChange((value - step).coerceAtLeast(min)) }
            Text("$value", style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.testTag("$tag-value"))
            StepButton("plus", "More", enabled = value < max, tag = "$tag-plus") { onChange((value + step).coerceAtMost(max)) }
        }
    }
}

@Composable
private fun SwitchLine(title: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    EditorLine(title) {
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AuraColors.green, checkedThumbColor = Color.White),
            modifier = Modifier.testTag(tag),
        )
    }
}
