package info.ibtaura.auraclarus.recovery

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.Shelf
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.hex
import info.ibtaura.auraclarus.design.symbol
import kotlin.math.floor
import kotlin.math.roundToInt

// A port of Recovery/RecoveryCards.swift: the Now cards and their mesh gradient.

/** The Now cards' two colour sets, sampled from iPhone 18 Pro - 5: nap cards and coffee cards. */
internal enum class RecoveryCardStyle(private val mesh: LongArray, private val header: LongArray) {
    Cool(
        longArrayOf(
            0x506AFF, 0x6650FF, 0xAE43FF, 0xE741E7,
            0x36A1FF, 0x306DFF, 0x573EFF, 0xA539FF,
            0x29C7F4, 0x2795FE, 0x3E45FF, 0x7034FF,
            0x21DDDA, 0x1CB9EB, 0x1E5CFB, 0x3F37FF,
        ),
        longArrayOf(0x3F92FE, 0x3E73FF, 0x3D50FE, 0x8A3EFE, 0xC23EF8),
    ),
    Warm(
        longArrayOf(
            0xC0F900, 0xA3FF00, 0x95FF00, 0x94FF00,
            0xEDEE00, 0xE6F400, 0xDCF300, 0xD0F700,
            0xFFBF00, 0xFCC800, 0xF7CC00, 0xF7E000,
            0xFE330B, 0xFF7600, 0xFF9C00, 0xFCBD00,
        ),
        longArrayOf(0xFF9C00, 0xFCC800, 0xE6F400, 0xA3FF00, 0x95FF00),
    );

    /** The detail page's header, left to right. */
    val headerColors: List<Color> get() = header.map { hex(it) }

    /** The 4x4 mesh drawn smoothly into a small bitmap once; the card scales it up with filtering. */
    val meshImage: ImageBitmap by lazy { renderMesh(mesh) }

    companion object {
        fun of(mode: Mode): RecoveryCardStyle {
            if (mode.id == "caffeine-nap") return Warm
            return when (mode.shelf) {
                Shelf.fuel, Shelf.movement, Shelf.outside, Shelf.people -> Warm
                Shelf.focus, Shelf.breathe, Shelf.meditate, Shelf.sleep, Shelf.rest, Shelf.mind -> Cool
            }
        }
    }
}

private const val MESH_W = 56
private const val MESH_H = 78

/**
 * SwiftUI's `MeshGradient(width: 4, height: 4)` with evenly spaced points: the colours meet along smooth curves.
 * Done here with Catmull-Rom interpolation through the 16 colours, row then column, clamped at the edges.
 */
private fun renderMesh(colors: LongArray): ImageBitmap {
    val channels = Array(3) { c -> DoubleArray(16) { i -> ((colors[i] shr (16 - 8 * c)) and 0xFF).toDouble() } }
    val pixels = IntArray(MESH_W * MESH_H)
    for (y in 0 until MESH_H) {
        val v = y.toDouble() / (MESH_H - 1)
        for (x in 0 until MESH_W) {
            val u = x.toDouble() / (MESH_W - 1)
            var argb = 0xFF shl 24
            for (c in 0 until 3) {
                val value = sample(channels[c], u, v).roundToInt().coerceIn(0, 255)
                argb = argb or (value shl (16 - 8 * c))
            }
            pixels[y * MESH_W + x] = argb
        }
    }
    return Bitmap.createBitmap(pixels, MESH_W, MESH_H, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun sample(grid: DoubleArray, u: Double, v: Double): Double {
    val rows = DoubleArray(4) { row -> spline({ col -> grid[row * 4 + col] }, u) }
    return spline({ rows[it] }, v)
}

/** Catmull-Rom through 4 evenly spaced values at 0, 1/3, 2/3, 1. */
private fun spline(at: (Int) -> Double, t: Double): Double {
    val g = (t * 3).coerceIn(0.0, 3.0)
    val i = floor(g).toInt().coerceIn(0, 2)
    val f = g - i
    val p0 = at((i - 1).coerceAtLeast(0))
    val p1 = at(i)
    val p2 = at(i + 1)
    val p3 = at((i + 2).coerceAtMost(3))
    val f2 = f * f
    val f3 = f2 * f
    return 0.5 * ((2 * p1) + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f2 + (-p0 + 3 * p1 - 3 * p2 + p3) * f3)
}

@Composable
internal fun RecoveryMesh(style: RecoveryCardStyle, modifier: Modifier = Modifier) {
    val image = style.meshImage
    Canvas(modifier) {
        drawImage(
            image,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
}

/** One card on the Now carousel. */
@Composable
internal fun RecoveryNowCard(mode: Mode, modifier: Modifier = Modifier) {
    val symbol = if (mode.id == "caffeine-nap") "fork.knife" else mode.shelf.symbol
    Box(modifier.size(167.dp, 231.dp).clip(RoundedCornerShape(22.dp))) {
        RecoveryMesh(RecoveryCardStyle.of(mode), Modifier.fillMaxSize())
        Icon(
            auraIcon(symbol),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp).size(23.dp),
        )
        Text(
            mode.nowTitle ?: mode.name,
            style = AuraType.system(15, FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
        )
    }
}
