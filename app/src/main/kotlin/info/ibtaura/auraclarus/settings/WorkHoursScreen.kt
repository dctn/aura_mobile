package info.ibtaura.auraclarus.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.WorkSchedule
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraMenuPicker
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class WeekDay(val number: Int, val letter: String, val name: String)

private val weekDays = listOf(
    WeekDay(1, "M", "Monday"),
    WeekDay(2, "T", "Tuesday"),
    WeekDay(3, "W", "Wednesday"),
    WeekDay(4, "T", "Thursday"),
    WeekDay(5, "F", "Friday"),
    WeekDay(6, "S", "Saturday"),
    WeekDay(7, "S", "Sunday"),
)
private val lunchLengths = listOf(30, 45, 60, 90)

/** 00:00 to 23:45 in 15-minute steps. */
private val quarters: List<String> = (0 until 96).map { WorkSchedule.clock(it * 15) }

/** "9:00" as "09:00", so a saved time matches a choice. */
private fun tidy(clock: String): String = WorkSchedule.minutes(clock)?.let { WorkSchedule.clock(it) } ?: clock

/** The quarter hours, plus a saved time that isn't on one, so the picker can show it. */
private fun timeChoices(including: String): List<String> =
    if (including in quarters) quarters else (quarters + including).sorted()

private fun lunchChoices(including: Int): List<Int> =
    if (including in lunchLengths) lunchLengths else (lunchLengths + including).sorted()

/**
 * The work and lunch hours the day zones are placed inside (Settings/WorkHoursForm.swift). A sheet on iOS, a pushed
 * page here, opened from Calendar and Settings with `nav.push(Route.WorkHours)`. Opens with `app.workSchedule`.
 */
@Composable
fun WorkHoursScreen(app: AppState, nav: AuraNav) {
    val saved = remember { app.workSchedule }
    var draft by remember {
        val start = saved ?: WorkSchedule.suggested
        mutableStateOf(
            start.copy(
                workdayStart = tidy(start.workdayStart),
                workdayEnd = tidy(start.workdayEnd),
                lunchStart = tidy(start.lunchStart),
            ),
        )
    }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val problem = draft.problem

    // `interactiveDismissDisabled(saving)`.
    BackHandler(enabled = saving) {}

    AuraPage {
        SectionTitle("Work hours", Modifier.padding(top = 8.dp))
        Text(
            "Your calendar is shaded inside these hours, with lunch as your main recovery.",
            style = AuraType.callout,
            color = AuraColors.gray,
        )

        AuraCard(padding = 20.dp, spacing = 0.dp) {
            TimeRow("Work starts", draft.workdayStart, "work-hours-start") { draft = draft.copy(workdayStart = it) }
            CardDivider()
            TimeRow("Work ends", draft.workdayEnd, "work-hours-end") { draft = draft.copy(workdayEnd = it) }
            CardDivider()
            TimeRow("Lunch starts", draft.lunchStart, "work-hours-lunch") { draft = draft.copy(lunchStart = it) }
            CardDivider()
            PickerRow("Lunch length") {
                AuraMenuPicker(
                    options = lunchChoices(draft.lunchMinutes),
                    selected = draft.lunchMinutes,
                    label = { "$it min" },
                    onSelect = { draft = draft.copy(lunchMinutes = it) },
                    modifier = Modifier.testTag("work-hours-lunch-length"),
                    tag = "work-hours-lunch-length",
                )
            }
        }

        AuraCard(padding = 20.dp, spacing = 14.dp) {
            Text("Work days", style = AuraType.bodyMedium, color = Color.Black)
            Row(Modifier.fillMaxWidth()) {
                weekDays.forEach { day ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        DayButton(day, on = day.number in draft.workDays) {
                            val days = draft.workDays
                            draft = draft.copy(
                                workDays = if (day.number in days) days - day.number else (days + day.number).sorted(),
                            )
                        }
                    }
                }
            }
        }

        if (problem != null) {
            Text(
                problem,
                style = AuraType.calloutSemibold,
                color = AuraColors.burnout,
                modifier = Modifier.testTag("work-hours-problem"),
            )
        }
        errorText?.let { Text(it, style = AuraType.callout, color = AuraColors.red) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                if (saved == null) "Later" else "Cancel",
                onClick = { nav.pop() },
                modifier = Modifier.weight(1f).testTag("work-hours-cancel"),
                style = PillStyle.Secondary,
                fills = true,
                enabled = !saving,
            )
            PillButton(
                if (saving) "Saving..." else "Save",
                onClick = {
                    val schedule = draft
                    saving = true
                    errorText = null
                    scope.launch {
                        try {
                            // Saving makes every screen load again through `dataVersion`.
                            app.saveWorkSchedule(schedule)
                            saving = false
                            nav.pop()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            saving = false
                            errorText = "Couldn't save. Try again."
                            app.note(e)
                        }
                    }
                },
                modifier = Modifier.weight(1f).testTag("work-hours-save"),
                fills = true,
                enabled = !saving && problem == null,
            )
        }
    }
}

@Composable
private fun PickerRow(title: String, picker: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = AuraType.bodyMedium, color = Color.Black, maxLines = 1, modifier = Modifier.weight(1f))
        picker()
    }
}

@Composable
private fun TimeRow(title: String, value: String, tag: String, onSelect: (String) -> Unit) {
    PickerRow(title) {
        AuraMenuPicker(
            options = timeChoices(value),
            selected = value,
            label = { it },
            onSelect = onSelect,
            modifier = Modifier.testTag(tag),
            tag = tag,
        )
    }
}

@Composable
private fun DayButton(day: WeekDay, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (on) AuraColors.blue else AuraColors.track)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = day.name
                selected = on
            }
            .testTag("work-day-${day.number}"),
        contentAlignment = Alignment.Center,
    ) {
        Text(day.letter, style = AuraType.system(16, FontWeight.SemiBold), color = if (on) Color.White else AuraColors.gray)
    }
}
