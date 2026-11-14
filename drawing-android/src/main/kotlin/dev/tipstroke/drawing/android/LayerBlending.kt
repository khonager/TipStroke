package dev.tipstroke.drawing.android

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import dev.tipstroke.core.model.LayerBlendMode

internal fun LayerBlendMode.nativeMode(): BlendMode = when (this) {
    LayerBlendMode.NORMAL -> BlendMode.SRC_OVER
    LayerBlendMode.ADD -> BlendMode.PLUS
    else -> BlendMode.valueOf(name)
}

/** Blend the complete clipped layer once, bounded to the current visible canvas. */
internal fun drawWithLayerBlend(canvas: Canvas, mode: LayerBlendMode, content: () -> Unit) {
    if (mode == LayerBlendMode.NORMAL) { content(); return }
    val saved = canvas.saveLayer(RectF(canvas.clipBounds), Paint().apply { blendMode = mode.nativeMode() })
    try { content() } finally { canvas.restoreToCount(saved) }
}

/** Reusable one-pixel native compositor for eyedropper/palette parity with export. */
internal class ColorBlendSampler {
    private val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint()
    fun composite(source: Int, destination: Int, opacity: Float, mode: LayerBlendMode): Int {
        bitmap.eraseColor(destination)
        paint.color = source
        paint.alpha = (android.graphics.Color.alpha(source) * opacity).toInt().coerceIn(0, 255)
        paint.blendMode = mode.nativeMode()
        canvas.drawRect(0f, 0f, 1f, 1f, paint)
        return bitmap.getPixel(0, 0)
    }
}
