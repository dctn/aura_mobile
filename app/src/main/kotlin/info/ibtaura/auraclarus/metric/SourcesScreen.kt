package info.ibtaura.auraclarus.metric

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.SearchGroupCard
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route

/**
 * Frame Walk Distance _ Sources (iOS MetricSourcesView). The backend has no per-metric sources, so this lists only
 * what is true: the phone's health store (Health Connect here, Apple Health on iOS) and whether it is connected.
 */
@Composable
fun SourcesScreen(app: AppState, nav: AuraNav, route: Route.Sources) {
    AuraPage(title = route.name, onBack = { nav.pop() }) {
        SectionTitle("Sources", MetricTitleInset)
        SearchGroupCard {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 66.dp)
                    .semantics(mergeDescendants = true) {}
                    .testTag("source-apple-health")
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Text("Health Connect", style = AuraType.row, color = Color.Black)
                Text(
                    if (app.healthConnected) "Connected" else "Not connected",
                    style = AuraType.system(14),
                    color = AuraColors.metricGrey,
                )
            }
        }
        Footnote(
            "${route.name} data is stored in your Aura Clarus account in the cloud. The app can't show yet which device recorded each value.",
            MetricTitleInset,
        )
    }
}
