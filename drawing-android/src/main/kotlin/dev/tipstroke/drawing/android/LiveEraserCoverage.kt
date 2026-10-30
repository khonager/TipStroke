package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.geometry.TileCoordinate
import kotlin.math.*

/** Reusable buffers for one live gesture; no blur bitmaps or full-tile pixel scans per sample. */
internal class LiveEraserCoverage(private val tileSize: Int) {
    private val coverage = mutableMapOf<TileCoordinate, ByteArray>()
    private val pixels = IntArray(tileSize * tileSize)

    fun apply(destination: Bitmap, before: Bitmap, coordinate: TileCoordinate, stroke: CompletedStroke) {
        val mask = coverage.getOrPut(coordinate) { ByteArray(tileSize * tileSize) }
        val style = stroke.style
        val brush = style.brush
        val left = coordinate.x * tileSize
        val top = coordinate.y * tileSize
        val softness = 1f - brush.hardness
        val strength = style.opacity * style.color.alpha * 255f
        val samples = stroke.samples
        for (index in 0 until maxOf(1, samples.size - 1)) {
            val a = samples[index]
            val b = samples[minOf(index + 1, samples.lastIndex)]
            val ax = a.position.x - left
            val ay = a.position.y - top
            val dx = b.position.x - a.position.x
            val dy = b.position.y - a.position.y
            val lengthSquared = dx * dx + dy * dy
            // Endpoint widths are shared by adjacent segments. Velocity must not shrink the
            // contact at each event boundary and expose notches during a fast sweep.
            val sizeA = style.sizePx * brush.pressureToSize.map(a.pressure)
            val sizeB = style.sizePx * brush.pressureToSize.map(b.pressure)
            val alphaA = strength * brush.pressureToOpacity.map(a.opacityPressure)
            val alphaB = strength * brush.pressureToOpacity.map(b.opacityPressure)
            val extent = maxOf(sizeA, sizeB) * (.5f + .75f * softness) + .5f
            val x0 = floor(minOf(ax, ax + dx) - extent).toInt().coerceIn(0, tileSize)
            val x1 = ceil(maxOf(ax, ax + dx) + extent).toInt().coerceIn(0, tileSize)
            val y0 = floor(minOf(ay, ay + dy) - extent).toInt().coerceIn(0, tileSize)
            val y1 = ceil(maxOf(ay, ay + dy) + extent).toInt().coerceIn(0, tileSize)
            val width = x1 - x0
            if (width <= 0 || y1 <= y0) continue
            before.getPixels(pixels, 0, width, x0, y0, width, y1 - y0)
            var changed = false
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val px = x + .5f - ax
                    val py = y + .5f - ay
                    val t = if (lengthSquared > 0f) ((px * dx + py * dy) / lengthSquared).coerceIn(0f, 1f) else 1f
                    val size = sizeA + (sizeB - sizeA) * t
                    val outer = size * (.5f + .75f * softness) + .5f
                    val inner = maxOf(0f, size * .5f * brush.hardness - .5f)
                    val ex = px - t * dx
                    val ey = py - t * dy
                    val distanceSquared = ex * ex + ey * ey
                    val maskIndex = y * tileSize + x
                    val old = mask[maskIndex].toInt() and 255
                    val alpha = if (distanceSquared >= outer * outer) 0 else {
                        val falloff = if (distanceSquared <= inner * inner) 1f else {
                            val u = ((outer - sqrt(distanceSquared)) / (outer - inner)).coerceIn(0f, 1f)
                            u * u * (3f - 2f * u)
                        }
                        ((alphaA + (alphaB - alphaA) * t) * falloff).roundToInt().coerceIn(0, 255)
                    }
                    val combined = maxOf(old, alpha)
                    if (combined > old) { mask[maskIndex] = combined.toByte(); changed = true }
                    val pixelIndex = (y - y0) * width + x - x0
                    val original = pixels[pixelIndex]
                    val remainingAlpha = ((original ushr 24) * (255 - combined) + 127) / 255
                    pixels[pixelIndex] = if (remainingAlpha == 0) 0 else
                        (original and 0x00ffffff) or (remainingAlpha shl 24)
                }
            }
            if (changed) destination.setPixels(pixels, 0, width, x0, y0, width, y1 - y0)
        }
    }
}
