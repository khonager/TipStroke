package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A temporary, screen-space handle layer. Bounds stay in document pixels until Apply. */
internal class CanvasResizeOverlay(
    context: Context,
    private val raster: RasterCanvasView,
    private val originalWidth: Int,
    private val originalHeight: Int,
    private val onChange: (Int, Int) -> Unit,
) : View(context) {
    var leftDelta = 0; private set
    var topDelta = 0; private set
    var rightDelta = 0; private set
    var bottomDelta = 0; private set

    val resizedWidth get() = originalWidth + leftDelta + rightDelta
    val resizedHeight get() = originalHeight + topDelta + bottomDelta
    val hasChanges get() = leftDelta != 0 || topDelta != 0 || rightDelta != 0 || bottomDelta != 0

    private val shade = Paint().apply { color = Color.argb(145, 0, 0, 0) }
    private val extension = Paint().apply { color = Color.argb(235, 255, 255, 255) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 39, 48) }
    private val handleRadius = 7f * resources.displayMetrics.density
    private val hitRadius = 32f * resources.displayMetrics.density
    private val handles = listOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)
    private var dragging: Pair<Int, Int>? = null
    private var startX = 0f
    private var startY = 0f
    private var startBounds = intArrayOf(0, 0, 0, 0)

    fun reset() {
        leftDelta = 0; topDelta = 0; rightDelta = 0; bottomDelta = 0
        onChange(resizedWidth, resizedHeight)
        fitPreview()
    }

    private fun screenPoint(x: Float, y: Float): FloatArray = floatArrayOf(x, y).also(raster.transformMatrix::mapPoints)

    private fun drawDocumentRect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, paint: Paint) {
        if (r <= l || b <= t) return
        canvas.drawRect(l, t, r, b, paint)
    }

    private fun fitPreview() {
        if (width == 0 || height == 0) return
        val l = minOf(0f, -leftDelta.toFloat())
        val t = minOf(0f, -topDelta.toFloat())
        val r = maxOf(originalWidth.toFloat(), originalWidth + rightDelta.toFloat())
        val b = maxOf(originalHeight.toFloat(), originalHeight + bottomDelta.toFloat())
        val angle = Math.toRadians(raster.transform.rotationDegrees.toDouble())
        val rotatedWidth = abs(cos(angle)) * (r - l) + abs(sin(angle)) * (b - t)
        val rotatedHeight = abs(sin(angle)) * (r - l) + abs(cos(angle)) * (b - t)
        val scale = minOf(width * .60f / rotatedWidth.toFloat(), height * .60f / rotatedHeight.toFloat())
        raster.updateTransformAround((l + r) / 2f, (t + b) / 2f,
            width / 2f, height / 2f, scale, raster.transform.rotationDegrees)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        extension.color = raster.layerStack.backgroundColor
        val l = -leftDelta.toFloat(); val t = -topDelta.toFloat()
        val r = originalWidth + rightDelta.toFloat(); val b = originalHeight + bottomDelta.toFloat()
        canvas.save()
        canvas.concat(raster.transformMatrix)
        // The existing paper and artwork are drawn beneath this overlay. Show additions as paper.
        drawDocumentRect(canvas, l, t, minOf(r, 0f), b, extension)
        drawDocumentRect(canvas, maxOf(l, originalWidth.toFloat()), t, r, b, extension)
        drawDocumentRect(canvas, maxOf(l, 0f), t, minOf(r, originalWidth.toFloat()), minOf(b, 0f), extension)
        drawDocumentRect(canvas, maxOf(l, 0f), maxOf(t, originalHeight.toFloat()), minOf(r, originalWidth.toFloat()), b, extension)
        // Mark original pixels that would be discarded.
        drawDocumentRect(canvas, 0f, 0f, minOf(l, originalWidth.toFloat()), originalHeight.toFloat(), shade)
        drawDocumentRect(canvas, maxOf(r, 0f), 0f, originalWidth.toFloat(), originalHeight.toFloat(), shade)
        drawDocumentRect(canvas, maxOf(l, 0f), 0f, minOf(r, originalWidth.toFloat()), minOf(t, originalHeight.toFloat()), shade)
        drawDocumentRect(canvas, maxOf(l, 0f), maxOf(b, 0f), minOf(r, originalWidth.toFloat()), originalHeight.toFloat(), shade)
        canvas.restore()

        val corners = listOf(screenPoint(l, t), screenPoint(r, t), screenPoint(r, b), screenPoint(l, b))
        val path = Path().apply {
            moveTo(corners[0][0], corners[0][1])
            for (i in 1..3) lineTo(corners[i][0], corners[i][1])
            close()
        }
        canvas.drawPath(path, outline)
        handles.forEach { (horizontal, vertical) ->
            val x = when (horizontal) { -1 -> l; 1 -> r; else -> (l + r) / 2f }
            val y = when (vertical) { -1 -> t; 1 -> b; else -> (t + b) / 2f }
            val point = screenPoint(x, y)
            canvas.drawCircle(point[0], point[1], handleRadius, handle)
            canvas.drawCircle(point[0], point[1], handleRadius * .45f, handleCenter)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val l = -leftDelta.toFloat(); val t = -topDelta.toFloat()
                val r = originalWidth + rightDelta.toFloat(); val b = originalHeight + bottomDelta.toFloat()
                dragging = handles.map { handle ->
                    val x = when (handle.first) { -1 -> l; 1 -> r; else -> (l + r) / 2f }
                    val y = when (handle.second) { -1 -> t; 1 -> b; else -> (t + b) / 2f }
                    val p = screenPoint(x, y)
                    handle to hypot(event.x - p[0], event.y - p[1])
                }.minByOrNull { it.second }?.takeIf { it.second <= hitRadius }?.first
                val start = raster.screenToDocument(event.x, event.y)
                startX = start.x; startY = start.y
                startBounds = intArrayOf(leftDelta, topDelta, rightDelta, bottomDelta)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val active = dragging ?: return true
                val now = raster.screenToDocument(event.x, event.y)
                val dx = (now.x - startX).roundToInt()
                val dy = (now.y - startY).roundToInt()
                if (active.first < 0) leftDelta = (startBounds[0] - dx).coerceIn(16 - originalWidth - rightDelta, 8192 - originalWidth - rightDelta)
                if (active.first > 0) rightDelta = (startBounds[2] + dx).coerceIn(16 - originalWidth - leftDelta, 8192 - originalWidth - leftDelta)
                if (active.second < 0) topDelta = (startBounds[1] - dy).coerceIn(16 - originalHeight - bottomDelta, 8192 - originalHeight - bottomDelta)
                if (active.second > 0) bottomDelta = (startBounds[3] + dy).coerceIn(16 - originalHeight - topDelta, 8192 - originalHeight - topDelta)
                onChange(resizedWidth, resizedHeight)
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_UP -> { if (dragging != null) fitPreview(); dragging = null; return true }
            MotionEvent.ACTION_CANCEL -> { dragging = null; return true }
        }
        return true
    }
}
