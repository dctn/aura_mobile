package info.ibtaura.auraclarus.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.HealthSources
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.health.openApp
import info.ibtaura.auraclarus.shell.AppState

/**
 * Says why this morning's capacity score is an estimate: still collecting the first nights, or no sleep from last
 * night (a warning after several). Nothing while the score is a real one. When sleep is missing and onboarding found
 * the sleep app, "Open <app>" takes the user there to check its sharing.
 */
@Composable
internal fun CollectingBanner(app: AppState) {
    val banner = app.collectingBanner ?: return
    val context = LocalContext.current
    val sourceApp = app.sleepSourceApp?.takeIf { banner.title.startsWith("No sleep") }
    AuraCard(padding = 20.dp, modifier = Modifier.testTag("home-collecting")) {
        // Title and detail read as one by TalkBack; the pill below stays its own button.
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                auraIcon(if (banner.warning) "exclamationmark.triangle.fill" else "info.circle.fill"),
                contentDescription = null,
                tint = if (banner.warning) AuraColors.orange else AuraColors.blue,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(banner.title, style = AuraType.calloutSemibold, color = Color.Black)
                Text(banner.detail, style = AuraType.caption, color = AuraColors.gray)
            }
        }
        if (sourceApp != null) {
            PillButton(
                "Open ${HealthSources.name(sourceApp)}",
                { openApp(context, sourceApp) },
                Modifier.padding(start = 34.dp).testTag("home-collecting-open"),
                style = PillStyle.Secondary,
            )
        }
    }
}
