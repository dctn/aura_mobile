package info.ibtaura.auraclarus.run

import androidx.compose.ui.graphics.Color
import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.RunKind
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.color
import info.ibtaura.auraclarus.design.hex
import info.ibtaura.auraclarus.design.symbol

/** Colours sampled from "Modes · active screens" that AuraColors doesn't have (iOS RunLook). */
internal object RunLook {
    val ink = hex(0x1C1C1E)
    val note = hex(0x86868A)
    val track = hex(0xE5E5E6)
    val sheet = hex(0xF2F2F7)
    val go = hex(0x1ED760)
    val nightDim = hex(0x8D8D93)
    val nightButton = hex(0x262629)
}

/** A run's accent, from the frames: by what kind of run it is, and otherwise its shelf's colour (frame 06). */
internal val Mode.runAccent: Color
    get() = when (run) {
        RunKind.breathe -> AuraColors.runBreathe
        RunKind.guided -> AuraColors.runMeditate
        RunKind.flow -> AuraColors.runFlow
        RunKind.winddown -> AuraColors.runNap
        RunKind.timer, RunKind.plan -> when {
            kind == "meeting" -> AuraColors.runMeeting
            kind == "focus" -> AuraColors.runFocus
            night == true -> AuraColors.runNap
            else -> shelf.color
        }
    }

/** The icon in the bar above the tabs. */
internal val Mode.runSymbol: String
    get() = if (run == RunKind.breathe) "wind" else shelf.symbol
