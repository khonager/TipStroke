package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.View
import dev.tipstroke.core.model.RgbaColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Native, non-interactive eyedropper preview drawn above the wet-ink surface. */
internal class ColorPickerLoupeView(
    context: Context,
    private val source: RasterCanvasView,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val radius = 52f * density
    private val edgeMargin = 10f * density
    private val fingerGap = 30f * density
    private val magnification = 5f
    private val loupeCenter = PointF()
    private val finger = PointF()
    private var sampledColor = Color.BLACK
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clipPath = Path()

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showAt(screenX: Float, screenY: Float, color: RgbaColor) {
        finger.set(screenX, screenY)
        sampledColor = Color.argb(
            (color.alpha * 255).roundToInt(), (color.red * 255).roundToInt(),
            (color.green * 255).roundToInt(), (color.blue * 255).roundToInt(),
        )
        placeLoupe()
        visibility = VISIBLE
        invalidate()
    }

    fun dismiss() {
        visibility = GONE
    }

    private fun placeLoupe() {
        val minimumX = radius + edgeMargin
        val maximumX = (width - radius - edgeMargin).coerceAtLeast(minimumX)
        loupeCenter.x = finger.x.coerceIn(minimumX, maximumX)
        val above = finger.y - radius - fingerGap
        val below = finger.y + radius + fingerGap
        loupeCenter.y = if (above - radius >= edgeMargin) above else below
        loupeCenter.y = loupeCenter.y.coerceIn(
            radius + edgeMargin,
            (height - radius - edgeMargin).coerceAtLeast(radius + edgeMargin),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        placeLoupe()
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE) return
        val angle = atan2(finger.y - loupeCenter.y, finger.x - loupeCenter.x)
        val connectorStart = PointF(
            loupeCenter.x + cos(angle) * radius,
            loupeCenter.y + sin(angle) * radius,
        )
        strokePaint.color = Color.argb(170, 10, 10, 12)
        strokePaint.strokeWidth = 8f * density
        canvas.drawLine(connectorStart.x, connectorStart.y, finger.x, finger.y, strokePaint)

        fillPaint.color = Color.argb(150, 0, 0, 0)
        canvas.drawCircle(loupeCenter.x + 2f * density, loupeCenter.y + 4f * density, radius + 5f * density, fillPaint)

        clipPath.reset()
        clipPath.addCircle(loupeCenter.x, loupeCenter.y, radius - 7f * density, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.translate(loupeCenter.x, loupeCenter.y)
        canvas.scale(magnification, magnification)
        canvas.translate(-finger.x, -finger.y)
        source.draw(canvas)
        canvas.restore()

        strokePaint.color = sampledColor
        strokePaint.strokeWidth = 10f * density
        canvas.drawCircle(loupeCenter.x, loupeCenter.y, radius - 5f * density, strokePaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2f * density
        canvas.drawCircle(loupeCenter.x, loupeCenter.y, radius, strokePaint)

        strokePaint.color = if (Color.luminance(sampledColor) > .55f) Color.BLACK else Color.WHITE
        strokePaint.strokeWidth = 1.5f * density
        val crosshair = 8f * density
        canvas.drawLine(loupeCenter.x - crosshair, loupeCenter.y, loupeCenter.x + crosshair, loupeCenter.y, strokePaint)
        canvas.drawLine(loupeCenter.x, loupeCenter.y - crosshair, loupeCenter.x, loupeCenter.y + crosshair, strokePaint)

        fillPaint.color = sampledColor
        canvas.drawCircle(finger.x, finger.y, 8f * density, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2f * density
        canvas.drawCircle(finger.x, finger.y, 9f * density, strokePaint)
    }
}
