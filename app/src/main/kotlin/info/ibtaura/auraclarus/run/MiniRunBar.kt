package info.ibtaura.auraclarus.run

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.RunEngine
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Frame 14: a hidden run in a glass bar above the tabs (iOS MiniRunBar). RootScreen shows it only while
 * `app.run?.showsMiniBar == true && !app.runExpanded`. Tags: "mini-run-bar" (reopens), "mini-run-pause".
 */
@Composable
fun MiniRunBar(app: AppState, nav: AuraNav) {
    val controller = app.runController ?: return
    val run = controller.active ?: return
    if (run.finishedAt != null || app.runExpanded) return

    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(run.engine) {
        while (true) {
            now = Instant.now()
            delay(1_000 - (System.currentTimeMillis() % 1_000) + 5)
        }
    }

    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .fillMaxWidth()
            .height(50.dp)
            .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.14f))
            .clip(CircleShape)
            .background(AuraColors.card.copy(alpha = 0.97f))
            .padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(role = Role.Button) { nav.openRun() }
                .testTag("mini-run-bar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(32.dp).background(run.mode.runAccent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(auraIcon(run.mode.runSymbol), contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    run.title,
                    style = AuraType.system(15, FontWeight.SemiBold),
                    color = RunLook.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    miniStatus(run.engine, now),
                    style = AuraType.caption.copy(fontFeatureSettings = "tnum"),
                    color = RunLook.note,
                    maxLines = 1,
                )
            }
        }
        val paused = run.engine.isPaused
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { controller.togglePause() }
                .testTag("mini-run-pause"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                auraIcon(if (paused) "play.fill" else "pause.fill"),
                contentDescription = if (paused) "Resume" else "Pause",
                tint = Color.Black,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun miniStatus(engine: RunEngine, at: Instant): String {
    val snapshot = engine.snapshot(at)
    if (engine.isPaused) return "Paused"
    snapshot.remaining?.let { return "${RunEngine.clock(it)} left" }
    return RunEngine.clock(snapshot.elapsed)
}
