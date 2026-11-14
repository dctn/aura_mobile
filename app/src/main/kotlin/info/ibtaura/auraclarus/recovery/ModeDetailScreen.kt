package info.ibtaura.auraclarus.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.RunKind
import info.ibtaura.aura.core.Shelf
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.GlassCircleButton
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.bottomPadding
import info.ibtaura.auraclarus.design.color
import info.ibtaura.auraclarus.design.hex
import info.ibtaura.auraclarus.design.symbol
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

// A port of Recovery/ModeDetailView.swift.

private val modeGrey = hex(0x9E9E9F)

private enum class ModeCalendarState(val line: String?) {
    Idle(null), Adding(null), Added("Added to your calendar."), AlreadyThere("Already in your calendar."),
    Failed("Couldn't add it. Try again."),
}

/**
 * Frames iPhone 18 Pro - 6 and - 7. `headline` is the Now card's title when opened from one, which also gives the
 * header that card's colours; otherwise the header takes the shelf's colour.
 */
@Composable
fun ModeDetailScreen(app: AppState, nav: AuraNav, route: Route.ModeDetail) {
    val mode = app.mode(route.modeId)
    Box(Modifier.fillMaxSize().background(AuraColors.background)) {
        if (mode != null) {
            ModeDetailBackdrop(mode, fromNowCard = route.headline != null)
            ModeDetailContent(app, mode, route.headline)
        }
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, top = 8.dp)) {
            GlassCircleButton("chevron.left", { nav.pop() }, Modifier.testTag("back"), contentDescription = "Back")
        }
    }
}

@Composable
private fun ModeDetailContent(app: AppState, mode: Mode, headline: String?) {
    var minutes by rememberSaveable(mode.id) { mutableStateOf(mode.minutes) }
    var sessionTitle by rememberSaveable(mode.id) { mutableStateOf("") }
    var calendarState by remember { mutableStateOf(ModeCalendarState.Idle) }
    val scope = rememberCoroutineScope()

    fun addToCalendar() {
        calendarState = ModeCalendarState.Adding
        scope.launch {
            // Frame 15: a plan mode goes on the calendar. The same title on that day already counts.
            val slot = mode.planSlot(Instant.now())
            calendarState = try {
                val existing = app.api.events(slot.start, slot.end)
                if (existing.any { it.title == mode.name }) {
                    ModeCalendarState.AlreadyThere
                } else {
                    app.api.addEvent(mode.name, slot.start, slot.end, mode.kind)
                    app.dataVersion += 1
                    ModeCalendarState.Added
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.note(e)
                ModeCalendarState.Failed
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = 44.dp + 94.dp, bottom = 24.dp + bottomPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Heading
        Icon(auraIcon(mode.shelf.symbol), contentDescription = null, tint = mode.shelf.color, modifier = Modifier.size(40.dp))
        Text(
            headline ?: mode.name,
            style = AuraType.system(22, FontWeight.Bold),
            color = Color.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 15.dp).widthIn(max = 200.dp).semantics { heading() },
        )

        // Length and the action
        Row(
            Modifier.padding(top = 12.dp).height(60.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeLengthPill(mode, minutes) { minutes = it }
            if (mode.run == RunKind.plan) {
                PillButton(
                    "Add to calendar",
                    onClick = { addToCalendar() },
                    modifier = Modifier.heightIn(min = 60.dp).testTag("mode-add-to-calendar"),
                    enabled = calendarState != ModeCalendarState.Adding,
                )
            } else {
                PillButton(
                    "Start",
                    onClick = { app.startRun(mode, minutes, sessionTitle) },
                    modifier = Modifier.heightIn(min = 60.dp).widthIn(min = 120.dp).testTag("mode-start"),
                )
            }
        }
        calendarState.line?.let {
            Text(it, style = AuraType.callout, color = modeGrey, modifier = Modifier.padding(top = 10.dp))
        }
        Text(
            mode.note,
            style = AuraType.system(16),
            color = modeGrey,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp).padding(top = 14.dp),
        )

        // Frame 01's rows: the breath pattern, and a title on the Focus shelf.
        val pattern = mode.pattern
        if (mode.run == RunKind.breathe && pattern != null) {
            ModeDetailRow("Breath", Modifier.padding(top = 15.dp)) {
                Text(pattern.line, style = AuraType.callout, color = modeGrey)
            }
        }
        if (mode.shelf == Shelf.focus) {
            ModeDetailRow("Title", Modifier.padding(top = 15.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (sessionTitle.isEmpty()) Text("Optional", style = AuraType.callout, color = AuraColors.secondary)
                    BasicTextField(
                        value = sessionTitle,
                        onValueChange = { sessionTitle = it },
                        singleLine = true,
                        textStyle = AuraType.callout.copy(color = Color.Black, textAlign = TextAlign.End),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Title" }
                            .testTag("mode-title"),
                    )
                }
            }
        }
        mode.evidence?.let { ModeWhyCard(it, Modifier.padding(top = 15.dp)) }
    }
}

/** The length pill: a menu of the mode's choices when it has some, else just the length. */
@Composable
private fun ModeLengthPill(mode: Mode, minutes: Int, onPick: (Int) -> Unit) {
    val choices = mode.choices.orEmpty()
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .height(60.dp)
                .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
                .clip(CircleShape)
                .background(AuraColors.glass)
                .then(
                    if (choices.isNotEmpty()) {
                        Modifier
                            .clickable(role = Role.DropdownList) { open = true }
                            .semantics {
                                contentDescription = "Length"
                                stateDescription = Mode.lengthText(minutes)
                            }
                            .testTag("mode-length")
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(Mode.lengthText(minutes), style = AuraType.system(19, FontWeight.Medium), color = Color.Black)
        }
        if (choices.isNotEmpty()) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { choice ->
                    val label = Mode.lengthText(choice)
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(18.dp)) {
                                    if (choice == minutes) {
                                        Icon(auraIcon("checkmark"), contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Text(label, style = AuraType.body, color = Color.Black)
                            }
                        },
                        onClick = {
                            open = false
                            onPick(choice)
                        },
                        modifier = Modifier.testTag("mode-length-option-$label"),
                    )
                }
            }
        }
    }
}

/** The soft header fading into the page, measured from frames - 6 and - 7. */
@Composable
private fun ModeDetailBackdrop(mode: Mode, fromNowCard: Boolean) {
    val tint = if (fromNowCard) {
        Brush.horizontalGradient(RecoveryCardStyle.of(mode).headerColors)
    } else {
        Brush.horizontalGradient(listOf(mode.shelf.color, mode.shelf.color))
    }
    // iOS masks the tint with black fading to clear; here the page colour is laid over it with the opposite alpha.
    val bg = AuraColors.background
    val fade = Brush.verticalGradient(
        0f to bg.copy(alpha = 0f),
        0.2f to bg.copy(alpha = 0.34f),
        0.33f to bg.copy(alpha = 0.52f),
        0.6f to bg.copy(alpha = 0.8f),
        0.73f to bg.copy(alpha = 0.9f),
        1f to bg,
    )
    Box(Modifier.fillMaxWidth().height(300.dp + 40.dp).background(tint)) {
        Box(Modifier.fillMaxSize().background(fade))
    }
}

@Composable
private fun ModeWhyCard(evidence: String, modifier: Modifier = Modifier) {
    AuraCard(modifier, padding = 22.dp, spacing = 12.dp) {
        Text("Why this Works", style = AuraType.system(22, FontWeight.Bold), color = Color.Black)
        Text(
            evidence,
            style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            color = modeGrey,
        )
    }
}

@Composable
private fun ModeDetailRow(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    AuraCard(modifier, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AuraType.callout, color = Color.Black)
            Spacer(Modifier.size(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { trailing() }
        }
    }
}
