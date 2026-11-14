package info.ibtaura.auraclarus.run

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.ibtaura.aura.core.BreathPhase
import info.ibtaura.aura.core.RunEngine
import info.ibtaura.aura.core.RunKind
import info.ibtaura.aura.core.RunSnapshot
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import java.time.Instant

// The middle of the run screen (iOS RunCenter and its pieces), and the 3-2-1.

@Composable
internal fun RunCenter(
    run: ActiveRun,
    snapshot: RunSnapshot,
    now: Instant,
    tomorrowText: String,
    onTomorrowChange: (String) -> Unit,
    onListFocus: (Boolean) -> Unit,
) {
    val accent = run.mode.runAccent
    val isNight = run.mode.night == true
    val clockText = RunEngine.clock(snapshot.remaining ?: snapshot.elapsed)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RunHeading(run.mode.kicker(run.minutes), run.title, accent, isNight)
        when (run.mode.run) {
            // Frame 07.
            RunKind.breathe -> {
                val phase = BreathPhase.at(snapshot.elapsed, run.mode.pattern)
                RunBreathCircle(phase.scale, accent, Modifier.padding(top = 26.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(phase.word.raw) }
                        append(" ")
                        withStyle(SpanStyle(color = accent.copy(alpha = 0.7f))) { append(phase.left.toString()) }
                    },
                    style = AuraType.callout,
                    color = accent,
                    modifier = Modifier.padding(top = 8.dp),
                )
                RunClockText(clockText, 44, false, Modifier.padding(top = 6.dp))
                RunNoteText(run.mode.note, false, Modifier.padding(top = 8.dp))
                RunProgressLine(snapshot.progress, accent, false, Modifier.padding(top = 22.dp))
            }
            // Frame 09: one prompt every length / 6.
            RunKind.guided -> {
                val prompts = run.mode.prompts ?: emptyList()
                val index = run.engine.promptIndex(now, prompts.size)
                RunClockText(clockText, 72, false, Modifier.padding(top = 8.dp))
                RunNoteText(run.mode.note, false, Modifier.padding(top = 8.dp))
                RunProgressLine(snapshot.progress, accent, false, Modifier.padding(top = 22.dp))
                if (index < prompts.size) {
                    Text(
                        "${index + 1} OF ${prompts.size}",
                        style = kickerStyle,
                        color = accent,
                        modifier = Modifier.padding(top = 28.dp),
                    )
                    Text(
                        prompts[index],
                        style = AuraType.callout,
                        color = RunLook.ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 8.dp),
                    )
                }
            }
            // Frame 11.
            RunKind.winddown -> {
                RunClockText(clockText, 72, false, Modifier.padding(top = 8.dp))
                RunNoteText(run.mode.note, false, Modifier.padding(top = 8.dp))
                RunProgressLine(snapshot.progress, accent, false, Modifier.padding(top = 22.dp))
                RunTomorrowList(tomorrowText, onTomorrowChange, onListFocus, Modifier.padding(top = 28.dp))
            }
            // Frames 03, 04, 05, 06 and 10.
            RunKind.timer, RunKind.flow, RunKind.plan -> {
                RunClockText(clockText, 72, isNight, Modifier.padding(top = 8.dp))
                RunNoteText(run.mode.note, isNight, Modifier.padding(top = 8.dp))
                RunProgressLine(snapshot.progress, accent, isNight, Modifier.padding(top = 24.dp))
            }
        }
    }
}

private val kickerStyle = AuraType.system(11, FontWeight.Bold).copy(letterSpacing = 0.6.sp)

@Composable
private fun RunHeading(kicker: String, title: String, accent: Color, isNight: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(kicker, style = kickerStyle, color = accent)
        Text(
            title,
            style = AuraType.system(22, FontWeight.SemiBold),
            color = if (isNight) Color.White else RunLook.ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 30.dp),
        )
    }
}

@Composable
private fun RunClockText(text: String, size: Int, isNight: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TextStyle(fontSize = size.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
        color = if (isNight) Color.White else RunLook.ink,
        maxLines = 1,
        modifier = modifier.testTag("run-clock"),
    )
}

@Composable
private fun RunNoteText(text: String, isNight: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        style = AuraType.callout,
        color = if (isNight) RunLook.nightDim else RunLook.note,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 50.dp),
    )
}

/** Shows nothing for an open-ended run (frame 04). */
@Composable
private fun RunProgressLine(progress: Double?, accent: Color, isNight: Boolean, modifier: Modifier = Modifier) {
    if (progress == null) return
    val width = 282.dp
    Box(modifier.width(width).height(4.dp).background(if (isNight) RunLook.nightButton else RunLook.track, CircleShape)) {
        Box(
            Modifier
                .width(maxOf(4.dp, width * progress.toFloat()))
                .height(4.dp)
                .background(accent, CircleShape),
        )
    }
}

/** Frames 07 and 08: the inner circle eases between 62% and 100% of the halo. */
@Composable
private fun RunBreathCircle(scale: Double, accent: Color, modifier: Modifier = Modifier, diameter: Dp = 222.dp) {
    Canvas(modifier.size(diameter).clearAndSetSemantics {}) {
        val outer = size.minDimension / 2
        drawCircle(accent.copy(alpha = 0.1f), radius = outer)
        val inner = outer * scale.toFloat()
        drawCircle(accent.copy(alpha = 0.2f), radius = inner)
        drawCircle(accent, radius = inner, style = Stroke(width = 2.5.dp.toPx()))
        drawCircle(accent.copy(alpha = 0.7f), radius = 7.dp.toPx())
    }
}

/** Frame 11's "For tomorrow". Each line becomes a task when the run finishes. */
@Composable
private fun RunTomorrowList(
    text: String,
    onChange: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("FOR TOMORROW", style = AuraType.system(12), color = RunLook.note)
        Box(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .background(AuraColors.card, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (text.isEmpty()) {
                Text("Reply to Ana\nBook the dentist", style = AuraType.callout, color = RunLook.note)
            }
            BasicTextField(
                value = text,
                onValueChange = onChange,
                textStyle = AuraType.callout.copy(color = Color.Black),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { onFocus(it.isFocused) }
                    .semantics { contentDescription = "For tomorrow" }
                    .testTag("run-tomorrow"),
            )
        }
        Text("One thing a line; they go on your task list for tomorrow.", style = AuraType.system(12), color = RunLook.note)
    }
}

/** Frame 02. Focus shelf only. */
@Composable
internal fun RunCountdownView(left: Int, progress: Double, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(AuraColors.background).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(188.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawCircle(RunLook.track, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
                    drawArc(
                        RunLook.go,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.toFloat().coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke),
                    )
                }
                Text(
                    left.toString(),
                    style = TextStyle(fontSize = 60.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    color = RunLook.ink,
                )
            }
        }
        Text(
            "Cancel",
            style = AuraType.callout,
            color = AuraColors.runFocus,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onCancel)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("run-countdown-cancel"),
        )
        Spacer(Modifier.height(44.dp))
    }
}
