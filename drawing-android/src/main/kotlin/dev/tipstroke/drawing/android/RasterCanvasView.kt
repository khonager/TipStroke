package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.*
import android.view.View
import dev.tipstroke.core.geometry.CanvasTransform

internal class RasterCanvasView(context: Context, val tileStore: TileStore) : View(context) {
    val transformMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val canvasPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f }
    var transform = CanvasTransform(0f, 0f, 1f, 0f); private set

    fun updateTransform(panX: Float, panY: Float, scale: Float, rotation: Float) {
        transform = CanvasTransform(panX, panY, scale, rotation)
        rebuildMatrix(); invalidate()
    }

    fun fitCanvas() {
        if (width == 0 || height == 0) return
        val scale = minOf(width * .76f / tileStore.canvasWidth, height * .83f / tileStore.canvasHeight)
        updateTransform(width / 2f, height / 2f, scale, 0f)
    }

    private fun rebuildMatrix() {
        transformMatrix.reset()
        transformMatrix.postTranslate(-tileStore.canvasWidth / 2f, -tileStore.canvasHeight / 2f)
        transformMatrix.postScale(transform.scale, transform.scale)
        transformMatrix.postRotate(transform.rotationDegrees)
        transformMatrix.postTranslate(transform.panX, transform.panY)
    }

    fun screenToDocument(x: Float, y: Float): android.graphics.PointF {
        val inverse = viewToDocumentMatrix()
        val points = floatArrayOf(x, y); inverse.mapPoints(points)
        return android.graphics.PointF(points[0], points[1])
    }

    fun viewToDocumentMatrix() = Matrix().also { transformMatrix.invert(it) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { if (oldw == 0 || oldh == 0) fitCanvas() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.concat(transformMatrix)
        canvas.drawRect(0f, 0f, tileStore.canvasWidth.toFloat(), tileStore.canvasHeight.toFloat(), canvasPaint)
        tileStore.draw(canvas, bitmapPaint)
        canvas.drawRect(0f, 0f, tileStore.canvasWidth.toFloat(), tileStore.canvasHeight.toFloat(), borderPaint)
        canvas.restore()
    }
}
