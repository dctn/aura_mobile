package info.ibtaura.auraclarus.run

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav

/**
 * PLACEHOLDER (owner: recovery+run agent): frame 14, the hidden run in a capsule above the tabs (iOS MiniRunBar).
 * RootScreen shows it only while `app.run?.showsMiniBar == true && !app.runExpanded`. Tags: "mini-run-bar" (reopens
 * with nav.openRun()), "mini-run-pause".
 */
@Composable
fun MiniRunBar(app: AppState, nav: AuraNav) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(50.dp)
            .clip(CircleShape)
            .background(AuraColors.card)
            .clickable { nav.openRun() }
            .testTag("mini-run-bar")
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Run in progress", style = AuraType.bodyMedium, color = Color.Black)
    }
}
