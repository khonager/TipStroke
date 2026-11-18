package dev.tipstroke.drawing.android

import android.graphics.*

/** Selected sparse pixels are captured once; dragging only changes their offset. */
internal class ShadowPullPreview(val layer: RasterLayerRuntime, val source: TileStore, color: Int, opacity: Float) {
    var dx = 0f
    var dy = 0f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
    }
    fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(dx, dy)
        source.draw(canvas, paint)
        canvas.restoreToCount(save)
    }
    fun discard() = source.discard()
}
