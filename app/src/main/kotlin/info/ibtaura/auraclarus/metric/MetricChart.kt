package info.ibtaura.auraclarus.metric

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.MetricMaths
import info.ibtaura.aura.core.MetricPoint
import info.ibtaura.aura.core.MetricRange
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The three range buttons: a glass capsule with a white lozenge sliding behind the chosen one (iOS MetricRangeSwitch). */
@Composable
internal fun MetricRangeSwitch(selection: MetricRange, onSelect: (MetricRange) -> Unit, modifier: Modifier = Modifier) {
    val ranges = MetricRange.entries
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.10f))
            .clip(CircleShape)
            .background(AuraColors.glass)
            .padding(4.dp),
    ) {
        val segment: Dp = maxWidth / ranges.size
        val lozengeX by animateDpAsState(
            segment * selection.ordinal,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "lozenge",
        )
        Box(
            Modifier
                .offset(x = lozengeX)
                .width(segment)
                .height(44.dp)
                .shadow(5.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f))
                .background(Color.White, CircleShape),
        )
        Row(Modifier.fillMaxWidth()) {
            ranges.forEachIndexed { index, range ->
                val chosen = range == selection
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(range) }
                        .semantics {
                            selected = chosen
                            role = Role.Tab
                        }
                        .testTag("metric-range-$index"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        range.title,
                        style = AuraType.system(15, if (chosen) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (chosen) Color.Black else AuraColors.metricGrey,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The chart's height in dp. */
internal val MetricChartHeight = 220.dp

/** Rounded orange bars on grey tracks, one a day. A day with no value shows only its track (iOS MetricBarChart). */
@Composable
internal fun MetricBarChart(
    name: String,
    points: List<MetricPoint>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** The unit the page shows (km or mi...), read out after the chart's name. */
    unit: String = "",
) {
    val fractions = remember(points) { MetricMaths.barFractions(points.map { it.value }) }
    val track = AuraColors.track
    val fill = AuraColors.burnout
    Canvas(
        modifier
            .fillMaxWidth()
            .height(MetricChartHeight)
            .clearAndSetSemantics {
                contentDescription = "$name chart"
                if (unit.isNotEmpty()) stateDescription = "Values in $unit"
            }
            .pointerInput(fractions) {
                detectTapGestures { tap ->
                    val total = size.width.toFloat()
                    val layout = BarLayout(total, fractions.size, density)
                    val slot = layout.bar + layout.gap
                    if (fractions.isEmpty() || slot <= 0f) return@detectTapGestures
                    val index = floor((tap.x + layout.gap / 2) / slot).toInt().coerceIn(0, fractions.size - 1)
                    if (fractions[index] != null) onSelect(index)
                }
            },
    ) {
        val layout = BarLayout(size.width, fractions.size, density)
        val bar = layout.bar
        val radius = CornerRadius(bar / 2, bar / 2)
        fractions.forEachIndexed { index, fraction ->
            val x = index * (bar + layout.gap)
            drawRoundRect(track, topLeft = Offset(x, 0f), size = Size(bar, size.height), cornerRadius = radius)
            if (fraction != null) {
                // Never shorter than a dot, so a measured zero still differs from a day with no value.
                val height = max(bar, fraction.toFloat() * size.height)
                val alpha = if (selectedIndex == null || selectedIndex == index) 1f else 0.4f
                drawRoundRect(
                    fill.copy(alpha = alpha),
                    topLeft = Offset(x, size.height - height),
                    size = Size(bar, height),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/** Bar width and gap in pixels: bars are 60% of their slot, between 2 and 14 dp, spread edge to edge. */
private class BarLayout(total: Float, count: Int, density: Float) {
    val bar: Float = min(14f * density, max(2f * density, total / max(count, 1) * 0.6f))
    val gap: Float = if (count > 1) max(0f, (total - count * bar) / (count - 1)) else 0f
}
