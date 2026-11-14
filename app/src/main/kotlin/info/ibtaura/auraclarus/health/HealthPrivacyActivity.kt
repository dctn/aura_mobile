package info.ibtaura.auraclarus.health

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraTheme
import info.ibtaura.auraclarus.design.AuraType

/**
 * Health Connect's "why does this app want this" page: opened from the permission screen
 * (`androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`) and, on Android 14+, from the app's permission usage
 * (`VIEW_PERMISSION_USAGE`, through the manifest's activity-alias).
 */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        setContent { AuraTheme { HealthPrivacyPage(onBack = { finish() }) } }
    }
}

@Composable
private fun HealthPrivacyPage(onBack: () -> Unit) {
    AuraPage(title = "Health data", onBack = onBack, modifier = Modifier.testTag("health-privacy")) {
        HealthPrivacyCard(
            "What Aura reads",
            "Sleep, steps, distance, active calories, exercise, resting heart rate, heart rate variability, " +
                "breathing rate, blood oxygen, weight, skin temperature and mindfulness sessions.",
        )
        HealthPrivacyCard(
            "Why",
            "Last night's sleep fills in your morning check-in, and your days of activity and recovery go into your " +
                "day score and capacity.",
        )
        HealthPrivacyCard(
            "What Aura does with it",
            "Aura only reads. It never writes to or deletes anything in Health Connect. What it reads is sent to " +
                "your Aura account over an encrypted connection and is used only to work out your scores. It isn't " +
                "sold or used for ads.",
        )
        HealthPrivacyCard(
            "Your choice",
            "You can turn off any of these, or all of them, at any time in Health Connect. Aura then stops reading " +
                "them and asks for your sleep times in the check-in instead.",
        )
        Text("Privacy policy link coming soon.", style = AuraType.caption, color = AuraColors.gray)
    }
}

@Composable
private fun HealthPrivacyCard(title: String, body: String) {
    AuraCard(padding = 20.dp, spacing = 8.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = AuraType.bodyMedium, color = Color.Black)
            Text(body, style = AuraType.callout, color = AuraColors.gray)
        }
    }
}
