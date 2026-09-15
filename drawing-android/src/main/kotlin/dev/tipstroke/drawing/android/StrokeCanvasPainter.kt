package dev.tipstroke.drawing.android

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.model.BlendBehavior
import dev.tipstroke.core.model.BrushEngine
import dev.tipstroke.core.model.RgbaColor
import kotlin.math.roundToInt

/** Shared painter for custom wet previews and their byte-for-byte-equivalent tile commit. */
internal object StrokeCanvasPainter {
    fun preparePaint(stroke: CompletedStroke) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = stroke.style.sizePx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
            color = stroke.style.color.toArgb()
            alpha = (stroke.style.opacity.coerceIn(0f, 1f) * 255).roundToInt()
            xfermode = if (stroke.style.blend == BlendBehavior.ERASE) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
            val soften = when {
                stroke.style.blend == BlendBehavior.ERASE -> 1f - stroke.style.brush.hardness
                stroke.style.brush.engine == BrushEngine.AIRBRUSH -> 1f - stroke.style.brush.hardness
                else -> 0f
            }
            if (soften > .01f) {
                maskFilter = BlurMaskFilter(stroke.style.sizePx * .42f * soften, BlurMaskFilter.Blur.NORMAL)
            }
    }

    fun draw(canvas: Canvas, stroke: CompletedStroke, paint: Paint = preparePaint(stroke)) {
        val saveCount = canvas.save()
        stroke.style.clipBounds?.normalized()?.let { canvas.clipRect(it.left, it.top, it.right, it.bottom) }
        val samples = stroke.samples
        if (samples.size == 1) {
            val sample = samples.first()
            paint.style = Paint.Style.FILL
            canvas.drawCircle(sample.position.x, sample.position.y, stroke.style.sizePx * sample.pressureSize(stroke) / 2f, paint)
        } else {
            for (index in 1 until samples.size) {
                val previous = samples[index - 1]
                val current = samples[index]
                val pressureSize = (previous.pressureSize(stroke) + current.pressureSize(stroke)) / 2f
                paint.strokeWidth = stroke.style.sizePx * pressureSize * current.speedSize(stroke, previous)
                paint.alpha = (stroke.style.opacity * current.pressureOpacity(stroke) * 255).roundToInt().coerceIn(0, 255)
                canvas.drawLine(previous.position.x, previous.position.y, current.position.x, current.position.y, paint)
            }
        }
        canvas.restoreToCount(saveCount)
    }

    private fun StrokeSample.pressureSize(stroke: CompletedStroke) = stroke.style.brush.pressureToSize.map(pressure)
    private fun StrokeSample.pressureOpacity(stroke: CompletedStroke) = stroke.style.brush.pressureToOpacity.map(pressure)
    private fun StrokeSample.speedSize(stroke: CompletedStroke, previous: StrokeSample): Float {
        val taper = stroke.style.brush.speedTaper
        if (taper <= 0f) return 1f
        val elapsedMillis = ((elapsedNanos - previous.elapsedNanos).coerceAtLeast(1L)) / 1_000_000f
        val distance = kotlin.math.hypot(position.x - previous.position.x, position.y - previous.position.y)
        val normalizedSpeed = (distance / elapsedMillis / 2.5f).coerceIn(0f, 1f)
        return 1f - normalizedSpeed * taper * .65f
    }
    private fun RgbaColor.toArgb() = Color.argb(
        (alpha * 255).roundToInt(),
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )
}
