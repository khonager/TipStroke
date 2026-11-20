package dev.tipstroke.core.drawing

import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.pencilTiltResponse
import kotlin.math.*
import kotlin.random.Random

data class PencilTipDynamics(val width: Float, val height: Float, val alpha: Int)

object Graphite {
    fun contact(brush: BrushPreset, size: Float, opacity: Float, x: Float, y: Float,
        pressure: Float, opacityPressure: Float, tilt: Float, speedScale: Float): PencilTipDynamics {
        val tiltResponse = brush.pencilTiltResponse(tilt)
        val widthMultiplier = 1f +
            (10f - 1f) * brush.pencilShadeSize * tiltResponse
        val heightMultiplier = 1f +
            (6.5f - 1f) * brush.pencilShadeSize * tiltResponse
        val tiltOpacity = 1f + (brush.pencilShadeOpacity - 1f) * tiltResponse
        val rawPressureSize = brush.pressureToSize.map(pressure)
        // The exposed side of a real pencil remains broad under light pressure; pressure mostly
        // controls how much graphite it deposits. Keep pressure sizing for the upright point,
        // then progressively decouple contact width as the barrel approaches the paper.
        val pressureDecoupling = sqrt(tiltResponse) * .95f * brush.pencilShadeSize.coerceIn(0f, 1f)
        val pressureSize = rawPressureSize + (1f - rawPressureSize) * pressureDecoupling
        val edgeVariation = 1f +
            sin(x * .071f + y * .113f) * .006f +
            sin(x * .029f - y * .053f) * .003f
        val densityVariation = 1f + sin(x * .047f + y * .031f) * .015f
        val baseDiameter = (
            size * .18f * brush.pencilPointSize
            ).coerceAtLeast(.5f)
        return PencilTipDynamics(
            width = baseDiameter * pressureSize * speedScale * widthMultiplier * edgeVariation,
            height = baseDiameter * pressureSize * speedScale * heightMultiplier * edgeVariation,
            alpha = (opacity * brush.pressureToOpacity.map(opacityPressure) * tiltOpacity * densityVariation * 255f)
                .roundToInt().coerceIn(0, 255),
        )
    }

    fun grain(): BrushTip {
        val size = 256
        val pixels = ByteArray(size * size)
        val fineRandom = Random(0x19D7)
        val fine = FloatArray(size * size) { fineRandom.nextFloat() * 2f - 1f }
        val tooth = valueNoiseGrid(size, 5, 0x32B1)
        for (y in 0 until size) for (x in 0 until size) {
            val index = y * size + x
            val fiber = sin(y * .31f + sin(x * .047f) * 1.4f) * .015f
            // Fine paper tooth varies coverage locally without broad light/dark blotches.
            // Large shade areas should look even from a distance and granular up close.
            val graphiteCoverage = .7f + fine[index] * .18f + tooth[index] * .025f + fiber
            val alpha = (((graphiteCoverage - .42f) / .48f).coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[y * size + x] = alpha.toByte()
        }
        return BrushTip(size, size, pixels)
    }

    private fun valueNoiseGrid(size: Int, cellSize: Int, seed: Int): FloatArray {
        val columns = size / cellSize + 2
        val rows = size / cellSize + 2
        val random = Random(seed)
        val values = FloatArray(columns * rows) { random.nextFloat() * 2f - 1f }
        return FloatArray(size * size) { index ->
            val x = index % size
            val y = index / size
            val gridX = x / cellSize
            val gridY = y / cellSize
            val localX = smooth((x % cellSize) / cellSize.toFloat())
            val localY = smooth((y % cellSize) / cellSize.toFloat())
            val top = lerp(values[gridY * columns + gridX], values[gridY * columns + gridX + 1], localX)
            val bottom = lerp(values[(gridY + 1) * columns + gridX], values[(gridY + 1) * columns + gridX + 1], localX)
            lerp(top, bottom, localY)
        }
    }

    private fun smooth(value: Float) = value * value * (3f - 2f * value)
    private fun lerp(start: Float, end: Float, amount: Float) = start + (end - start) * amount
}
