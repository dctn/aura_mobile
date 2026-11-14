package info.ibtaura.auraclarus.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val TrackGray = Color(0xFFE3E3E8)
private val DotOnTrack = Color(0xFFC7C7CC)
private val DotOnFill = Color(0x99FFFFFF)

/**
 * The iOS slider look, drawn by hand: a thin rounded track, blue up to the thumb, small step dots and a round white
 * thumb with a soft shadow. It snaps to whole steps, ticks the haptics once per step, jumps on a tap and grows the
 * thumb a little while it's held. Material's slider has a bar thumb, a gap and stop dots, which didn't match iOS.
 *
 * [steps] counts the stops between the two ends, the same as Material's slider (1..5 with steps = 3).
 * [labels], one per stop, are printed under the track so the whole scale shows; the picked one is blue and bold.
 * Leave it null to number the stops from the range (1..9 gives 1 to 9); pass an empty list for no scale.
 */
@Composable
fun AuraSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    labels: List<String>? = null,
) {
    val haptics = LocalHapticFeedback.current
    val start = valueRange.start
    val end = valueRange.endInclusive
    val stops = steps + 1 // segments between stops
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    var pressed by remember { mutableStateOf(false) }

    fun snap(raw: Float): Float {
        val f = ((raw - start) / (end - start)).coerceIn(0f, 1f)
        return start + (f * stops).roundToInt() / stops.toFloat() * (end - start)
    }

    fun moveTo(x: Float, width: Float, thumbRadius: Float) {
        val usable = (width - thumbRadius * 2).coerceAtLeast(1f)
        val next = snap(start + ((x - thumbRadius) / usable) * (end - start))
        if (next != current) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            change(next)
        }
    }

    val fraction = ((value - start) / (end - start)).coerceIn(0f, 1f)
    val shown by animateFloatAsState(fraction, spring(stiffness = 900f), label = "slider")
    val grow by animateFloatAsState(if (pressed) 1.1f else 1f, label = "thumb")

    val scale = labels ?: (0..stops).map { i -> (start + (end - start) * i / stops).roundToInt().toString() }
    val picked = (fraction * stops).roundToInt()

    Column(modifier.fillMaxWidth()) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                setProgress { target ->
                    val next = snap(target)
                    if (next != current) change(next)
                    finished?.invoke()
                    true
                }
            }
            .pointerInput(start, end, stops) {
                val r = 14.dp.toPx()
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { offset ->
                        moveTo(offset.x, size.width.toFloat(), r)
                        finished?.invoke()
                    },
                )
            }
            .pointerInput(start, end, stops) {
                val r = 14.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { offset -> pressed = true; moveTo(offset.x, size.width.toFloat(), r) },
                    onDragEnd = { pressed = false; finished?.invoke() },
                    onDragCancel = { pressed = false; finished?.invoke() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        moveTo(change.position.x, size.width.toFloat(), r)
                    },
                )
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val r = 14.dp.toPx()
            val trackH = 4.dp.toPx()
            val cy = size.height / 2
            val left = r
            val usable = size.width - r * 2
            val thumbX = left + usable * shown
            val corner = CornerRadius(trackH / 2)

            drawRoundRect(TrackGray, Offset(left - trackH / 2, cy - trackH / 2), Size(usable + trackH, trackH), corner)
            drawRoundRect(AuraColors.blue, Offset(left - trackH / 2, cy - trackH / 2), Size(thumbX - left + trackH, trackH), corner)

            // Step dots, skipped when there are too many to read.
            if (stops in 2..12) {
                for (i in 0..stops) {
                    val x = left + usable * i / stops
                    drawCircle(if (x <= thumbX) DotOnFill else DotOnTrack, radius = 1.5.dp.toPx(), center = Offset(x, cy))
                }
            }

            val thumbR = r * grow
            // Soft shadow in two passes, then the white thumb with a hairline edge.
            drawCircle(Color.Black.copy(alpha = 0.06f), thumbR + 2.5.dp.toPx(), Offset(thumbX, cy + 1.5.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.10f), thumbR + 0.8.dp.toPx(), Offset(thumbX, cy + 1.dp.toPx()))
            drawCircle(Color.White, thumbR, Offset(thumbX, cy))
            drawCircle(Color.Black.copy(alpha = 0.06f), thumbR, Offset(thumbX, cy), style = Stroke(0.5.dp.toPx()))
        }
    }
    if (scale.size == stops + 1) {
        Layout(
            content = {
                scale.forEachIndexed { i, label ->
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = if (i == picked) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (i == picked) AuraColors.blue else AuraColors.gray,
                        maxLines = 1,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
            val width = constraints.maxWidth
            val r = 14.dp.roundToPx()
            val usable = width - r * 2
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(width, height) {
                placeables.forEachIndexed { i, p ->
                    val center = r + usable * i / stops
                    p.placeRelative((center - p.width / 2).coerceIn(0, (width - p.width).coerceAtLeast(0)), 0)
                }
            }
        }
    }
    }
}
