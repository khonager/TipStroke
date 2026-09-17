package dev.tipstroke.drawing.android

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.geometry.Point
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
            alpha = (stroke.style.opacity.coerceIn(0f, 1f) * stroke.style.color.alpha * 255).roundToInt()
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
        stroke.style.selection?.let { canvas.clipPath(it.toAndroidPath()) }
        val samples = stroke.samples
        if (stroke.style.blend == BlendBehavior.PAINT && stroke.style.brush.engine == BrushEngine.AIRBRUSH) {
            if (pressureBehaviorEnabled(stroke.style.brush.pressureToOpacity)) {
                drawPressureResponsiveAirbrush(canvas, stroke, paint)
            } else {
                drawUnifiedAirbrushMask(canvas, stroke, paint)
            }
            canvas.restoreToCount(saveCount)
            return
        }
        if (samples.size == 1) {
            val sample = samples.first()
            paint.style = Paint.Style.FILL
            paint.alpha = (stroke.style.opacity * stroke.style.color.alpha * sample.pressureOpacity(stroke) * 255)
                .roundToInt().coerceIn(0, 255)
            canvas.drawCircle(sample.position.x, sample.position.y, stroke.style.sizePx * sample.pressureSize(stroke) / 2f, paint)
        } else {
            for (index in 1 until samples.size) {
                val previous = samples[index - 1]
                val current = samples[index]
                val pressureSize = (previous.pressureSize(stroke) + current.pressureSize(stroke)) / 2f
                paint.strokeWidth = stroke.style.sizePx * pressureSize * current.speedSize(stroke, previous)
                paint.alpha = (stroke.style.opacity * stroke.style.color.alpha * current.pressureOpacity(stroke) * 255)
                    .roundToInt().coerceIn(0, 255)
                canvas.drawLine(previous.position.x, previous.position.y, current.position.x, current.position.y, paint)
            }
        }
        canvas.restoreToCount(saveCount)
    }

    /**
     * Draws variable-opacity airbrush segments into an isolated layer. SRC replacement inside
     * that layer prevents overlapping samples from becoming darker while pressure still varies
     * along the stroke; restoring the layer composites the result over existing paint once.
     */
    private fun drawPressureResponsiveAirbrush(canvas: Canvas, stroke: CompletedStroke, paint: Paint) {
        val samples = stroke.samples
        val padding = stroke.style.sizePx
        val bounds = stroke.bounds
        val layer = canvas.saveLayer(
            bounds.left - padding,
            bounds.top - padding,
            bounds.right + padding,
            bounds.bottom + padding,
            null,
        )
        val originalMode = paint.xfermode
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val baseAlpha = stroke.style.opacity * stroke.style.color.alpha * 255f
        if (samples.size == 1) {
            val sample = samples.first()
            paint.style = Paint.Style.FILL
            paint.alpha = (baseAlpha * sample.pressureOpacity(stroke)).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(
                sample.position.x,
                sample.position.y,
                stroke.style.sizePx * sample.pressureSize(stroke) / 2f,
                paint,
            )
        } else {
            for (index in 1 until samples.size) {
                val previous = samples[index - 1]
                val current = samples[index]
                val sizeScale = (previous.pressureSize(stroke) + current.pressureSize(stroke)) / 2f
                paint.strokeWidth = stroke.style.sizePx * sizeScale * current.speedSize(stroke, previous)
                val opacityScale = (previous.pressureOpacity(stroke) + current.pressureOpacity(stroke)) / 2f
                paint.alpha = (baseAlpha * opacityScale).roundToInt().coerceIn(0, 255)
                canvas.drawLine(previous.position.x, previous.position.y, current.position.x, current.position.y, paint)
            }
        }
        paint.xfermode = originalMode
        canvas.restoreToCount(layer)
    }

    /** Builds one filled variable-width silhouette, then applies the blur to that union once. */
    private fun drawUnifiedAirbrushMask(canvas: Canvas, stroke: CompletedStroke, paint: Paint) {
        val samples = stroke.samples
        if (samples.size == 1) {
            val sample = samples.first()
            paint.style = Paint.Style.FILL
            canvas.drawCircle(
                sample.position.x,
                sample.position.y,
                stroke.style.sizePx * sample.pressureSize(stroke) / 2f,
                paint,
            )
            return
        }
        val radii = samples.mapIndexed { index, sample ->
            val speedScale = if (index == 0) 1f else sample.speedSize(stroke, samples[index - 1])
            stroke.style.sizePx * sample.pressureSize(stroke) * speedScale / 2f
        }
        val normals = samples.indices.map { index ->
            val before = samples[(index - 1).coerceAtLeast(0)].position
            val after = samples[(index + 1).coerceAtMost(samples.lastIndex)].position
            val dx = after.x - before.x
            val dy = after.y - before.y
            val length = kotlin.math.hypot(dx, dy).coerceAtLeast(.0001f)
            Point(-dy / length, dx / length)
        }
        val path = Path().apply {
            val first = samples.first().position
            moveTo(first.x + normals.first().x * radii.first(), first.y + normals.first().y * radii.first())
            for (index in 1..samples.lastIndex) {
                val point = samples[index].position
                lineTo(point.x + normals[index].x * radii[index], point.y + normals[index].y * radii[index])
            }
            for (index in samples.lastIndex downTo 0) {
                val point = samples[index].position
                lineTo(point.x - normals[index].x * radii[index], point.y - normals[index].y * radii[index])
            }
            close()
            // Match the ribbon winding so the caps union with it instead of punching out their
            // overlapping halves and leaving detached semicircles.
            addCircle(samples.first().position.x, samples.first().position.y, radii.first(), Path.Direction.CCW)
            addCircle(samples.last().position.x, samples.last().position.y, radii.last(), Path.Direction.CCW)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = (stroke.style.opacity.coerceIn(0f, 1f) * stroke.style.color.alpha * 255)
            .roundToInt().coerceIn(0, 255)
        canvas.drawPath(path, paint)
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
