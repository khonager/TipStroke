package dev.tipstroke.core.editor

import dev.tipstroke.core.effects.*
import dev.tipstroke.core.model.LayerId

/** Flat native adapter; UIKit owns tiles, shared core owns filtering and region rules. */
class NativeRasterEffects {
    @Throws(IllegalArgumentException::class)
    fun apply(pixels: IntArray, side: Int, effect: Int, radius: Int, angle: Float, centerX: Float,
        centerY: Float, threshold: Float, intensity: Float, originX: Int, originY: Int,
        documentWidth: Int, documentHeight: Int): IntArray = RasterEffects.apply(pixels, side, side,
        RasterEffectSettings(RasterEffect.entries[effect], radius, angle, centerX, centerY, threshold, intensity),
        originX, originY, documentWidth, documentHeight)

    @Throws(IllegalArgumentException::class)
    fun colorize(width: Int, height: Int, ink: ByteArray, hints: IntArray, gap: Int, threshold: Float): IntArray =
        ColorizeSolver.solve(width, height, ink, hints, ColorizeMask(LayerId("line"), LayerId("hints"), gap, threshold))
}
