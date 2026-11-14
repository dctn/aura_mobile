package info.ibtaura.auraclarus.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.BackBar
import info.ibtaura.auraclarus.design.bottomPadding

/** The body of a placeholder screen until its agent replaces it. Tagged "coming-soon-<title>". */
@Composable
fun ComingSoon(title: String, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(AuraColors.background).windowInsetsPadding(WindowInsets.statusBars)) {
        if (onBack != null) BackBar(title, onBack)
        Column(
            Modifier.fillMaxSize().padding(bottom = bottomPadding()).testTag("coming-soon-$title"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = AuraType.title, color = Color.Black)
            Text("Coming soon", style = AuraType.callout, color = AuraColors.gray)
        }
    }
}
