package dev.tipstroke.core.drawing

import dev.tipstroke.core.model.*
import kotlin.math.*

/** Shared coverage generation. Platform engines upload these bounded images once per tip. */
class BrushTip(val width: Int, val height: Int, private val alpha: ByteArray) {
    fun coverage(index: Int): Int = alpha[index].toInt() and 255
    companion object {
        fun shape(config: StampBrush, hardness: Float): BrushTip {
            val texture = config.shapeTexture.takeIf { config.shape == StampShape.IMAGE }
            val width = texture?.width ?: 128; val height = texture?.height ?: 128
            val pixels = ByteArray((width + 2) * (height + 2))
            for (y in 0 until height) for (x in 0 until width) {
                val nx = (x + .5f) / width * 2f - 1f; val ny = (y + .5f) / height * 2f - 1f
                val radius = when (config.shape) { StampShape.SQUARE -> max(abs(nx), abs(ny)); StampShape.OVAL -> hypot(nx, ny * 2); else -> hypot(nx, ny) }
                val soft = ((1f - radius) / (1f - hardness).coerceAtLeast(.015f)).coerceIn(0f, 1f)
                val value = texture?.coverage(x, y) ?: if (config.shape == StampShape.BRISTLES)
                    (255 * soft * if ((x * 17 + y / 12) % 13 < 6) 1f else .08f).roundToInt() else (255 * soft).roundToInt()
                pixels[(y + 1) * (width + 2) + x + 1] = value.toByte()
            }
            return BrushTip(width + 2, height + 2, pixels)
        }
        fun grain(config: StampBrush): BrushTip {
            val texture = config.grainTexture.takeIf { config.grain == StampGrain.IMAGE }
            val width = texture?.width ?: 64; val height = texture?.height ?: 64
            return BrushTip(width, height, ByteArray(width * height) { index ->
                val x = index % width; val y = index / width
                val noise = (((index * 1103515245 + 12345) ushr 16) and 255) / 255f
                val value = when (config.grain) {
                    StampGrain.NONE -> 1f
                    StampGrain.NOISE -> noise
                    StampGrain.PAPER -> (.25f + .5f * noise + .25f * sin(x * .9f) * sin(y * .7f)).coerceIn(0f, 1f)
                    StampGrain.LINES -> if (y % 8 < 3) .15f else 1f
                    StampGrain.IMAGE -> requireNotNull(texture).coverage(x, y) / 255f
                }
                ((1f - config.grainDepth + config.grainDepth * value) * 255).roundToInt().toByte()
            })
        }
    }
}
