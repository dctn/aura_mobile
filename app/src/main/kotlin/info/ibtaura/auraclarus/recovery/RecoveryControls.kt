package info.ibtaura.auraclarus.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.auraIcon

// Small pieces shared by the Recovery tab and its Edit and Filter sheets.

/**
 * Text that keeps its line breaks and never cuts a word: it shrinks (down to [minScale]) until it fits in [maxLines]
 * at the width it is given, like SwiftUI's `minimumScaleFactor`.
 */
@Composable
internal fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    minScale: Float = 0.75f,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val width = constraints.maxWidth
        val fitted = remember(text, style, width, maxLines, measurer) {
            if (width == Constraints.Infinity) return@remember style
            var scale = 1f
            while (scale >= minScale - 0.001f) {
                val candidate = style.copy(fontSize = style.fontSize * scale)
                // A line that has to wrap adds a line past maxLines, which counts as overflow.
                val result = measurer.measure(text, candidate, maxLines = maxLines, constraints = Constraints(maxWidth = width))
                if (!result.hasVisualOverflow) return@remember candidate
                scale -= 0.05f
            }
            style.copy(fontSize = style.fontSize * minScale)
        }
        Text(text, style = fitted, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** The sheets' top line: a text button each side of a centred title (iOS navigation bar in a sheet). */
@Composable
internal fun SheetHeader(
    title: String,
    leadingTitle: String?,
    onLeading: () -> Unit,
    trailingTitle: String,
    onTrailing: () -> Unit,
    titleTag: String,
    leadingTag: String,
    trailingTag: String,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        if (leadingTitle != null) {
            HeaderButton(leadingTitle, FontWeight.Normal, onLeading, Modifier.align(Alignment.CenterStart).testTag(leadingTag))
        }
        Text(
            title,
            style = AuraType.system(17, FontWeight.SemiBold),
            color = Color.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).semantics { heading() }.testTag(titleTag),
        )
        HeaderButton(trailingTitle, FontWeight.SemiBold, onTrailing, Modifier.align(Alignment.CenterEnd).testTag(trailingTag))
    }
}

@Composable
private fun HeaderButton(title: String, weight: FontWeight, onClick: () -> Unit, modifier: Modifier) {
    Text(
        title,
        style = AuraType.system(17, weight),
        color = AuraColors.blue,
        modifier = modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/** A capsule chip that fills blue with a tick when picked. An optional icon leads in its own colour. */
@Composable
internal fun RecoveryChip(
    title: String,
    isSelected: Boolean,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    symbolColor: Color = AuraColors.blue,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .heightIn(min = 40.dp)
            .alpha(if (enabled || isSelected) 1f else 0.45f)
            .clip(CircleShape)
            .background(if (isSelected) AuraColors.blue else AuraColors.card)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { selected = isSelected }
            .testTag(tag)
            .padding(start = if (symbol != null || isSelected) 12.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            isSelected -> Icon(auraIcon("checkmark"), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            symbol != null -> Icon(auraIcon(symbol), contentDescription = null, tint = symbolColor, modifier = Modifier.size(18.dp))
        }
        Text(
            title,
            style = AuraType.system(15, if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (isSelected) Color.White else Color.Black,
            maxLines = 1,
        )
    }
}
