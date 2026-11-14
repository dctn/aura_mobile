package info.ibtaura.auraclarus.design

import androidx.compose.ui.graphics.Color
import info.ibtaura.aura.core.Shelf

// A port of DesignSystem/AuraColor.swift and Search/SearchStyle.swift. Colours sampled from the Figma PNGs.
// Light only, like the iOS app.

/** `Color(0xRRGGBB)` with an optional opacity, as Swift's `Color(hex:opacity:)`. */
fun hex(rgb: Long, opacity: Float = 1f): Color = Color(0xFF000000 or rgb).copy(alpha = opacity)

object AuraColors {
    val background = hex(0xF5F5F5)
    val card = Color.White
    val searchField = hex(0xDFDFE0)
    val footnote = hex(0xB4B4B4)
    val track = hex(0xEDEDED)

    val blue = hex(0x0088FF)
    val startBlue = hex(0x108DFD)
    val green = hex(0x34C759)
    val orange = hex(0xFF9F0A)
    val indigo = hex(0x5E5CE6)
    val red = hex(0xFF3B30)

    val burnout = hex(0xFF6B35)
    val burnoutFill = hex(0xF7D9CE)

    val ringOuter = hex(0x0081FF)
    val ringMiddle = hex(0xFF7D4C)
    val ringInner = hex(0x34C759)

    // Run screen accents (Figma "Modes active").
    val runFocus = hex(0x007AFF)
    val runFlow = hex(0xAF52DE)
    val runMeeting = hex(0x5AC8FA)
    val runBreathe = hex(0x32ADE6)
    val runMeditate = hex(0xAF52DE)
    val runNap = hex(0x5E5CE6)

    /** SwiftUI `Color.gray` (secondary text on Settings, Work hours, Sign in). */
    val gray = hex(0x8E8E93)
    /** SwiftUI `.secondary` on a light background. */
    val secondary = hex(0x3C3C43, 0.6f)
    /** The thin line between rows in a list card. */
    val divider = hex(0xDCDCDC)
    /** SwiftUI `Divider()` on a white card. */
    val separator = hex(0xC6C6C8)
    /** The grey Search and metric frames use for small text (`MetricText.grey`). */
    val metricGrey = hex(0x9E9E9F)
    /** The secondary (glass) pill's fill. */
    val glass = hex(0xFAFAFA)
    /** The selected tab's pill. */
    val tabSelected = hex(0xEBEBEB)
}

/** Each shelf's colour, from the Figma's Recovery tab. */
val Shelf.color: Color
    get() = when (this) {
        Shelf.focus -> AuraColors.blue
        Shelf.breathe -> AuraColors.green
        Shelf.meditate -> AuraColors.orange
        Shelf.sleep -> AuraColors.indigo
        Shelf.rest -> AuraColors.orange
        Shelf.movement -> AuraColors.burnout
        Shelf.mind -> AuraColors.blue
        Shelf.outside -> AuraColors.green
        Shelf.people -> AuraColors.red
        Shelf.fuel -> AuraColors.orange
    }

/** Each shelf's SF Symbol name; draw it with `auraIcon(shelf.symbol)`. */
val Shelf.symbol: String
    get() = when (this) {
        Shelf.focus -> "timer"
        Shelf.breathe -> "arrow.clockwise.heart.fill"
        Shelf.meditate -> "brain"
        Shelf.sleep -> "bed.double.fill"
        Shelf.rest -> "brain.head.profile"
        Shelf.movement -> "figure.walk"
        Shelf.mind -> "target"
        Shelf.outside -> "arrow.clockwise.heart.fill"
        Shelf.people -> "person.2.fill"
        Shelf.fuel -> "fork.knife"
    }

/** A Search category's icon and colour, sampled from the Figma's Search frame. */
data class SearchCategoryStyle(val symbol: String, val color: Color) {
    companion object {
        val orange = hex(0xFF9500)
        val sky = hex(0x5AC8FA)
        val blue = hex(0x007AFF)
        val pink = hex(0xFF2D55)
        val grey = hex(0x8E8E93)

        private val fallback = SearchCategoryStyle("square.grid.2x2.fill", grey)

        private val styles: Map<String, SearchCategoryStyle> = mapOf(
            "movement" to SearchCategoryStyle("figure.walk", AuraColors.burnout),
            "running" to SearchCategoryStyle("figure.run", AuraColors.red),
            "cycling" to SearchCategoryStyle("figure.outdoor.cycle", AuraColors.green),
            "gym" to SearchCategoryStyle("figure.strengthtraining.traditional", AuraColors.burnout),
            "swimming" to SearchCategoryStyle("figure.pool.swim", sky),
            "winter" to SearchCategoryStyle("snowflake", blue),
            "body" to SearchCategoryStyle("figure.arms.open", blue),
            "heart" to SearchCategoryStyle("heart.fill", pink),
            "stress" to SearchCategoryStyle("brain.head.profile", orange),
            "sleep" to SearchCategoryStyle("bed.double.fill", AuraColors.indigo),
            "recovery" to SearchCategoryStyle("arrow.clockwise.heart.fill", AuraColors.green),
            "focus" to SearchCategoryStyle("target", blue),
            "screen" to SearchCategoryStyle("hourglass", sky),
            "meetings" to SearchCategoryStyle("person.2.fill", AuraColors.red),
            "mental" to SearchCategoryStyle("brain", orange),
            "respiration" to SearchCategoryStyle("lungs.fill", sky),
            "mobility" to SearchCategoryStyle("figure.walk.motion", AuraColors.burnout),
            "nutrition" to SearchCategoryStyle("fork.knife", orange),
            "cycle" to SearchCategoryStyle("drop.fill", pink),
            "hearing" to SearchCategoryStyle("ear.fill", blue),
            "symptoms" to SearchCategoryStyle("cross.circle.fill", AuraColors.red),
            "burnout" to SearchCategoryStyle("flame.fill", AuraColors.burnout),
            "habits" to SearchCategoryStyle("checklist", AuraColors.green),
            "progress" to SearchCategoryStyle("chart.line.uptrend.xyaxis", AuraColors.green),
            "other" to SearchCategoryStyle("square.grid.2x2.fill", grey),
        )

        /** Real ids are one word ("gym"); the stub's are lowercased titles ("gym and strength"). */
        fun of(sectionId: String): SearchCategoryStyle {
            val key = sectionId.split(" ").firstOrNull() ?: sectionId
            return styles[key] ?: fallback
        }
    }
}
