package dev.tipstroke.drawing.android

import android.graphics.*
import dev.tipstroke.core.geometry.TileCoordinate

/** A visual effect only: the fill is already committed, so later strokes and undo remain normal. */
internal class FillReveal(val masks: Map<TileCoordinate, Bitmap>, private var startedAt: Long, private val durationMillis: Long = DURATION_MS) {
    companion object { const val DURATION_MS = 2500L }
    init { require(durationMillis in 250L..5000L) }
    val before = mutableMapOf<TileCoordinate, Bitmap?>() // Shares immutable history snapshots.
    val editedTiles = mutableSetOf<TileCoordinate>()
    private val plain = Paint()
    private val remove = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val keep = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    var progress = 0f
        private set

    fun start(now: Long) { startedAt = now; update(now) }

    fun update(now: Long): Boolean {
        progress = ((now - startedAt).toFloat() / durationMillis).coerceIn(0f, 1f)
        // Mask alpha encodes arrival time; clamp a steep ramp into a threshold.
        val filter = ColorMatrixColorFilter(floatArrayOf(
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, -255f, ((progress * 255).toInt() + 1) * 255f,
        ))
        remove.colorFilter = filter
        keep.colorFilter = filter
        return progress >= 1f
    }

    fun drawTile(canvas: Canvas, coordinate: TileCoordinate, current: Bitmap, paint: Paint, size: Int) {
        val mask = masks.getValue(coordinate)
        val left = (coordinate.x * size).toFloat()
        val top = (coordinate.y * size).toFloat()
        // Apply layer opacity once to the combined before/after pixels.
        val outer = canvas.saveLayer(left, top, left + size, top + size, paint)
        plain.isFilterBitmap = paint.isFilterBitmap
        before[coordinate]?.let { canvas.drawBitmap(it, left, top, plain) }
        canvas.drawBitmap(mask, left, top, remove)
        val after = canvas.saveLayer(left, top, left + size, top + size, null)
        canvas.drawBitmap(current, left, top, plain)
        canvas.drawBitmap(mask, left, top, keep)
        canvas.restoreToCount(after)
        canvas.restoreToCount(outer)
    }

    fun discard() { masks.values.forEach(Bitmap::recycle); before.clear() }
}

internal object FillPreparation {
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "fill-preparation").apply { isDaemon = true }
    }
}
