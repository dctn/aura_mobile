package info.ibtaura.auraclarus.run

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.Difficulty
import info.ibtaura.aura.core.RunEngine
import info.ibtaura.aura.core.SessionTrace
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Frame 12. The stress change comes from the session trace, and is only shown when there is one. A saved focus run
 * also asks how hard it was; no pick is a skip, and Done still closes. Closing it any way forgets the run.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun RunDoneSheet(app: AppState, controller: RunController, run: ActiveRun) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { controller.close() },
        sheetState = state,
        containerColor = RunLook.sheet,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
    ) {
        RunDoneContent(app, controller, run)
    }
}

@Composable
private fun RunDoneContent(app: AppState, controller: RunController, run: ActiveRun) {
    val accent = run.mode.runAccent
    val isFocus = run.mode.kind == "focus"
    var isLoaded by remember { mutableStateOf(false) }
    var trace by remember { mutableStateOf<SessionTrace?>(null) }
    var difficulty by remember { mutableStateOf<Difficulty?>(null) }
    var capacityLine by remember { mutableStateOf<String?>(null) }
    var rateError by remember { mutableStateOf<String?>(null) }
    var rateAttempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // The run as it is now: the session id and the saved flags arrive after the sheet opens.
    val current = controller.active ?: run
    val ratableSessionId: String? =
        if (isLoaded && isFocus && current.completeDelivered && !current.workLimitReached) current.sessionId else null

    LaunchedEffect(Unit) {
        trace = controller.loadTrace()
        isLoaded = true
        val now = controller.active ?: run
        val sessionId = if (isFocus && now.completeDelivered && !now.workLimitReached) now.sessionId else null
        if (sessionId != null) {
            try {
                val line = app.api.capacityLine(sessionId)
                if (rateAttempt == 0) capacityLine = line
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.note(e)
            }
        }
    }

    fun rate(picked: Difficulty, sessionId: String) {
        difficulty = picked
        rateError = null
        rateAttempt += 1
        val attempt = rateAttempt
        scope.launch {
            try {
                app.api.setDifficulty(sessionId, picked)
                val line = app.api.capacityLine(sessionId)
                if (attempt != rateAttempt) return@launch
                capacityLine = line ?: "You rated it ${picked.label}."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (attempt != rateAttempt) return@launch
                rateError = "Couldn't save your rating. Try again."
                app.note(e)
            }
        }
    }

    val summary = buildList {
        val seconds = run.engine.elapsed(run.finishedAt ?: Instant.now())
        add("${RunEngine.lengthText(seconds)}.")
        if (isLoaded) {
            add(
                when {
                    current.workLimitReached -> "Not saved: you've reached today's work limit."
                    current.isSaved -> "Saved to your sessions."
                    else -> "Not saved: Aura couldn't be reached."
                },
            )
            stressLine(run, trace)?.let { add(it) }
        }
    }.joinToString(" ")

    Column(
        Modifier.fillMaxWidth().height(if (isFocus) 484.dp else 294.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 14.dp).size(78.dp).background(accent.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(64.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(auraIcon("checkmark"), contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        Text(
            "${run.title} done",
            style = AuraType.system(22, FontWeight.SemiBold),
            color = RunLook.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp, start = 16.dp, end = 16.dp),
        )
        Text(
            summary,
            style = AuraType.callout,
            color = RunLook.note,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 44.dp).padding(top = 6.dp).testTag("run-done-summary"),
        )
        if (ratableSessionId != null) {
            Column(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("How hard was this for you?", style = AuraType.system(17, FontWeight.SemiBold), color = RunLook.ink)
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("run-difficulty")) {
                    DifficultyPicker(selected = difficulty, onPick = { rate(it, ratableSessionId) })
                }
                capacityLine?.let {
                    Text(
                        it,
                        style = AuraType.callout,
                        color = RunLook.note,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 30.dp).testTag("run-capacity-line"),
                    )
                }
                rateError?.let {
                    Text(
                        it,
                        style = AuraType.callout,
                        color = AuraColors.red,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 30.dp),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent)
                .clickable(role = Role.Button) { controller.close() }
                .testTag("run-done"),
            contentAlignment = Alignment.Center,
        ) {
            Text("Done", style = AuraType.system(17, FontWeight.SemiBold), color = Color.White)
        }
    }
}

/** Only recovery and walk sessions have a stress change. Same words as the website. */
private fun stressLine(run: ActiveRun, trace: SessionTrace?): String? {
    if (run.mode.kind == "focus" || run.mode.kind == "meeting" || trace == null) return null
    trace.stressDrop?.let { drop ->
        return if (drop > 0) "Stress fell ${drop.roundToInt()} points." else "Stress didn't drop this time."
    }
    return when (trace.status) {
        "pending" -> "Your stress change shows once today's data is computed."
        "noData" -> "No band data for this session, so there is no stress change to show."
        else -> null
    }
}
