package info.ibtaura.auraclarus.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraSheet
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.SectionTitle
import kotlinx.coroutines.launch

/** One plain step, shown when the backend answers `consent_required` (Home/ConsentSheet.swift). */
@Composable
fun ConsentSheet(app: AppState) {
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AuraSheet(onDismiss = { app.needsConsent = false }, dismissible = !busy) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionTitle("Health data")
            AuraCard {
                Text(
                    "To show these numbers, Aura needs your permission to store your band and app data in the cloud, read it there and work out your metrics from it.",
                    style = AuraType.callout, color = Color.Black,
                )
                Text(
                    "If you choose Not now, Aura won't store your health data or work out your metrics, so these pages stay empty. You'll be asked again next time they need it.",
                    style = AuraType.callout, color = Color.Black,
                )
            }
            errorText?.let { Text(it, style = AuraType.callout, color = AuraColors.red) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    "Not now",
                    onClick = { app.needsConsent = false },
                    modifier = Modifier.weight(1f).testTag("consent-not-now"),
                    style = PillStyle.Secondary,
                    fills = true,
                    enabled = !busy,
                )
                PillButton(
                    if (busy) "Saving..." else "Allow",
                    onClick = {
                        busy = true
                        errorText = null
                        scope.launch {
                            app.grantConsent()
                            busy = false
                            // A grant that worked closes this sheet; one that failed leaves it up.
                            if (app.needsConsent) {
                                errorText = "That didn't go through. Check your connection and try again."
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("consent-allow"),
                    fills = true,
                    enabled = !busy,
                )
            }
        }
    }
}
