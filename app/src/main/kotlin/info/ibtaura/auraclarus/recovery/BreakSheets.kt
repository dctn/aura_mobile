package info.ibtaura.auraclarus.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.RecoveryOption
import info.ibtaura.aura.core.RecoveryType
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraSheet
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.home.BetaTimeField
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** What the break sheet asks for: the Recovery break mode, run under the type's name for this many minutes. */
data class RecoveryBreakChoice(val type: RecoveryType, val title: String, val minutes: Int, val note: String)

private val breakLengths = listOf(10, 15, 20, 30)

/** The Capacity service's values, in its order, for when it can't be reached (iOS `fallbackOptions`). */
private val fallbackOptions = listOf(
    RecoveryOption(id = "nap", title = RecoveryType.nap.label, minutes = 20, maxPoints = 10.0, fadesAfterMinutes = 120, note = ""),
    RecoveryOption(id = "walk", title = RecoveryType.walk.label, minutes = 20, maxPoints = 9.0, fadesAfterMinutes = 50, note = ""),
    RecoveryOption(id = "rest", title = RecoveryType.rest.label, minutes = 20, maxPoints = 5.0, fadesAfterMinutes = 40, note = ""),
    RecoveryOption(id = "outside", title = RecoveryType.outside.label, minutes = 20, maxPoints = 9.0, fadesAfterMinutes = 50, note = ""),
    RecoveryOption(id = "social", title = RecoveryType.social.label, minutes = 20, maxPoints = 5.0, fadesAfterMinutes = 40, note = ""),
)

/**
 * "Take a recovery break": pick a kind of break and a length, then Start. The kinds and their lifts come from the
 * Capacity service, or from the app's own copy of them when it can't be reached.
 *
 * Shows itself as an [AuraSheet] while it is composed: compose it while it should show. Start calls [onStart] with the
 * pick, then [onDismiss]; the caller starts the run after. Cancel and a swipe call [onDismiss] only.
 */
@Composable
fun RecoveryBreakSheet(app: AppState, onStart: (RecoveryBreakChoice) -> Unit, onDismiss: () -> Unit) {
    var options by remember { mutableStateOf<List<RecoveryOption>>(emptyList()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var minutes by rememberSaveable { mutableIntStateOf(20) }
    val selected = options.firstOrNull { it.id == selectedId }

    // Only kinds the app knows are listed, since the session must send one of them.
    LaunchedEffect(Unit) {
        var loaded: List<RecoveryOption> = emptyList()
        try {
            loaded = app.api.recoveryOptions(20)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
        }
        val known = loaded.filter { it.type != null }
        options = known.ifEmpty { fallbackOptions }
    }

    AuraSheet(onDismiss = onDismiss) {
        BreakSheetColumn {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Take a recovery break", Modifier.testTag("break-sheet"))
                Text(
                    "Pick what you'll do. It runs as a timed break and lifts your capacity.",
                    style = AuraType.callout, color = AuraColors.gray,
                )
            }
            if (options.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            } else {
                AuraCard(padding = 20.dp, spacing = 0.dp) {
                    options.forEachIndexed { index, option ->
                        if (index > 0) CardDivider()
                        BreakTypeRow(option, option.id == selectedId) { selectedId = option.id }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How long", style = AuraType.bodyMedium, color = Color.Black)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        breakLengths.forEach { length ->
                            BreakChip(
                                title = "$length min",
                                isSelected = length == minutes,
                                tag = "break-length-$length",
                                unselected = AuraColors.card,
                                horizontal = 14,
                            ) { minutes = length }
                        }
                    }
                }
                Text(
                    "Under 10 minutes gives no lift; the full lift comes at 20 minutes.",
                    style = AuraType.callout, color = AuraColors.gray,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        "Cancel", onClick = onDismiss, style = PillStyle.Secondary, fills = true,
                        modifier = Modifier.weight(1f).testTag("break-cancel"),
                    )
                    PillButton(
                        "Start",
                        onClick = {
                            val option = selected ?: return@PillButton
                            val type = option.type ?: return@PillButton
                            onStart(RecoveryBreakChoice(type, option.title, minutes, option.note))
                            onDismiss()
                        },
                        fills = true,
                        enabled = selected?.type != null,
                        modifier = Modifier.weight(1f).testTag("break-start"),
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakTypeRow(option: RecoveryOption, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = isSelected }
            .testTag("break-type-${option.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(option.title, style = AuraType.bodyMedium, color = Color.Black)
            Text(option.hint, style = AuraType.system(14), color = AuraColors.gray)
        }
        Icon(
            auraIcon(if (isSelected) "checkmark.circle.fill" else "circle"),
            contentDescription = null,
            tint = if (isSelected) AuraColors.blue else AuraColors.footnote,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * "Add a break I took": records a break from today or yesterday that wasn't started in the app. Shows itself as an
 * [AuraSheet] while composed; Cancel/Done, a swipe, or a moment after "Break added." call [onDismiss].
 */
@Composable
fun LogBreakSheet(app: AppState, onDismiss: () -> Unit) {
    val initial = remember { initialLogStart(app.flags.isUiTesting) }
    var breakType by rememberSaveable { mutableStateOf(RecoveryType.walk) }
    var isYesterday by rememberSaveable { mutableStateOf(initial.toLocalDate() != LocalDate.now()) }
    var startTime by rememberSaveable { mutableStateOf(initial.toLocalTime()) }
    var minutes by rememberSaveable { mutableIntStateOf(20) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var savedText by remember { mutableStateOf<String?>(null) }
    // One id for the sheet, so trying Save again resends the same break instead of adding a second.
    val logId = rememberSaveable { UUID.randomUUID().toString().lowercase() }
    val scope = rememberCoroutineScope()

    // An edit after a refusal clears the message, so the next Save is judged afresh.
    fun changed() {
        errorText = null
    }

    fun save() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val day = if (isYesterday) today.minusDays(1) else today
        val start: Instant = LocalDateTime.of(day, startTime).atZone(zone).toInstant()
        val picked = breakType
        val length = minutes
        saving = true
        errorText = null
        scope.launch {
            try {
                val logged = app.api.logBreak(id = logId, type = picked, startedAt = start, minutes = length)
                saving = false
                savedText = if (logged.created) "Break added." else "Already added."
                app.dataVersion += 1
                delay(1500)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                saving = false
                errorText = logBreakMessage(e)
                app.note(e)
            }
        }
    }

    AuraSheet(onDismiss = onDismiss, dismissible = !saving) {
        BreakSheetColumn {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Add a break I took", Modifier.testTag("log-break-sheet"))
                Text(
                    "A break from today or yesterday that you didn't start here. It counts towards your capacity.",
                    style = AuraType.callout, color = AuraColors.gray,
                )
            }
            AuraCard(padding = 20.dp, spacing = 14.dp) {
                Text("What you did", style = AuraType.bodyMedium, color = Color.Black)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecoveryType.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { choice ->
                                BreakChip(
                                    title = choice.label,
                                    isSelected = choice == breakType,
                                    tag = "log-type-${choice.raw}",
                                    unselected = AuraColors.track,
                                    horizontal = 12,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    breakType = choice
                                    changed()
                                }
                            }
                            if (row.size < 2) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            AuraCard(padding = 20.dp, spacing = 0.dp) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BreakChip("Today", !isYesterday, "log-day-today", AuraColors.track, 16) {
                        isYesterday = false
                        changed()
                    }
                    BreakChip("Yesterday", isYesterday, "log-day-yesterday", AuraColors.track, 16) {
                        isYesterday = true
                        changed()
                    }
                }
                CardDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Started", style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.weight(1f))
                    BetaTimeField("Started", startTime, "log-start-time") {
                        startTime = it
                        changed()
                    }
                }
                CardDivider()
                BreakStepper(minutes) {
                    minutes = it
                    changed()
                }
            }
            errorText?.let {
                Text(it, style = AuraType.callout, color = AuraColors.red, modifier = Modifier.testTag("log-error"))
            }
            savedText?.let {
                Text(it, style = AuraType.calloutSemibold, color = AuraColors.green, modifier = Modifier.testTag("log-saved"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    if (savedText == null) "Cancel" else "Done", onClick = onDismiss, style = PillStyle.Secondary,
                    fills = true, enabled = !saving, modifier = Modifier.weight(1f).testTag("log-cancel"),
                )
                PillButton(
                    if (saving) "Saving..." else "Save", onClick = { save() }, fills = true,
                    enabled = !saving && savedText == null, modifier = Modifier.weight(1f).testTag("log-save"),
                )
            }
        }
    }
}

/** UI tests: 09:15 today, which overlaps the stub's 09:00 to 09:50 focus session, so Save is refused. Else 20 minutes ago, down to the last 5. */
private fun initialLogStart(uiTesting: Boolean): LocalDateTime {
    val now = LocalDateTime.now()
    if (uiTesting) return now.toLocalDate().atTime(9, 15)
    val start = now.minusMinutes(20)
    return start.withMinute(start.minute / 5 * 5).withSecond(0).withNano(0)
}

/** The backend's refusals in plain words. */
private fun logBreakMessage(error: Throwable): String = when ((error as? AuraError)?.serverCode) {
    "log_too_old" -> "That's too long ago: breaks from today or yesterday only."
    "log_in_future" -> "That break hasn't ended yet."
    "log_overlaps" -> "That overlaps a session you already recorded."
    else -> "Couldn't save. Try again."
}

/** The sheet's scrolling page, padded as AuraPage. */
@Composable
private fun BreakSheetColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/** A capsule chip that turns blue when picked. */
@Composable
private fun BreakChip(
    title: String,
    isSelected: Boolean,
    tag: String,
    unselected: Color,
    horizontal: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(CircleShape)
            .background(if (isSelected) AuraColors.blue else unselected)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = isSelected }
            .testTag(tag)
            .padding(horizontal = horizontal.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = AuraType.system(15, if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (isSelected) Color.White else Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** SwiftUI's Stepper, 10 to 240 minutes in 5s: "How long", the minutes, then − and +. Tagged `log-minutes`. */
@Composable
private fun BreakStepper(minutes: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("log-minutes"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("How long", style = AuraType.bodyMedium, color = Color.Black)
        Spacer(Modifier.weight(1f))
        Text("$minutes min", style = AuraType.body, color = Color.Black)
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(AuraColors.track),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperHalf("minus", "Shorter", "log-minutes-decrement", minutes > 10) { onChange((minutes - 5).coerceAtLeast(10)) }
            Box(Modifier.width(1.dp).heightIn(min = 18.dp).background(AuraColors.separator))
            StepperHalf("plus", "Longer", "log-minutes-increment", minutes < 240) { onChange((minutes + 5).coerceAtMost(240)) }
        }
    }
}

@Composable
private fun StepperHalf(symbol: String, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (symbol == "plus") {
            Icon(auraIcon("plus"), null, tint = if (enabled) Color.Black else AuraColors.footnote, modifier = Modifier.size(20.dp))
        } else {
            Text("−", style = AuraType.system(22, FontWeight.Medium), color = if (enabled) Color.Black else AuraColors.footnote)
        }
    }
}
