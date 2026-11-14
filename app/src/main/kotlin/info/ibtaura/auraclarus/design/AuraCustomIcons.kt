package info.ibtaura.auraclarus.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-drawn stand-ins for SF Symbols that Material has no close match for. All are 24 x 24 and drawn in black, so
 * `Icon(tint = ...)` colours them like any Material icon.
 *
 * - [HeartArrowClockwiseFill]: "arrow.clockwise.heart.fill", a filled heart with a clockwise arrow cut out of it.
 * - [Brain]: "brain", a brain outline seen from the front: two lobed halves and their folds.
 * - [BrainHeadProfile]: "brain.head.profile", a head in profile with a small brain inside.
 */
object AuraCustomIcons {
    val HeartArrowClockwiseFill: ImageVector by lazy { heartArrowClockwise() }
    val Brain: ImageVector by lazy { brain() }
    val BrainHeadProfile: ImageVector by lazy { brainHeadProfile() }
}

private fun builder(name: String) = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
)

private val ink = SolidColor(Color.Black)

/** The heart and, in the same even-odd path, the arrow, so the arrow shows as a hole the background fills. */
private fun heartArrowClockwise(): ImageVector = builder("HeartArrowClockwiseFill").apply {
    path(fill = ink, pathFillType = PathFillType.EvenOdd) {
        // The heart (Material "Favorite").
        moveTo(12f, 21.35f)
        lineTo(10.55f, 20.03f)
        curveTo(5.4f, 15.36f, 2f, 12.28f, 2f, 8.5f)
        curveTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f)
        curveTo(9.24f, 3f, 10.91f, 3.81f, 12f, 5.09f)
        curveTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f)
        curveTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f)
        curveTo(22f, 12.28f, 18.6f, 15.36f, 13.45f, 20.04f)
        close()
        // The arrow: a ring open at the top, its head at 11 o'clock pointing clockwise.
        clockwiseArrow(cx = 12f, cy = 11.7f, outer = 4.0f, inner = 2.4f, startDeg = -50f, endDeg = 250f, headHalf = 2.0f, headLength = 2.2f)
    }
}.build()

/** A filled ring from [startDeg] clockwise to [endDeg] (screen angles, 0 = 3 o'clock) with an arrow head at its end. */
private fun PathBuilder.clockwiseArrow(
    cx: Float,
    cy: Float,
    outer: Float,
    inner: Float,
    startDeg: Float,
    endDeg: Float,
    headHalf: Float,
    headLength: Float,
) {
    fun x(r: Float, deg: Float) = cx + r * cos(deg * PI / 180).toFloat()
    fun y(r: Float, deg: Float) = cy + r * sin(deg * PI / 180).toFloat()
    val mid = (outer + inner) / 2
    val large = endDeg - startDeg > 180f
    // Clockwise along the outside.
    moveTo(x(outer, startDeg), y(outer, startDeg))
    arcTo(outer, outer, 0f, large, true, x(outer, endDeg), y(outer, endDeg))
    // The head: out past the ring, to the tip along the clockwise tangent, back in past the ring.
    val rad = endDeg * PI / 180
    val tangentX = (-sin(rad)).toFloat()
    val tangentY = cos(rad).toFloat()
    lineTo(x(mid + headHalf, endDeg), y(mid + headHalf, endDeg))
    lineTo(x(mid, endDeg) + tangentX * headLength, y(mid, endDeg) + tangentY * headLength)
    lineTo(x(mid - headHalf, endDeg), y(mid - headHalf, endDeg))
    lineTo(x(inner, endDeg), y(inner, endDeg))
    // Back anticlockwise along the inside.
    arcTo(inner, inner, 0f, large, false, x(inner, startDeg), y(inner, startDeg))
    close()
}

private fun ImageVector.Builder.strokePath(width: Float, block: PathBuilder.() -> Unit) {
    path(
        fill = null,
        stroke = ink,
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}

/** A front view: each half is a row of round lobes, with the folds drawn inside. */
private fun brain(): ImageVector = builder("Brain").apply {
    // Left half, anticlockwise from the top of the middle line, every lobe bulging outwards.
    strokePath(1.6f) {
        moveTo(12f, 5.2f)
        arcTo(2.1f, 2.1f, 0f, false, false, 8.2f, 4.6f)
        arcTo(2.3f, 2.3f, 0f, false, false, 5.0f, 7.3f)
        arcTo(2.2f, 2.2f, 0f, false, false, 3.8f, 11.2f)
        arcTo(2.2f, 2.2f, 0f, false, false, 4.6f, 15.2f)
        arcTo(2.2f, 2.2f, 0f, false, false, 7.4f, 18.2f)
        arcTo(2.1f, 2.1f, 0f, false, false, 12f, 18.9f)
        close()
    }
    // Right half, the mirror image.
    strokePath(1.6f) {
        moveTo(12f, 5.2f)
        arcTo(2.1f, 2.1f, 0f, false, true, 15.8f, 4.6f)
        arcTo(2.3f, 2.3f, 0f, false, true, 19.0f, 7.3f)
        arcTo(2.2f, 2.2f, 0f, false, true, 20.2f, 11.2f)
        arcTo(2.2f, 2.2f, 0f, false, true, 19.4f, 15.2f)
        arcTo(2.2f, 2.2f, 0f, false, true, 16.6f, 18.2f)
        arcTo(2.1f, 2.1f, 0f, false, true, 12f, 18.9f)
    }
    // The folds.
    strokePath(1.4f) {
        moveTo(8.9f, 7.4f)
        curveTo(7.6f, 7.7f, 7.1f, 8.8f, 7.4f, 10.0f)
        moveTo(12f, 10.3f)
        curveTo(10.5f, 10.3f, 9.5f, 11.1f, 9.4f, 12.4f)
        moveTo(6.2f, 13.0f)
        curveTo(7.4f, 12.6f, 8.6f, 13.1f, 9.0f, 14.2f)
        moveTo(9.4f, 16.6f)
        curveTo(9.7f, 15.6f, 10.7f, 15.1f, 12f, 15.1f)

        moveTo(15.1f, 7.4f)
        curveTo(16.4f, 7.7f, 16.9f, 8.8f, 16.6f, 10.0f)
        moveTo(12f, 10.3f)
        curveTo(13.5f, 10.3f, 14.5f, 11.1f, 14.6f, 12.4f)
        moveTo(17.8f, 13.0f)
        curveTo(16.6f, 12.6f, 15.4f, 13.1f, 15.0f, 14.2f)
        moveTo(14.6f, 16.6f)
        curveTo(14.3f, 15.6f, 13.3f, 15.1f, 12f, 15.1f)
    }
}.build()

/** A head facing right (back of the head on the left, nose and chin on the right) with a small brain inside. */
private fun brainHeadProfile(): ImageVector = builder("BrainHeadProfile").apply {
    strokePath(1.6f) {
        // Neck at the back, up and over the head, down the forehead to the nose, lips and chin, then the neck's front.
        moveTo(7.2f, 21.2f)
        curveTo(5.6f, 18.4f, 3.6f, 15.4f, 3.8f, 10.6f)
        curveTo(4.1f, 5.7f, 7.8f, 2.8f, 12f, 2.8f)
        curveTo(16.4f, 2.8f, 19.4f, 5.9f, 19.6f, 9.8f)
        lineTo(21.1f, 12.5f)
        curveTo(21.35f, 12.95f, 21.15f, 13.35f, 20.7f, 13.45f)
        lineTo(19.8f, 13.6f)
        lineTo(19.8f, 15.9f)
        curveTo(19.8f, 17.1f, 18.9f, 18.0f, 17.7f, 18.0f)
        lineTo(15.6f, 18.0f)
        lineTo(15.6f, 21.2f)
    }
    strokePath(1.35f) {
        // The brain: lobes clockwise from its lower back, a flat underside.
        moveTo(8.3f, 11.7f)
        arcTo(1.7f, 1.7f, 0f, false, true, 7.7f, 8.7f)
        arcTo(1.7f, 1.7f, 0f, false, true, 9.7f, 6.3f)
        arcTo(1.6f, 1.6f, 0f, false, true, 12.7f, 6.0f)
        arcTo(1.6f, 1.6f, 0f, false, true, 15.1f, 7.7f)
        arcTo(1.6f, 1.6f, 0f, false, true, 15.4f, 10.7f)
        arcTo(1.6f, 1.6f, 0f, false, true, 13.2f, 12.6f)
        lineTo(10.4f, 12.6f)
        arcTo(1.3f, 1.3f, 0f, false, true, 8.3f, 11.7f)
        close()
        // Its folds.
        moveTo(11.4f, 7.7f)
        curveTo(11.4f, 8.8f, 12.3f, 9.3f, 13.2f, 9.3f)
        moveTo(10.0f, 10.6f)
        curveTo(10.6f, 9.9f, 11.6f, 9.8f, 12.4f, 10.3f)
    }
}.build()
