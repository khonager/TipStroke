package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.ImageTransform
import kotlin.math.ceil
import kotlin.math.floor

internal fun ImageTransform.reduced(factor: Float) = copy(
    centerX = centerX * factor, centerY = centerY * factor, scale = scale * factor,
)

/** Capture/commit on the surface thread; prepare on a worker. Originals survive any failure.
 * COW leases pin pixels without copying whole layers; cold cels stay encoded throughout.
 */
internal class ResolutionReduction(
    private val stack: LayerStack,
    private val animation: AnimationRuntime?,
    val factor: Float,
    private val storage: CelStorage,
) {
    init { require(factor == .5f || factor == .25f) }
    val width = ceil(stack.canvasWidth * factor).toInt()
    val height = ceil(stack.canvasHeight * factor).toInt()
    private val active = stack.layers.filterIsInstance<RasterLayerRuntime>().mapTo(mutableSetOf()) { it.tiles }
    private val leases = (active + animation?.rasterCels.orEmpty().values.flatMap { it.values })
        .associateWith { it.duplicate() }
    private val encoded = linkedMapOf<TileStore, Map<TileCoordinate, EncodedTile>>()
    private val decoded = linkedMapOf<TileStore, Map<TileCoordinate, Bitmap>>()

    fun prepare() {
        require(width >= 16 && height >= 16) { "Canvas must remain at least 16 × 16 pixels" }
        leases.forEach { (original, lease) ->
            encoded[original] = resample(lease, width, height, factor, storage)
        }
        active.forEach { original -> decoded[original] = TileStore.decodeTiles(encoded.getValue(original)) }
    }

    fun commit() {
        val replacements = leases.keys.associateWith { original ->
            TileStore(width, height).apply {
                val backing = encoded.getValue(original)
                deferTiles(backing)
                decoded.remove(original)?.let { installDecoded(backing, it) }
            }
        }
        animation?.saveActiveImageTransforms()
        animation?.applyResolution(replacements, factor)
        stack.layers.forEach { layer -> when (layer) {
            is RasterLayerRuntime -> layer.tiles = replacements.getValue(layer.tiles)
            is ImageLayerRuntime -> { layer.transform = layer.transform.reduced(factor); layer.mask.history.clear() }
        } }
        stack.canvasWidth = width
        stack.canvasHeight = height
        leases.keys.forEach(TileStore::discard)
    }

    fun release() {
        leases.values.forEach(TileStore::discard)
        decoded.values.forEach { tiles -> tiles.values.forEach(Bitmap::recycle) }
        decoded.clear()
    }

    companion object {
        /** One output tile plus its source window (at most 1026² pixels at 25%). A halo
         * lets filtering cross source tile edges without seams; no full-canvas bitmap.
         */
        internal fun resample(source: TileStore, width: Int, height: Int, factor: Float,
            storage: CelStorage): Map<TileCoordinate, EncodedTile> {
            val resident = source.residentTiles()
            val backing = source.encodedBacking
            val coordinates = backing?.keys ?: resident.keys
            val destinations = linkedSetOf<TileCoordinate>()
            coordinates.forEach { coordinate ->
                val left = floor(coordinate.x * 256 * factor).toInt().coerceAtLeast(0)
                val top = floor(coordinate.y * 256 * factor).toInt().coerceAtLeast(0)
                val right = ceil(minOf((coordinate.x + 1) * 256, source.canvasWidth) * factor).toInt().coerceAtMost(width)
                val bottom = ceil(minOf((coordinate.y + 1) * 256, source.canvasHeight) * factor).toInt().coerceAtMost(height)
                if (right > left && bottom > top) for (y in top / 256..(bottom - 1) / 256)
                    for (x in left / 256..(right - 1) / 256) destinations += TileCoordinate(x, y)
            }
            val result = linkedMapOf<TileCoordinate, EncodedTile>()
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            destinations.forEach { target ->
                val left = target.x * 256; val top = target.y * 256
                val right = minOf(left + 256, width); val bottom = minOf(top + 256, height)
                val srcLeft = floor(left / factor).toInt().coerceAtLeast(0)
                val srcTop = floor(top / factor).toInt().coerceAtLeast(0)
                val srcRight = (ceil(right / factor).toInt() + 1).coerceAtMost(source.canvasWidth)
                val srcBottom = (ceil(bottom / factor).toInt() + 1).coerceAtMost(source.canvasHeight)
                val window = Bitmap.createBitmap(srcRight - srcLeft, srcBottom - srcTop, Bitmap.Config.ARGB_8888)
                val output = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(window)
                    for (y in srcTop / 256..(srcBottom - 1) / 256) for (x in srcLeft / 256..(srcRight - 1) / 256) {
                        val key = TileCoordinate(x, y)
                        val tile = resident[key] ?: backing?.get(key)?.decode() ?: continue
                        try { canvas.drawBitmap(tile, (x * 256 - srcLeft).toFloat(), (y * 256 - srcTop).toFloat(), null) }
                        finally { if (key !in resident) tile.recycle() }
                    }
                    Canvas(output).apply {
                        clipRect(0, 0, right - left, bottom - top)
                        drawBitmap(window, Rect(0, 0, window.width, window.height),
                            RectF(srcLeft * factor - left, srcTop * factor - top,
                                srcRight * factor - left, srcBottom * factor - top), paint)
                    }
                    val pixels = IntArray(256 * 256)
                    output.getPixels(pixels, 0, 256, 0, 0, 256, 256)
                    if (pixels.any { it ushr 24 != 0 }) result[target] = storage.store(output)
                } finally { window.recycle(); output.recycle() }
            }
            return result
        }
    }
}
