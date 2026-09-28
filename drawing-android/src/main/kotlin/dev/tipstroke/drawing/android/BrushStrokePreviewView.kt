package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.drawing.PointerKind
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.drawing.StrokeStyle
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.BlendBehavior
import dev.tipstroke.core.model.BrushEngine
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.RgbaColor
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * A small, cached rendering of a representative stroke using the permanent raster painter.
 * Keeping this in :drawing-android prevents Brush Studio from drifting away from the canvas
 * implementation as Pencil and Airbrush rendering evolve.
 */
class BrushStrokePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var brush = BrushPreset.Ink
    private var erasing = false
    private var opacity = 1f
    private var color = RgbaColor(.25f, .25f, .28f)
    private var rendered: Bitmap? = null

    init {
        setWillNotDraw(false)
    }

    fun setPreview(
        brush: BrushPreset,
        erasing: Boolean,
        opacity: Float,
        color: RgbaColor,
    ) {
        val safeOpacity = opacity.coerceIn(0f, 1f)
        if (this.brush == brush && this.erasing == erasing && this.opacity == safeOpacity && this.color == color) return
        this.brush = brush
        this.erasing = erasing
        this.opacity = safeOpacity
        this.color = color
        clearRenderedPreview()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        clearRenderedPreview()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val bitmap = rendered ?: renderPreview(width, height).also { rendered = it }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun onDetachedFromWindow() {
        clearRenderedPreview()
        super.onDetachedFromWindow()
    }

    private fun renderPreview(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            if (erasing) drawEraserSample(canvas, width, height)
            StrokeCanvasPainter.draw(canvas, previewStroke(width, height))
        }

    private fun previewStroke(width: Int, height: Int): CompletedStroke {
        val left = max(5f, width * .015f)
        val right = width - left
        val centerY = height * .5f
        val segmentCount = max(96, width / 3)
        var elapsedNanos = 0L
        val samples = List(segmentCount) { index ->
            val progress = index / (segmentCount - 1f)
            val pressure = (.16f + .84f * sin(progress * PI).toFloat()).coerceIn(0f, 1f)
            if (index > 0) elapsedNanos += if (progress > .72f) 1_000_000L else 10_000_000L
            StrokeSample(
                pointerId = 0,
                position = Point(
                    left + (right - left) * progress,
                    centerY + sin(progress * PI * 2).toFloat() * height * .09f,
                ),
                pressure = pressure,
                tiltRadians = if (!erasing && brush.engine == BrushEngine.PENCIL) {
                    progress * BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS
                } else {
                    0f
                },
                // Put the tilted Pencil's broad axis across the path so its changing contact is
                // legible in this short horizontal sample.
                orientationRadians = if (!erasing && brush.engine == BrushEngine.PENCIL) (PI / 2).toFloat() else 0f,
                elapsedNanos = elapsedNanos,
                buttonState = 0,
                kind = PointerKind.STYLUS,
            )
        }
        val nominalSize = when {
            erasing -> height * .24f
            brush.engine == BrushEngine.PENCIL -> height * .29f
            brush.engine == BrushEngine.AIRBRUSH -> height * .28f
            else -> height * .18f
        }
        return CompletedStroke(
            samples,
            StrokeStyle(
                brush = brush,
                sizePx = nominalSize,
                opacity = opacity,
                color = color,
                blend = if (erasing) BlendBehavior.ERASE else BlendBehavior.PAINT,
            ),
        )
    }

    private fun drawEraserSample(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val centerY = height * .5f
        val halfHeight = height * .31f
        paint.color = Color.rgb(98, 101, 109)
        canvas.drawRoundRect(
            RectF(5f, centerY - halfHeight, width - 5f, centerY + halfHeight),
            halfHeight,
            halfHeight,
            paint,
        )
        paint.color = Color.argb(46, 255, 255, 255)
        paint.strokeWidth = 2f
        for (index in 0..7) {
            val x = 5f + (width - 10f) * index / 7f
            canvas.drawLine(x - 18f, centerY - halfHeight, x + 18f, centerY + halfHeight, paint)
        }
    }

    private fun clearRenderedPreview() {
        rendered?.recycle()
        rendered = null
    }
}
