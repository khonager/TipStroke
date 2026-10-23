package dev.tipstroke.drawing.android

import android.graphics.*
import dev.tipstroke.core.drawing.StrokeCoverage
import dev.tipstroke.core.geometry.TileCoordinate

/** Captures only reached source tiles, before the destination changes them. */
internal class ReferenceStrokes(
    private val source: TileStore,
    val sameLayer: Boolean,
    private val coverage: MutableMap<TileCoordinate, ByteArray?> = mutableMapOf(),
) : StrokeCoverage {
    fun forTarget(target: TileStore) = ReferenceStrokes(source, source === target, coverage)
    private fun tile(coordinate: TileCoordinate): ByteArray? {
        if (!coverage.containsKey(coordinate)) coverage[coordinate] = source.referenceAlpha(coordinate)
        return coverage[coordinate]
    }
    override fun alphaAt(x: Int, y: Int): Int {
        if (x !in 0 until source.canvasWidth || y !in 0 until source.canvasHeight) return 0
        return tile(TileCoordinate(x / source.tileSize, y / source.tileSize))
            ?.get(y % source.tileSize * source.tileSize + x % source.tileSize)?.toInt()?.and(255) ?: 0
    }
    fun drawCoverage(canvas: Canvas, bounds: Rect) {
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        val size = source.tileSize
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        try {
            for (y in Math.floorDiv(bounds.top, size)..Math.floorDiv(bounds.bottom - 1, size)) {
                for (x in Math.floorDiv(bounds.left, size)..Math.floorDiv(bounds.right - 1, size)) {
                    val alpha = tile(TileCoordinate(x, y))
                    pixels.indices.forEach { i ->
                        val value = alpha?.get(i)?.toInt()?.and(255) ?: 0
                        pixels[i] = Color.argb(if (sameLayer && value > 0) 255 else value, 255, 255, 255)
                    }
                    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
                    canvas.drawBitmap(bitmap, (x * size).toFloat(), (y * size).toFloat(), paint)
                }
            }
        } finally { bitmap.recycle() }
    }
}
