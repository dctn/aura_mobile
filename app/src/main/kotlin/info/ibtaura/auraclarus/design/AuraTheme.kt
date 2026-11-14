package info.ibtaura.auraclarus.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The iOS app's text, by size and weight. iOS points map 1:1 to sp. SF isn't on Android, so the system sans is used.
 * Prefer these over MaterialTheme.typography in screens: `Text("Insights", style = AuraType.title)`.
 */
object AuraType {
    /** `.system(size:weight:)`: any other size. */
    fun system(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified): TextStyle =
        TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, fontWeight = weight, color = color)

    /** SectionTitle and the back bar title: 30 bold, tracking -0.4. */
    val title = system(30, FontWeight.Bold).copy(letterSpacing = (-0.4).sp)
    /** "Work hours" heading on Settings: 20 bold. */
    val heading = system(20, FontWeight.Bold)
    /** ListRowCard / SearchGroupRow titles: 20 medium. */
    val row = system(20, FontWeight.Medium)
    /** Pill button labels: 19 medium. */
    val pill = system(19, FontWeight.Medium)
    /** Text fields: 18. */
    val field = system(18)
    /** Body text: 17. */
    val body = system(17)
    val bodyMedium = system(17, FontWeight.Medium)
    /** Small text and footnotes: 15. */
    val callout = system(15)
    val calloutMedium = system(15, FontWeight.Medium)
    val calloutSemibold = system(15, FontWeight.SemiBold)
    /** Labels over values: 13. */
    val caption = system(13)
    /** Tab bar labels. */
    val tab = system(12, FontWeight.Medium)
}

private val colors = lightColorScheme(
    primary = AuraColors.blue,
    onPrimary = Color.White,
    secondary = AuraColors.startBlue,
    background = AuraColors.background,
    onBackground = Color.Black,
    surface = AuraColors.card,
    onSurface = Color.Black,
    surfaceContainerLow = AuraColors.background,
    surfaceContainer = AuraColors.background,
    surfaceContainerHigh = AuraColors.card,
    surfaceContainerHighest = AuraColors.card,
    error = AuraColors.red,
    outline = AuraColors.separator,
    outlineVariant = AuraColors.divider,
)

private val typography = Typography(
    displaySmall = AuraType.title,
    headlineLarge = AuraType.title,
    headlineMedium = AuraType.system(28, FontWeight.Bold),
    headlineSmall = AuraType.system(24, FontWeight.Bold),
    titleLarge = AuraType.heading,
    titleMedium = AuraType.bodyMedium,
    titleSmall = AuraType.calloutMedium,
    bodyLarge = AuraType.body,
    bodyMedium = AuraType.callout,
    bodySmall = AuraType.caption,
    labelLarge = AuraType.pill,
    labelMedium = AuraType.calloutMedium,
    labelSmall = AuraType.tab,
)

/** Light only, as the iOS app (`.preferredColorScheme(.light)`). */
@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
