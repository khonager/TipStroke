package dev.tipstroke.drawing.android

import android.graphics.*
import dev.tipstroke.core.drawing.CompletedStroke
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Viewport-resolution coverage only. Permanent tiles always use the full-resolution painter. */
internal class AirbrushPreview : AutoCloseable {
    private var source: Bitmap? = null
    private val output = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    internal val allocatedBytes: Int get() = source?.allocationByteCount ?: 0

    fun draw(canvas: Canvas, stroke: CompletedStroke, zoom: Float) {
        val bounds = stroke.bounds
        val visible = RectF(canvas.clipBounds)
        if (!visible.intersect(bounds.left, bounds.top, bounds.right, bounds.bottom)) return
        // Include offscreen coverage that can blur back into the visible area. Bound both
        // dimensions, even for an enormous document-space tip or a long zoomed-out stroke.
        val halo = stroke.style.sizePx * 1.5f + 2f
        val region = RectF(visible).apply { inset(-halo, -halo) }
        val scale = min(min(1f, zoom), MAX_EDGE / max(region.width(), region.height()))
            .coerceAtLeast(1e-9f)
        val width = ceil(region.width() * scale).toInt().coerceIn(1, MAX_EDGE)
        val height = ceil(region.height() * scale).toInt().coerceIn(1, MAX_EDGE)
        var bitmap = source
        if (bitmap == null || bitmap.width < width || bitmap.height < height) {
            val nextWidth = max(bitmap?.width ?: 0, capacity(width))
            val nextHeight = max(bitmap?.height ?: 0, capacity(height))
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(nextWidth, nextHeight, Bitmap.Config.ARGB_8888)
            source = bitmap
        }
        bitmap.eraseColor(Color.TRANSPARENT)
        val scratch = Canvas(bitmap)
        scratch.scale(scale, scale)
        scratch.translate(-region.left, -region.top)
        val paint = StrokeCanvasPainter.preparePaint(stroke).apply { maskFilter = null }
        StrokeCanvasPainter.drawAirbrushCoverage(scratch, stroke, paint)
        val blur = stroke.style.sizePx * .42f * (1f - stroke.style.brush.hardness) * scale
        val filter = Paint().apply {
            if (blur > .01f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        val offset = IntArray(2)
        val mask = bitmap.extractAlpha(filter, offset)
        try {
            output.color = Color.rgb((stroke.style.color.red * 255).roundToInt(),
                (stroke.style.color.green * 255).roundToInt(), (stroke.style.color.blue * 255).roundToInt())
            val save = canvas.save()
            canvas.clipRect(visible)
            stroke.style.selection?.let { canvas.clipPath(it.toAndroidPath()) }
            val left = region.left + offset[0] / scale
            val top = region.top + offset[1] / scale
            canvas.drawBitmap(mask, null,
                RectF(left, top, left + mask.width / scale, top + mask.height / scale), output)
            canvas.restoreToCount(save)
        } finally { mask.recycle() }
    }

    override fun close() { source?.recycle(); source = null }

    private fun capacity(size: Int): Int {
        var result = 64
        while (result < size) result *= 2
        return result.coerceAtMost(MAX_EDGE)
    }

    companion object { private const val MAX_EDGE = 1024 }
}
