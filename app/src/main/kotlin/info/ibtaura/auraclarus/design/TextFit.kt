package info.ibtaura.auraclarus.design

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.ceil

/**
 * Shrink-to-fit for labels (iOS `minimumScaleFactor`), in 5% steps. Measuring text is the slow part of drawing a
 * Recovery shelf, so this measures as little as it can and remembers each answer for the whole process: a tab that
 * comes back, or a tile that scrolls back in, measures nothing.
 */
internal object TextFit {
    private const val STEP = 0.05f
    private val scales = HashMap<String, Float>()

    /**
     * The largest scale (1, 0.95, ... down to [minScale]) at which every line fits [width] px on one line, or null
     * when even [minScale] is too wide. Usually two measures: each line once at full size (text width grows in step
     * with font size, which gives the starting scale), then one check at that scale.
     */
    fun oneLineScale(
        measurer: TextMeasurer,
        lines: List<String>,
        style: TextStyle,
        width: Int,
        minScale: Float,
        density: Density,
    ): Float? {
        if (width == Constraints.Infinity) return 1f
        val key = "1|${lines.joinToString("\u0000")}|$width|${style.fontSize}|${style.fontWeight}|$minScale|" +
            "${density.density}|${density.fontScale}"
        cached(key)?.let { return it.takeIf { s -> s > 0f } }
        fun fits(scale: Float): Boolean = lines.none { line ->
            measurer.measure(
                line,
                style.copy(fontSize = style.fontSize * scale),
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(maxWidth = width),
            ).hasVisualOverflow
        }
        val widest = lines.maxOf { line ->
            measurer.measure(line, style, softWrap = false, maxLines = 1).size.width
        }
        var scale = if (widest <= width) 1f else 1f - ceil((1f - width.toFloat() / widest) / STEP - 0.001f) * STEP
        var found: Float? = null
        while (scale >= minScale - 0.001f) {
            if (scale >= 1f || fits(scale)) {
                found = scale
                break
            }
            scale -= STEP
        }
        remember(key, found ?: -1f)
        return found
    }

    /** The largest scale at which [text] fits [width] px within [maxLines] lines, never below [minScale]. */
    fun wrappedScale(
        measurer: TextMeasurer,
        text: String,
        style: TextStyle,
        width: Int,
        maxLines: Int,
        minScale: Float,
        density: Density,
    ): Float {
        if (width == Constraints.Infinity) return 1f
        val key = "w|$text|$width|${style.fontSize}|${style.fontWeight}|$maxLines|$minScale|" +
            "${density.density}|${density.fontScale}"
        cached(key)?.let { return it }
        var scale = 1f
        var found = minScale
        while (scale >= minScale - 0.001f) {
            val candidate = style.copy(fontSize = style.fontSize * scale)
            // A line that has to wrap adds a line past maxLines, which counts as overflow.
            val result = measurer.measure(text, candidate, maxLines = maxLines, constraints = Constraints(maxWidth = width))
            if (!result.hasVisualOverflow) {
                found = scale
                break
            }
            scale -= STEP
        }
        remember(key, found)
        return found
    }

    @Synchronized
    private fun cached(key: String): Float? = scales[key]

    @Synchronized
    private fun remember(key: String, scale: Float) {
        if (scales.size > 2_000) scales.clear()
        scales[key] = scale
    }
}
