package info.ibtaura.auraclarus.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraSheet
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.auraIcon
import java.util.Locale

/** One goal the stepper edits: its range, step and how a value reads ("8,000 steps", "8 hr 30 min"). */
internal data class GoalSpec(
    val key: String,
    val title: String,
    val symbol: String,
    val color: Color,
    val min: Int,
    val max: Int,
    val step: Int,
    val footnote: String,
    val text: (Int) -> String,
)

internal fun grouped(value: Int): String = String.format(Locale.US, "%,d", value)

internal fun hoursAndMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h hr"
        else -> "$h hr $m min"
    }
}

/** Snap to the step grid inside the range. */
internal fun snap(value: Int, spec: GoalSpec): Int {
    val clamped = value.coerceIn(spec.min, spec.max)
    val steps = Math.round((clamped - spec.min).toDouble() / spec.step).toInt()
    return (spec.min + steps * spec.step).coerceIn(spec.min, spec.max)
}

/** A sheet with the goal's value between − and +, and Save. Nothing is sent until Save. */
@Composable
internal fun GoalStepperSheet(spec: GoalSpec, value: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(spec.key) { mutableIntStateOf(snap(value, spec)) }
    AuraSheet(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(auraIcon(spec.symbol), contentDescription = null, tint = spec.color, modifier = Modifier.size(24.dp))
                Text(spec.title, style = AuraType.heading, color = Color.Black)
            }
            AuraCard(padding = 22.dp, spacing = 14.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StepButton("minus", "Less", enabled = draft > spec.min, tag = "goal-stepper-minus") {
                        draft = snap(draft - spec.step, spec)
                    }
                    Text(
                        spec.text(draft),
                        style = AuraType.system(30, FontWeight.Bold),
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).testTag("goal-stepper-value"),
                    )
                    StepButton("plus", "More", enabled = draft < spec.max, tag = "goal-stepper-plus") {
                        draft = snap(draft + spec.step, spec)
                    }
                }
                Text(
                    "${spec.text(spec.min)} to ${spec.text(spec.max)}",
                    style = AuraType.caption,
                    color = AuraColors.metricGrey,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(spec.footnote, style = AuraType.callout, color = AuraColors.footnote, modifier = Modifier.padding(horizontal = 20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f), style = PillStyle.Secondary, fills = true)
                PillButton(
                    "Save",
                    onClick = { onSave(draft) },
                    modifier = Modifier.weight(1f).testTag("goal-stepper-save"),
                    fills = true,
                )
            }
        }
    }
}

@Composable
internal fun StepButton(symbol: String, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (enabled) AuraColors.background else AuraColors.track)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (symbol == "minus") "−" else "+",
            style = AuraType.system(26, FontWeight.Medium),
            color = if (enabled) AuraColors.blue else AuraColors.gray,
        )
    }
}

/** A thin rounded bar, 0...1, on the grey track. */
@Composable
internal fun GoalBar(fraction: Double?, color: Color, modifier: Modifier = Modifier) {
    val shown = (fraction ?: 0.0).coerceIn(0.0, 1.0).toFloat()
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(AuraColors.track)) {
        if (shown > 0f) {
            Box(Modifier.fillMaxWidth(shown).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}
