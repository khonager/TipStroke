package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.*
import android.view.View
import dev.tipstroke.core.geometry.CanvasTransform

internal class RasterCanvasView(context: Context, var layerStack: LayerStack) : View(context) {
    val transformMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val canvasPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val transformBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(237, 106, 90); style = Paint.Style.STROKE }
    private val transformHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    var showImageTransformBounds = false
        set(value) { field = value; invalidate() }
    var transform = CanvasTransform(0f, 0f, 1f, 0f); private set

    fun updateTransform(panX: Float, panY: Float, scale: Float, rotation: Float) {
        transform = CanvasTransform(panX, panY, scale, rotation)
        rebuildMatrix(); invalidate()
    }

    fun fitCanvas() {
        if (width == 0 || height == 0) return
        val scale = minOf(width * .76f / layerStack.canvasWidth, height * .83f / layerStack.canvasHeight)
        updateTransform(width / 2f, height / 2f, scale, 0f)
    }

    private fun rebuildMatrix() {
        transformMatrix.reset()
        transformMatrix.postTranslate(-layerStack.canvasWidth / 2f, -layerStack.canvasHeight / 2f)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { if (w > 0 && h > 0 && (w != oldw || h != oldh)) fitCanvas() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.concat(transformMatrix)
        canvas.drawRect(0f, 0f, layerStack.canvasWidth.toFloat(), layerStack.canvasHeight.toFloat(), canvasPaint)
        canvas.clipRect(0f, 0f, layerStack.canvasWidth.toFloat(), layerStack.canvasHeight.toFloat())
        layerStack.layers.forEach { layer ->
            if (!layer.visible || layer.opacity <= 0f) return@forEach
            bitmapPaint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
            when (layer) {
                is RasterLayerRuntime -> layer.tiles.draw(canvas, bitmapPaint)
                is ImageLayerRuntime -> layer.source.bitmapOrRequest()?.let { bitmap ->
                    val displayedWidth = layer.source.width * layer.transform.scale
                    val displayedHeight = layer.source.height * layer.transform.scale
                    canvas.save()
                    canvas.rotate(layer.transform.rotationDegrees, layer.transform.centerX, layer.transform.centerY)
                    val destination = RectF(
                        layer.transform.centerX - displayedWidth / 2f,
                        layer.transform.centerY - displayedHeight / 2f,
                        layer.transform.centerX + displayedWidth / 2f,
                        layer.transform.centerY + displayedHeight / 2f,
                    )
                    canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
                    canvas.restore()
                }
            }
        }
        if (showImageTransformBounds) drawSelectedImageBounds(canvas)
        bitmapPaint.alpha = 255
        canvas.drawRect(0f, 0f, layerStack.canvasWidth.toFloat(), layerStack.canvasHeight.toFloat(), borderPaint)
        canvas.restore()
    }

    private fun drawSelectedImageBounds(canvas: Canvas) {
        val image = layerStack.selectedImage() ?: return
        val halfWidth = image.source.width * image.transform.scale / 2f
        val halfHeight = image.source.height * image.transform.scale / 2f
        val bounds = RectF(
            image.transform.centerX - halfWidth,
            image.transform.centerY - halfHeight,
            image.transform.centerX + halfWidth,
            image.transform.centerY + halfHeight,
        )
        val lineWidth = 2f / transform.scale.coerceAtLeast(.08f)
        val handleRadius = 5f / transform.scale.coerceAtLeast(.08f)
        transformBorderPaint.strokeWidth = lineWidth
        canvas.save()
        canvas.rotate(image.transform.rotationDegrees, image.transform.centerX, image.transform.centerY)
        canvas.drawRect(bounds, transformBorderPaint)
        listOf(bounds.left to bounds.top, bounds.right to bounds.top, bounds.right to bounds.bottom, bounds.left to bounds.bottom).forEach { (x, y) ->
            canvas.drawCircle(x, y, handleRadius, transformHandlePaint)
            canvas.drawCircle(x, y, handleRadius, transformBorderPaint)
        }
        canvas.restore()
    }
}
