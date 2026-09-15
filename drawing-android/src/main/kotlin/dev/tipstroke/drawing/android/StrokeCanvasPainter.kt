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
            if (stroke.style.brush.engine == BrushEngine.AIRBRUSH) {
                maskFilter = BlurMaskFilter(stroke.style.sizePx * .35f, BlurMaskFilter.Blur.NORMAL)
            }
    }

    fun draw(canvas: Canvas, stroke: CompletedStroke, paint: Paint = preparePaint(stroke)) {
        val samples = stroke.samples
        if (samples.size == 1) {
            val sample = samples.first()
            paint.style = Paint.Style.FILL
            canvas.drawCircle(sample.position.x, sample.position.y, stroke.style.sizePx * sample.pressureSize(stroke) / 2f, paint)
        } else {
            for (index in 1 until samples.size) {
                val previous = samples[index - 1]
                val current = samples[index]
                paint.strokeWidth = stroke.style.sizePx * ((previous.pressureSize(stroke) + current.pressureSize(stroke)) / 2f)
                paint.alpha = (stroke.style.opacity * current.pressureOpacity(stroke) * 255).roundToInt().coerceIn(0, 255)
                canvas.drawLine(previous.position.x, previous.position.y, current.position.x, current.position.y, paint)
            }
        }
    }

    private fun StrokeSample.pressureSize(stroke: CompletedStroke) = stroke.style.brush.pressureToSize.map(pressure)
    private fun StrokeSample.pressureOpacity(stroke: CompletedStroke) = stroke.style.brush.pressureToOpacity.map(pressure)
    private fun RgbaColor.toArgb() = Color.argb(
        (alpha * 255).roundToInt(),
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )
}
