package info.ibtaura.auraclarus.run

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import info.ibtaura.aura.core.RunEngine
import info.ibtaura.aura.core.RunKind
import info.ibtaura.aura.core.RunPhase
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import kotlinx.coroutines.delay
import java.time.Instant

/** Frames 02 to 13: every run, drawn from `engine.snapshot(at)` (iOS RunScreen). Hide with nav.closeRun(). */
@Composable
fun RunScreen(app: AppState, nav: AuraNav) {
    val controller = app.runController
    val run = controller?.active

    // FLAG_KEEP_SCREEN_ON while the run page shows (iOS isIdleTimerDisabled).
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    AskForNotifications(app, run?.startId)

    if (controller == null || run == null) {
        Box(Modifier.fillMaxSize().background(AuraColors.background))
    } else {
        RunStage(app, nav, controller, run)
    }
    OtherSessionAlert(app, showsHere = true)
}

/** Android 13+: the end-of-run notification needs the permission, asked when a run starts (iOS asks then too). */
@Composable
private fun AskForNotifications(app: AppState, runId: String?) {
    if (app.flags.isUiTesting || runId == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(runId) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !asked.contains(runId)) {
            asked.add(runId)
            runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}

/** Runs already asked about in this process, so reopening the run page doesn't ask again. */
private val asked = mutableSetOf<String>()

private sealed interface RunAlert {
    data class End(val question: String) : RunAlert
    data class Info(val title: String, val body: String) : RunAlert
}

@Composable
private fun RunStage(app: AppState, nav: AuraNav, controller: RunController, run: ActiveRun) {
    val isDone = run.finishedAt != null
    val isNight = run.mode.night == true
    var alert by remember { mutableStateOf<RunAlert?>(null) }
    var doneShown by remember { mutableStateOf(false) }
    var listFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Every frame only for the breathing circle. UI tests stay slow so UiAutomator sees an idle app.
    val everyFrame = run.mode.run == RunKind.breathe && !app.flags.isUiTesting
    val paused = isDone || run.engine.isPaused
    var tick by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(paused, everyFrame, run.engine) {
        tick = Instant.now()
        if (paused) return@LaunchedEffect
        while (true) {
            if (everyFrame) withFrameMillis { } else delay(250)
            tick = Instant.now()
        }
    }
    val now = run.finishedAt ?: tick

    LaunchedEffect(isDone) {
        if (!isDone) {
            doneShown = false
            return@LaunchedEffect
        }
        alert = null
        // Lets the run screen finish coming up, or the end-early dialog finish going away.
        delay(500)
        doneShown = true
    }

    HideStatusBar(isNight)

    val snapshot = run.engine.snapshot(now)
    val phase = snapshot.phase
    if (phase is RunPhase.Countdown) {
        RunCountdownView(phase.left, snapshot.countdownProgress) { controller.cancelCountdown() }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(if (isNight) Color.Black else AuraColors.background)
                .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            RunTopBar(
                isNight = isNight,
                onHide = { nav.closeRun() },
                onInfo = {
                    val length = if (run.engine.isOpenEnded) "Open-ended" else RunEngine.lengthText(run.engine.duration)
                    val text = "${run.mode.note} $length, timed on this phone and saved as a session. " +
                        "Hide keeps it running above the tabs. End lets you stop early or count it as done."
                    alert = RunAlert.Info(run.title, text)
                },
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = if (listFocused) 0.dp else 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                RunCenter(
                    run = run,
                    snapshot = snapshot,
                    now = now,
                    tomorrowText = run.tomorrowList,
                    onTomorrowChange = { controller.setTomorrowList(it) },
                    onListFocus = { listFocused = it },
                )
            }
            if (!listFocused) {
                RunControls(
                    isPaused = run.engine.isPaused,
                    isNight = isNight,
                    onPause = { controller.togglePause() },
                    onEnd = { alert = RunAlert.End(run.engine.endEarlyTitle(Instant.now())) },
                )
            }
        }
    }

    alert?.let { shown ->
        RunAlertDialog(shown, controller, onDismiss = { alert = null })
    }
    if (doneShown && isDone) {
        RunDoneSheet(app, controller, run)
    }
}

@Composable
private fun RunTopBar(isNight: Boolean, onHide: () -> Unit, onInfo: () -> Unit) {
    val tint = if (isNight) RunLook.nightDim else AuraColors.runFocus
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onHide)
                .padding(vertical = 6.dp)
                .testTag("run-hide"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Hide", style = AuraType.body, color = tint)
            Icon(auraIcon("chevron.down"), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onInfo)
                .testTag("run-info"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(auraIcon("info.circle"), contentDescription = "About this run", tint = tint, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun RunControls(isPaused: Boolean, isNight: Boolean, onPause: () -> Unit, onEnd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 30.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (isNight) RunLook.nightButton else RunLook.track)
                .clickable(role = Role.Button, onClick = onPause)
                .testTag("run-pause"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                auraIcon(if (isPaused) "play.fill" else "pause.fill"),
                contentDescription = if (isPaused) "Resume" else "Pause",
                tint = if (isNight) Color.White else Color.Black,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.width(40.dp))
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(AuraColors.red)
                .clickable(role = Role.Button, onClick = onEnd)
                .testTag("run-end"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(auraIcon("xmark"), contentDescription = "End", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

/** Frame 13's end-early question (Mark as done, End session, Cancel), and the info alert. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RunAlertDialog(alert: RunAlert, controller: RunController, onDismiss: () -> Unit) {
    val title = when (alert) {
        is RunAlert.End -> alert.question
        is RunAlert.Info -> alert.title
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuraColors.card,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("run-alert"),
        title = { Text(title, style = AuraType.system(17, FontWeight.SemiBold), color = Color.Black) },
        text = (alert as? RunAlert.Info)?.let { info -> { Text(info.body, style = AuraType.callout, color = AuraColors.gray) } },
        confirmButton = {
            when (alert) {
                is RunAlert.End -> Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            controller.markDone()
                        },
                        modifier = Modifier.testTag("run-mark-done"),
                    ) { Text("Mark as done", style = AuraType.bodyMedium, color = AuraColors.blue) }
                    TextButton(
                        onClick = {
                            onDismiss()
                            controller.endSession()
                        },
                        modifier = Modifier.testTag("run-end-session"),
                    ) { Text("End session", style = AuraType.bodyMedium, color = AuraColors.red) }
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("run-end-cancel")) {
                        Text("Cancel", style = AuraType.system(17, FontWeight.SemiBold), color = AuraColors.blue)
                    }
                }
                is RunAlert.Info -> TextButton(onClick = onDismiss, modifier = Modifier.testTag("run-info-ok")) {
                    Text("OK", style = AuraType.system(17, FontWeight.SemiBold), color = AuraColors.blue)
                }
            }
        },
    )
}

/** A nap's black screen hides the status bar (iOS `statusBarHidden`). */
@Composable
private fun HideStatusBar(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden, view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (hidden) controller?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { if (hidden) controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
