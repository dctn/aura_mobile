package info.ibtaura.auraclarus.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.IconTile
import info.ibtaura.auraclarus.design.SearchCategoryStyle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AuraNav

/**
 * Search > Clinical Documents. The backend has no route for documents yet, so this page says what will live here
 * and stores nothing. No fake upload button.
 */
@Composable
fun ClinicalDocumentsScreen(nav: AuraNav) {
    AuraPage(Modifier.testTag("clinical-documents-page"), title = "Clinical Documents", onBack = { nav.pop() }) {
        AuraCard(padding = 24.dp, spacing = 12.dp) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconTile("doc.text.fill", SearchCategoryStyle.blue, size = 60.dp)
                Text("No documents yet", style = AuraType.heading, color = Color.Black, textAlign = TextAlign.Center)
                Text(
                    "Your lab results, prescriptions and doctor's notes will be kept here, next to your health data.",
                    style = AuraType.callout,
                    color = AuraColors.gray,
                    textAlign = TextAlign.Center,
                )
            }
        }
        AuraCard(spacing = 14.dp) {
            Text("What it will hold", style = AuraType.bodyMedium, color = Color.Black)
            DocumentKind("drop.fill", "Lab results", "Blood tests and other reports")
            CardDivider()
            DocumentKind("cross.circle.fill", "Prescriptions", "Medicines and doses")
            CardDivider()
            DocumentKind("heart.text.square.fill", "Visit notes", "Summaries from your doctor")
        }
        Footnote("Adding documents isn't available yet. Aura doesn't store any documents today.")
    }
}

@Composable
private fun DocumentKind(symbol: String, title: String, detail: String) {
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(auraIcon(symbol), contentDescription = null, tint = SearchCategoryStyle.blue, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraType.calloutMedium, color = Color.Black)
            Text(detail, style = AuraType.caption, color = AuraColors.gray)
        }
    }
}
