package info.ibtaura.auraclarus.check

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import info.ibtaura.aura.core.MEMORY_TEST_SECONDS
import info.ibtaura.aura.core.QuickCheckResult
import info.ibtaura.aura.core.REACTION_TEST_SECONDS
import info.ibtaura.aura.core.ReactionTest
import info.ibtaura.aura.core.SpanTest
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.hex
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Quick check cover (iOS QuickCheckScreen.swift), full screen and dark like the design: an intro, the reaction
 * test, the memory part (45 s each: `REACTION_TEST_SECONDS`, `MEMORY_TEST_SECONDS`), then what the backend made of it.
 * Ends with `nav.finishQuickCheck(result)`: the backend's answer after Done, or null when the person left without one.
 */
@Composable
fun QuickCheckScreen(app: AppState, nav: AuraNav) {
    val scope = rememberCoroutineScope()
    val flow = remember(app) {
        QuickCheckFlow(
            api = app.api,
            short = app.quickCheckShort,
            uiTesting = app.flags.isUiTesting,
            scope = scope,
            // Missing consent opens the consent step from here.
            onError = { app.note(it) },
        )
    }
    var closed by remember { mutableStateOf(false) }
    val close: (QuickCheckResult?) -> Unit = { result ->
        if (!closed) {
            closed = true
            flow.stop()
            nav.finishQuickCheck(result)
        }
    }

    // Leaving the app (or anything taking the front) stops the reaction test: its timing no longer holds.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { flow.interrupt() }

    // The screen stays on, and the status bar turns light on the dark page, while the check shows.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val wasLight = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            flow.stop()
            view.keepScreenOn = false
            if (wasLight != null) controller.isAppearanceLightStatusBars = wasLight
        }
    }

    QuickCheckStage(flow, onClose = close)
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

// MARK: The frame around every step

@Composable
private fun QuickCheckStage(flow: QuickCheckFlow, onClose: (QuickCheckResult?) -> Unit) {
    var askingStop by remember { mutableStateOf(false) }
    var showingInfo by remember { mutableStateOf(false) }

    val accent = when (flow.step) {
        QuickCheckFlow.Step.reaction -> QuickCheckLook.green
        QuickCheckFlow.Step.memory -> QuickCheckLook.purple
        else -> QuickCheckLook.dim
    }

    // The intro closes at once (nothing has run); a running part asks first.
    val hide: () -> Unit = {
        when (flow.step) {
            QuickCheckFlow.Step.intro, QuickCheckFlow.Step.sending -> onClose(null)
            QuickCheckFlow.Step.reaction, QuickCheckFlow.Step.memory -> askingStop = true
            QuickCheckFlow.Step.result -> onClose(flow.result)
        }
    }
    val hideEnabled = !(flow.step == QuickCheckFlow.Step.sending && !flow.sendFailed)

    // System back does what Hide does (this handler wins over the cover's own).
    BackHandler { if (hideEnabled) hide() }

    Column(
        Modifier
            .fillMaxSize()
            .background(QuickCheckLook.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // Top bar: Hide, then the info button.
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(enabled = hideEnabled, role = Role.Button, onClick = hide)
                    .testTag("quick-check-hide"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tint = if (hideEnabled) accent else accent.copy(alpha = 0.4f)
                Text("Hide", style = AuraType.system(17), color = tint)
                Icon(auraIcon("chevron.down"), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(role = Role.Button) { showingInfo = true }
                    .semantics { contentDescription = "About this step" }
                    .testTag("quick-check-info"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(auraIcon("info.circle"), contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
            }
        }

        AnimatedContent(
            targetState = flow.step,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = "quick-check-step",
        ) { step ->
            when (step) {
                QuickCheckFlow.Step.intro -> QuickCheckIntro(flow.interrupted) { flow.startReaction() }
                QuickCheckFlow.Step.reaction -> QuickCheckReaction(flow)
                QuickCheckFlow.Step.memory -> QuickCheckMemory(flow)
                QuickCheckFlow.Step.sending -> QuickCheckSending(flow.sendFailed, flow.sendRefused) { flow.submit() }
                QuickCheckFlow.Step.result -> flow.result?.let { result ->
                    QuickCheckResultView(result, bestSpan = if (flow.memoryDone) flow.span.bestSpan else null) {
                        onClose(result)
                    }
                }
            }
        }
    }

    if (askingStop) {
        QuickCheckAlert(
            title = "Stop the check?",
            message = "It won't be sent.",
            confirmTitle = "Stop",
            confirmTag = "quick-check-stop",
            onConfirm = {
                askingStop = false
                onClose(null)
            },
            cancelTitle = "Keep going",
            cancelTag = "quick-check-keep-going",
            onDismiss = { askingStop = false },
        )
    }
    if (showingInfo) {
        QuickCheckAlert(
            title = infoTitle(flow.step),
            message = infoText(flow.step),
            confirmTitle = "OK",
            confirmTag = "quick-check-info-ok",
            destructive = false,
            onConfirm = { showingInfo = false },
            onDismiss = { showingInfo = false },
        )
    }
}

private fun infoTitle(step: QuickCheckFlow.Step): String = when (step) {
    QuickCheckFlow.Step.reaction -> "Reaction"
    QuickCheckFlow.Step.memory -> "Memory"
    else -> "Quick check"
}

private fun infoText(step: QuickCheckFlow.Step): String = when (step) {
    QuickCheckFlow.Step.intro ->
        "Two short tests of how alert you are right now: a ${lengthAdjective(REACTION_TEST_SECONDS)} reaction test, " +
            "then a ${lengthAdjective(MEMORY_TEST_SECONDS)} memory test. " +
            "Aura uses it to plan your day. Hide closes this; nothing is sent."
    QuickCheckFlow.Step.reaction ->
        "Tap the card the moment it turns green. A tap before green is a false start, " +
            "and an answer of 355 ms or slower counts as a slow answer. " +
            "It runs for ${spokenLength(REACTION_TEST_SECONDS)}."
    QuickCheckFlow.Step.memory ->
        "Digits show for a moment; type them back in order. A right answer adds a digit, " +
            "and two misses at the same length end it. It runs for up to ${spokenLength(MEMORY_TEST_SECONDS)}. " +
            "Skip sends the check without this part."
    QuickCheckFlow.Step.sending, QuickCheckFlow.Step.result ->
        "Aura scores the check. It counts when the reaction test ran the full ${spokenLength(REACTION_TEST_SECONDS)}, " +
            "with enough answers and few false starts. Your normal uses morning checks taken 30+ minutes after waking, " +
            "after 7+ hours of sleep."
}

/** The dark alert of the check (iOS `.alert` in dark mode). One button without `cancelTitle`. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun QuickCheckAlert(
    title: String,
    message: String,
    confirmTitle: String,
    confirmTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    cancelTitle: String? = null,
    cancelTag: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = QuickCheckLook.alert,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text(title, style = AuraType.system(17, FontWeight.SemiBold), color = Color.White) },
        text = { Text(message, style = AuraType.callout, color = QuickCheckLook.dim) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(confirmTag)) {
                Text(
                    confirmTitle,
                    style = AuraType.bodyMedium,
                    color = if (destructive) QuickCheckLook.red else QuickCheckLook.blue,
                )
            }
        },
        dismissButton = cancelTitle?.let {
            {
                TextButton(
                    onClick = onDismiss,
                    modifier = if (cancelTag != null) Modifier.testTag(cancelTag) else Modifier,
                ) { Text(it, style = AuraType.system(17, FontWeight.SemiBold), color = QuickCheckLook.blue) }
            }
        },
    )
}

@Composable
private fun QuickCheckHeading(kicker: String, title: String, accent: Color) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(kicker, style = AuraType.system(11, FontWeight.Bold).copy(letterSpacing = 0.6.sp), color = accent)
        Text(
            title,
            style = AuraType.system(22, FontWeight.SemiBold),
            color = Color.White,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** A full-width capsule button in the dark design's colours. */
@Composable
private fun QuickCheckPill(title: String, fill: Color, ink: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .background(fill, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = AuraType.system(19, FontWeight.SemiBold), color = ink)
    }
}

// MARK: Intro

@Composable
private fun QuickCheckIntro(interrupted: Boolean, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 26.dp, end = 26.dp, top = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "QUICK CHECK",
                    style = AuraType.system(11, FontWeight.Bold).copy(letterSpacing = 0.6.sp),
                    color = QuickCheckLook.dim,
                )
                Text(
                    "Quick check",
                    style = AuraType.system(30, FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.semantics { heading() }.testTag("quick-check-intro"),
                )
                Text("Under ${spokenLength(totalMinutes * 60.0)}, in two steps.", style = AuraType.body, color = Color.White.copy(alpha = 0.85f))
            }
            if (interrupted) {
                Text(
                    "The check stopped when you left the app. The reaction test needs you here the whole time, so it starts again.",
                    style = AuraType.callout,
                    color = QuickCheckLook.green,
                    modifier = Modifier.testTag("quick-check-interrupted"),
                )
            }
            StepRow(1, QuickCheckLook.green, "Reaction", "Tap the card the moment it turns green. For ${spokenLength(REACTION_TEST_SECONDS)}.")
            StepRow(2, QuickCheckLook.purple, "Memory", "A row of digits shows for a moment. Type them back. For ${spokenLength(MEMORY_TEST_SECONDS)}.")
            Text("Best in the morning, 30+ minutes after waking.", style = AuraType.callout, color = QuickCheckLook.dim)
        }
        QuickCheckPill(
            "Start",
            fill = QuickCheckLook.green,
            ink = Color.Black,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 16.dp).testTag("quick-check-start"),
            onClick = onStart,
        )
    }
}

@Composable
private fun StepRow(number: Int, color: Color, title: String, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(QuickCheckLook.card, RoundedCornerShape(18.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(28.dp).background(color, CircleShape).semantics { hideFromAccessibility() },
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = AuraType.system(15, FontWeight.Bold), color = Color.Black)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = AuraType.system(17, FontWeight.SemiBold), color = color)
            Text(text, style = AuraType.callout, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

// MARK: Reaction

@Composable
private fun QuickCheckReaction(flow: QuickCheckFlow) {
    val phase = flow.reactionPhase
    val isDot = phase == ReactionTest.Phase.Dot
    val cardValue = when (phase) {
        ReactionTest.Phase.Dot -> "Tap!"
        is ReactionTest.Phase.Result -> "${phase.ms.roundToInt()} ms"
        ReactionTest.Phase.TooSoon -> "Too soon"
        ReactionTest.Phase.NotStarted, ReactionTest.Phase.Waiting -> "Waiting"
        ReactionTest.Phase.Finished -> "Done"
    }
    val leftText = leftText(flow.secondsLeft)

    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        QuickCheckHeading("QUICK CHECK · 1 OF 2", "Reaction", QuickCheckLook.green)
        // No animation on the card: the green must show on the frame the dot is counted from.
        Box(
            Modifier
                .padding(start = 24.dp, end = 24.dp, top = 28.dp)
                .fillMaxWidth()
                .height(380.dp)
                .background(if (isDot) QuickCheckLook.green else QuickCheckLook.card, RoundedCornerShape(40.dp))
                // Counts the touch when the finger lands, with the touch event's own time; one touch is one tap.
                .pointerInput(flow) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        flow.tap(down.uptimeMillis / 1000.0)
                        waitForUpOrCancellation()
                    }
                }
                // Read by TalkBack and by the UI walk, which taps only on "Tap!".
                .clearAndSetSemantics {
                    contentDescription = "Reaction card, $cardValue"
                    role = Role.Button
                    testTag = "quick-check-reaction"
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (phase) {
                    ReactionTest.Phase.Dot ->
                        Text("Tap!", style = AuraType.system(52, FontWeight.Bold), color = Color.Black)
                    is ReactionTest.Phase.Result ->
                        Text("${phase.ms.roundToInt()} ms", style = AuraType.system(44, FontWeight.SemiBold), color = Color.White)
                    ReactionTest.Phase.TooSoon ->
                        Text("Too soon", style = AuraType.system(36, FontWeight.SemiBold), color = QuickCheckLook.orange)
                    ReactionTest.Phase.NotStarted, ReactionTest.Phase.Waiting ->
                        Text("Wait for green", style = AuraType.system(20, FontWeight.Medium), color = QuickCheckLook.dim)
                    ReactionTest.Phase.Finished ->
                        Text("Done", style = AuraType.system(36, FontWeight.SemiBold), color = Color.White)
                }
                Text(
                    leftText,
                    style = AuraType.callout.copy(fontFeatureSettings = "tnum"),
                    color = if (isDot) Color.Black.copy(alpha = 0.55f) else QuickCheckLook.dim,
                )
            }
        }

        // The last 3 times, newest first: "312 ms   —   —".
        val times = flow.recentTimes
        val timeoutMs = flow.reactionConfig.timeout * 1000
        val timeText = { ms: Double -> if (ms >= timeoutMs - 0.5) "Missed" else "${ms.roundToInt()} ms" }
        val recentLabel = if (times.isEmpty()) "No times yet" else "Last times: " + times.joinToString(", ") { timeText(it) }
        Row(
            Modifier
                .padding(start = 36.dp, end = 36.dp, top = 26.dp)
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = recentLabel
                    testTag = "quick-check-recent"
                },
        ) {
            for (index in 0 until 3) {
                val has = index < times.size
                Text(
                    if (has) timeText(times[index]) else "—",
                    style = AuraType.system(17, FontWeight.Medium).copy(fontFeatureSettings = "tnum"),
                    color = if (has) Color.White else QuickCheckLook.dim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Both tests and a little over, in whole minutes: 2 for 45 s + 45 s. */
private val totalMinutes: Int = ceil((REACTION_TEST_SECONDS + MEMORY_TEST_SECONDS) / 60.0).toInt()

/** "45 seconds", "1 minute", "3 minutes": whole minutes as minutes, anything else in seconds. */
private fun spokenLength(seconds: Double): String {
    val whole = seconds.roundToInt()
    return when {
        whole >= 60 && whole % 60 == 0 -> if (whole == 60) "1 minute" else "${whole / 60} minutes"
        whole == 1 -> "1 second"
        else -> "$whole seconds"
    }
}

/** "45-second", "3-minute". */
private fun lengthAdjective(seconds: Double): String {
    val whole = seconds.roundToInt()
    return if (whole >= 60 && whole % 60 == 0) "${whole / 60}-minute" else "$whole-second"
}

/** "0:45 left". */
private fun leftText(secondsLeft: Int): String {
    val seconds = max(0, secondsLeft)
    return "${seconds / 60}:${String.format(Locale.ROOT, "%02d", seconds % 60)} left"
}

// MARK: Memory

private val keyWidth = 86.dp
private val keyHeight = 56.dp

@Composable
private fun QuickCheckMemory(flow: QuickCheckFlow) {
    // Reading the version makes this redraw when the (non-Compose) test changes.
    @Suppress("UNUSED_VARIABLE") val version = flow.spanVersion
    val span = flow.span
    val feedback = flow.feedback

    Column(Modifier.fillMaxSize().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        QuickCheckHeading("QUICK CHECK · 2 OF 2", "Memory", QuickCheckLook.purple)
        Text(
            leftText(flow.memorySecondsLeft),
            style = AuraType.callout.copy(fontFeatureSettings = "tnum"),
            color = QuickCheckLook.dim,
            modifier = Modifier.padding(top = 6.dp).testTag("quick-check-memory-left"),
        )

        // The prompt.
        Column(
            Modifier.padding(top = 14.dp).height(104.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            when {
                feedback != null -> Text(
                    if (feedback) "Right" else "Not quite",
                    style = AuraType.system(30, FontWeight.SemiBold),
                    color = if (feedback) QuickCheckLook.green else QuickCheckLook.orange,
                    modifier = Modifier.testTag("quick-check-feedback"),
                )
                !flow.memoryBegun -> Text("Get ready", style = AuraType.system(20, FontWeight.Medium), color = QuickCheckLook.dim)
                span.phase == SpanTest.Phase.showing -> {
                    val row = span.digits.joinToString(" ")
                    Text(
                        row,
                        style = AuraType.system(if (span.digits.size > 6) 36 else 48, FontWeight.Light)
                            .copy(letterSpacing = 6.sp, fontFeatureSettings = "tnum"),
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .clearAndSetSemantics {
                                contentDescription = row
                                testTag = "quick-check-digits"
                            },
                    )
                    Text("Remember these · ${span.digits.size} digits", style = AuraType.callout, color = QuickCheckLook.dim)
                }
                span.phase == SpanTest.Phase.entering -> {
                    Text("Type them back", style = AuraType.system(22, FontWeight.Medium), color = Color.White)
                    Text("${span.digits.size} digits", style = AuraType.callout, color = QuickCheckLook.dim)
                }
            }
        }

        // One box per digit: empty while the row shows, then what was typed.
        val row = if (feedback != null) flow.feedbackRow else span.entered
        val count = if (feedback != null) flow.feedbackRow.size else if (flow.memoryBegun) span.digits.size else 0
        val wide = count <= 6
        val tint = feedback?.let { if (it) QuickCheckLook.green else QuickCheckLook.orange }
        val typedLabel = if (row.isEmpty()) "Nothing typed" else "Typed " + row.joinToString(" ")
        Row(
            Modifier
                .padding(top = 14.dp)
                .height(52.dp)
                .clearAndSetSemantics {
                    contentDescription = typedLabel
                    testTag = "quick-check-entry"
                },
            horizontalArrangement = Arrangement.spacedBy(if (wide) 10.dp else 6.dp),
        ) {
            for (index in 0 until count) {
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier
                        .width(if (wide) 46.dp else 32.dp)
                        .height(52.dp)
                        .background(QuickCheckLook.card, shape)
                        .then(if (tint != null) Modifier.border(2.dp, tint, shape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (index < row.size) row[index].toString() else " ",
                        style = AuraType.system(if (wide) 24 else 20, FontWeight.SemiBold),
                        color = Color.White,
                    )
                }
            }
        }

        // The keypad only exists while typing, so a test (or a person) can't type while the row shows.
        // Its space is kept so nothing jumps.
        Box(Modifier.padding(top = 30.dp).height(keyHeight * 4 + 30.dp), contentAlignment = Alignment.TopCenter) {
            if (span.phase == SpanTest.Phase.entering || feedback != null) {
                val enabled = feedback == null
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (line in 0 until 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (column in 1..3) Key(line * 3 + column, enabled) { flow.type(it) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Spacer(Modifier.width(keyWidth).height(keyHeight))
                        Key(0, enabled) { flow.type(it) }
                        Box(
                            Modifier
                                .width(keyWidth)
                                .height(keyHeight)
                                .background(QuickCheckLook.key, RoundedCornerShape(12.dp))
                                .clickable(enabled = enabled, role = Role.Button) { flow.deleteLast() }
                                .semantics { contentDescription = "Delete" }
                                .testTag("quick-check-delete"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(auraIcon("delete.left"), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .padding(bottom = 12.dp)
                .widthIn(min = 88.dp)
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClickLabel = "Send the check without the memory part") { flow.skipMemory() }
                .testTag("quick-check-skip-memory"),
            contentAlignment = Alignment.Center,
        ) {
            Text("Skip", style = AuraType.bodyMedium, color = QuickCheckLook.dim)
        }
    }
}

@Composable
private fun Key(digit: Int, enabled: Boolean, onType: (Int) -> Unit) {
    Box(
        Modifier
            .width(keyWidth)
            .height(keyHeight)
            .background(QuickCheckLook.key, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button) { onType(digit) }
            .testTag("quick-check-key-$digit"),
        contentAlignment = Alignment.Center,
    ) {
        Text(digit.toString(), style = AuraType.system(24), color = Color.White)
    }
}

// MARK: Sending

@Composable
private fun QuickCheckSending(failed: Boolean, refused: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (failed) {
            Icon(
                auraIcon("exclamationmark.triangle"),
                contentDescription = null,
                tint = QuickCheckLook.orange,
                modifier = Modifier.size(40.dp),
            )
            Text(
                "Couldn't send the check.",
                style = AuraType.system(20, FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier.testTag("quick-check-send-failed"),
            )
            Text(
                if (refused) {
                    "The server doesn't take this check yet, so it wasn't saved. Shorter checks will count once the backend is updated."
                } else {
                    "Your results are still here. Try again when you're online."
                },
                style = AuraType.callout,
                color = QuickCheckLook.dim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            QuickCheckPill(
                "Try again",
                fill = Color.White,
                ink = Color.Black,
                modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 8.dp).testTag("quick-check-retry"),
                onClick = onRetry,
            )
        } else {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            Text(
                "Sending your check",
                style = AuraType.callout,
                color = QuickCheckLook.dim,
                modifier = Modifier.testTag("quick-check-sending"),
            )
        }
    }
}

// MARK: Result

private const val NORMAL_NOTE = "This one won't count toward your normal, which uses morning checks taken " +
    "30+ minutes after waking, after 7+ hours of sleep. It still counts for today."

/** The server sends a code, not words. */
private fun reasonText(code: String?): String = when (code.orEmpty()) {
    // The server's code still says 3 minutes; the test is shorter now.
    "not_3_minutes" -> "It didn't run the full time."
    "too_few_responses" -> "Too few taps were answered."
    "too_many_false_starts" -> "Too many taps came before the green."
    else -> "It didn't meet the rules for a check."
}

@Composable
private fun QuickCheckResultView(result: QuickCheckResult, bestSpan: Int?, onDone: () -> Unit) {
    val verdict = if (result.valid) "This check counts" else "This check didn't count: ${reasonText(result.invalidReason)}"
    Column(Modifier.fillMaxSize().testTag("quick-check-result")) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 26.dp, end = 26.dp, top = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "QUICK CHECK · DONE",
                    style = AuraType.system(11, FontWeight.Bold).copy(letterSpacing = 0.6.sp),
                    color = QuickCheckLook.dim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        auraIcon(if (result.valid) "checkmark.circle.fill" else "exclamationmark.circle.fill"),
                        contentDescription = null,
                        tint = if (result.valid) QuickCheckLook.green else QuickCheckLook.orange,
                        modifier = Modifier.size(30.dp),
                    )
                    Text(
                        verdict,
                        style = AuraType.system(24, FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.semantics { heading() }.testTag("quick-check-verdict"),
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(QuickCheckLook.card, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp),
            ) {
                StatRow("Reaction time", "The middle of your answers", "${result.medianRtMs.roundToInt()} ms")
                StatDivider()
                StatRow("Lapses", "Slow answers, 355 ms or more", result.lapses.toString())
                StatDivider()
                StatRow("False starts", "Taps before green", result.falseStarts.toString())
                if (bestSpan != null) {
                    StatDivider()
                    StatRow("Best span", "The longest row you typed back", if (bestSpan == 0) "None" else "$bestSpan digits")
                }
            }
            if (result.valid && !result.qualifiesForNormal) {
                Text(
                    NORMAL_NOTE,
                    style = AuraType.callout,
                    color = QuickCheckLook.dim,
                    modifier = Modifier.testTag("quick-check-normal-note"),
                )
            }
        }
        QuickCheckPill(
            "Done",
            fill = Color.White,
            ink = Color.Black,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 16.dp).testTag("quick-check-done"),
            onClick = onDone,
        )
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(QuickCheckLook.divider))
}

@Composable
private fun StatRow(title: String, detail: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AuraType.bodyMedium, color = Color.White)
            Text(detail, style = AuraType.caption, color = QuickCheckLook.dim)
        }
        Text(
            value,
            style = AuraType.system(20, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
            color = Color.White,
        )
    }
}

// MARK: Look

/** Colours sampled from the dark Quick check frames; the rest of the app is light. */
private object QuickCheckLook {
    val background = hex(0x0D0D0F)
    val card = hex(0x2C2C2E)
    val key = hex(0x2C2C2E)
    val divider = hex(0x3A3A3C)
    val dim = hex(0x8E8E93)
    val green = hex(0x30D158)
    val purple = hex(0xBF5AF2)
    val orange = hex(0xFF9F0A)
    /** iOS dark-mode alert and its buttons. */
    val alert = hex(0x1C1C1E)
    val blue = hex(0x0A84FF)
    val red = hex(0xFF453A)
}
