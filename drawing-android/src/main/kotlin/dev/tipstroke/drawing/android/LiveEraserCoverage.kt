package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.geometry.TileCoordinate
import kotlin.math.*

/** Reusable buffers for one live gesture; no blur bitmaps or full-tile pixel scans per sample. */
internal class LiveEraserCoverage(private val tileSize: Int, private val useNative: Boolean = NativeEraser.available) {
    companion object {
        fun bounds(stroke: CompletedStroke): dev.tipstroke.core.geometry.Rect {
            val brush = stroke.style.brush
            val size = stroke.samples.maxOf { stroke.style.sizePx * brush.pressureToSize.map(it.pressure) }
            // Match the segment kernel's maximum outer radius, including pixel centers.
            val radius = size * (.5f + .75f * (1f - brush.hardness)) + 1.5f
            return dev.tipstroke.core.geometry.Rect(stroke.samples.minOf { it.position.x },
                stroke.samples.minOf { it.position.y }, stroke.samples.maxOf { it.position.x },
                stroke.samples.maxOf { it.position.y }).expanded(radius)
        }
    }

    private class TileCoverage(size: Int) {
        val alpha = ByteArray(size)
        val distanceSquared = FloatArray(size) { Float.POSITIVE_INFINITY }
    }
    private val coverage = mutableMapOf<TileCoordinate, TileCoverage>()
    private val pixels = IntArray(tileSize * tileSize)
    private val originals = IntArray(tileSize * tileSize)

    fun apply(destination: Bitmap, before: Bitmap, coordinate: TileCoordinate, stroke: CompletedStroke) {
        val tile = coverage.getOrPut(coordinate) { TileCoverage(tileSize * tileSize) }
        val mask = tile.alpha
        val style = stroke.style
        val brush = style.brush
        val left = coordinate.x * tileSize
        val top = coordinate.y * tileSize
        val softness = 1f - brush.hardness
        val strength = style.opacity * style.color.alpha * 255f
        val samples = stroke.samples
        // Read each bitmap once per batch, not once per historical sample. The
        // reusable buffers are gesture-wide; they do not add storage to every tile.
        val bounds = bounds(stroke)
        val readLeft = floor(bounds.left - left).toInt().coerceIn(0, tileSize)
        val readTop = floor(bounds.top - top).toInt().coerceIn(0, tileSize)
        val readRight = ceil(bounds.right - left).toInt().coerceIn(0, tileSize)
        val readBottom = ceil(bounds.bottom - top).toInt().coerceIn(0, tileSize)
        if (readRight <= readLeft || readBottom <= readTop) return
        val offset = readTop * tileSize + readLeft
        before.getPixels(originals, offset, tileSize, readLeft, readTop, readRight - readLeft, readBottom - readTop)
        destination.getPixels(pixels, offset, tileSize, readLeft, readTop, readRight - readLeft, readBottom - readTop)
        var changedLeft = tileSize
        var changedTop = tileSize
        var changedRight = 0
        var changedBottom = 0
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
            var changed = false
            val inverseLengthSquared = if (lengthSquared > 0f) 1f / lengthSquared else 0f
            if (useNative) {
                changed = NativeEraser.applySegment(pixels, originals, mask, tile.distanceSquared,
                    tileSize, x0, x1, y0, y1, ax, ay, dx, dy, inverseLengthSquared,
                    sizeA, sizeB, alphaA, alphaB, brush.hardness)
            } else {
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val px = x + .5f - ax
                        val py = y + .5f - ay
                        val t = if (lengthSquared > 0f) ((px * dx + py * dy) * inverseLengthSquared).coerceIn(0f, 1f) else 1f
                        val ex = px - t * dx
                        val ey = py - t * dy
                        val distanceSquared = ex * ex + ey * ey
                        val maskIndex = y * tileSize + x
                        val original = originals[maskIndex]
                        if (original ushr 24 == 0) continue
                        val previousDistance = tile.distanceSquared[maskIndex]
                        val nearer = distanceSquared < previousDistance - .0001f
                        val tied = abs(distanceSquared - previousDistance) <= .0001f
                        // Completed centerline coverage wins over more distant caps. Reject
                        // these pixels before the square root, pressure and falloff work.
                        if (!nearer && !tied) continue
                        val size = sizeA + (sizeB - sizeA) * t
                        val outer = size * (.5f + .75f * softness) + .5f
                        val inner = maxOf(0f, size * .5f * brush.hardness - .5f)
                        val old = mask[maskIndex].toInt() and 255
                        val alpha = if (distanceSquared >= outer * outer) 0 else {
                            val falloff = if (distanceSquared <= inner * inner) 1f else {
                                val u = ((outer - sqrt(distanceSquared)) / (outer - inner)).coerceIn(0f, 1f)
                                u * u * (3f - 2f * u)
                            }
                            ((alphaA + (alphaB - alphaA) * t) * falloff).roundToInt().coerceIn(0, 255)
                        }
                        // Pressure belongs to the nearest part of the continuous centerline.
                        // A previous endpoint's round cap is provisional: keeping its maximum
                        // alpha forever leaves circular bands when the next sample is lighter.
                        val combined = when {
                            nearer -> alpha
                            tied -> maxOf(old, alpha)
                            else -> old
                        }
                        if (nearer) tile.distanceSquared[maskIndex] = distanceSquared
                        if (combined == old) continue
                        mask[maskIndex] = combined.toByte()
                        changed = true
                        val remainingAlpha = ((original ushr 24) * (255 - combined) + 127) / 255
                        pixels[maskIndex] = if (remainingAlpha == 0) 0 else
                            (original and 0x00ffffff) or (remainingAlpha shl 24)
                    }
                }
            }
            if (changed) {
                changedLeft = minOf(changedLeft, x0); changedTop = minOf(changedTop, y0)
                changedRight = maxOf(changedRight, x1); changedBottom = maxOf(changedBottom, y1)
            }
        }
        if (changedRight > changedLeft && changedBottom > changedTop) {
            destination.setPixels(pixels, changedTop * tileSize + changedLeft, tileSize,
                changedLeft, changedTop, changedRight - changedLeft, changedBottom - changedTop)
        }
    }
}
