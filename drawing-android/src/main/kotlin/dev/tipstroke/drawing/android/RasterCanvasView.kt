package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.*
import android.view.View
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.geometry.CanvasTransform
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.geometry.TileGrid
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.model.LayerId
import kotlin.math.ceil
import kotlin.math.floor

internal data class StylusHoverPreview(
    val position: Point,
    val width: Float,
    val height: Float,
    val rotationRadians: Float,
)

internal class RasterCanvasView(context: Context, var layerStack: LayerStack) : View(context) {
    var animation: AnimationRuntime? = null
        set(value) { field = value; invalidate() }
    var animationPlaying: Boolean = false
        set(value) { field = value; invalidate() }
    val transformMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val layerOpacityPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val canvasPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val transformBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(237, 106, 90); style = Paint.Style.STROKE }
    private val transformHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val previewOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(237, 106, 90); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    var selectionRegion: SelectionRegion? = null
        set(value) { field = value; invalidate() }
    var selectionDraftPoints: List<Point> = emptyList()
        set(value) { field = value; invalidate() }
    var selectionPreviewOffset = Point(0f, 0f)
        set(value) { field = value; invalidate() }
    var showImageTransformBounds = false
        set(value) { field = value; invalidate() }
    var previewStroke: CompletedStroke? = null
        set(value) { field = value; postInvalidateOnAnimation() }
    private var pencilPreviewStore: TileStore? = null
    private var pencilPreviewLayerId: LayerId? = null
    var adjustmentPreviewStroke: CompletedStroke? = null
        set(value) { field = value; postInvalidateOnAnimation() }
    var stylusHoverPreview: StylusHoverPreview? = null
        set(value) { field = value; postInvalidateOnAnimation() }
    var transform = CanvasTransform(0f, 0f, 1f, 0f); private set
    var transformChangedListener: ((CanvasTransform) -> Unit)? = null

    fun setPencilPreview(store: TileStore?, layerId: LayerId?) {
        pencilPreviewStore = store
        pencilPreviewLayerId = layerId
        postInvalidateOnAnimation()
    }

    fun updateTransform(panX: Float, panY: Float, scale: Float, rotation: Float) {
        transform = CanvasTransform(panX, panY, scale, rotation)
        rebuildMatrix(); invalidate(); transformChangedListener?.invoke(transform)
    }

    fun updateTransformAround(
        documentX: Float,
        documentY: Float,
        screenX: Float,
        screenY: Float,
        scale: Float,
        rotation: Float,
    ) {
        val anchor = floatArrayOf(documentX, documentY)
        Matrix().apply {
            postTranslate(-layerStack.canvasWidth / 2f, -layerStack.canvasHeight / 2f)
            postScale(scale, scale)
            postRotate(rotation)
            mapPoints(anchor)
        }
        updateTransform(screenX - anchor[0], screenY - anchor[1], scale, rotation)
    }

    fun fitCanvas(rotationDegrees: Float = 0f) {
        if (width == 0 || height == 0) return
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val rotatedWidth = kotlin.math.abs(kotlin.math.cos(radians)) * layerStack.canvasWidth +
            kotlin.math.abs(kotlin.math.sin(radians)) * layerStack.canvasHeight
        val rotatedHeight = kotlin.math.abs(kotlin.math.sin(radians)) * layerStack.canvasWidth +
            kotlin.math.abs(kotlin.math.cos(radians)) * layerStack.canvasHeight
        val scale = minOf(width * .76f / rotatedWidth.toFloat(), height * .83f / rotatedHeight.toFloat())
        updateTransform(width / 2f, height / 2f, scale, rotationDegrees)
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

    fun invalidateTiles(tiles: Set<TileCoordinate>) {
        if (tiles.isEmpty()) return
        val documentBounds = RectF(
            (tiles.minOf { it.x } * TileGrid.DEFAULT_TILE_SIZE).toFloat(),
            (tiles.minOf { it.y } * TileGrid.DEFAULT_TILE_SIZE).toFloat(),
            ((tiles.maxOf { it.x } + 1) * TileGrid.DEFAULT_TILE_SIZE).toFloat(),
            ((tiles.maxOf { it.y } + 1) * TileGrid.DEFAULT_TILE_SIZE).toFloat(),
        )
        transformMatrix.mapRect(documentBounds)
        postInvalidateOnAnimation(
            floor(documentBounds.left).toInt() - 2,
            floor(documentBounds.top).toInt() - 2,
            ceil(documentBounds.right).toInt() + 2,
            ceil(documentBounds.bottom).toInt() + 2,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) fitCanvas(transform.rotationDegrees)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.concat(transformMatrix)
        canvas.drawRect(0f, 0f, layerStack.canvasWidth.toFloat(), layerStack.canvasHeight.toFloat(), canvasPaint)
        canvas.clipRect(0f, 0f, layerStack.canvasWidth.toFloat(), layerStack.canvasHeight.toFloat())
        layerStack.layers.forEach { layer ->
            drawOnionForLayer(canvas, layer)
            if (!layer.visible || !layer.activeAtFrame || layer.opacity <= 0f) return@forEach
            when (layer) {
                is RasterLayerRuntime -> {
                    val preview = pencilPreviewStore?.takeIf { layer.id == pencilPreviewLayerId }
                    if (preview == null) {
                        bitmapPaint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                        layer.tiles.draw(canvas, bitmapPaint)
                    } else if (layer.opacity >= .999f) {
                        bitmapPaint.alpha = 255
                        layer.tiles.draw(canvas, bitmapPaint)
                        preview.draw(canvas, bitmapPaint)
                    } else {
                        layerOpacityPaint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                        val visible = canvas.clipBounds
                        val group = canvas.saveLayer(
                            visible.left.toFloat(), visible.top.toFloat(),
                            visible.right.toFloat(), visible.bottom.toFloat(),
                            layerOpacityPaint,
                        )
                        bitmapPaint.alpha = 255
                        layer.tiles.draw(canvas, bitmapPaint)
                        preview.draw(canvas, bitmapPaint)
                        canvas.restoreToCount(group)
                    }
                }
                is ImageLayerRuntime -> layer.source.bitmapOrRequest()?.let { bitmap ->
                    bitmapPaint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                    drawImageLayer(canvas, layer, bitmap)
                }
            }
        }
        previewStroke?.let { StrokeCanvasPainter.draw(canvas, it) }
        stylusHoverPreview?.let { preview -> drawStylusHoverPreview(canvas, preview) }
        if (showImageTransformBounds) drawSelectedImageBounds(canvas)
        drawSelection(canvas)
        bitmapPaint.alpha = 255
        canvas.drawRect(0f, 0f, layerStack.canvasWidth.toFloat(), layerStack.canvasHeight.toFloat(), borderPaint)
        canvas.restore()
        adjustmentPreviewStroke?.let { stroke ->
            canvas.save()
            canvas.concat(transformMatrix)
            StrokeCanvasPainter.draw(canvas, stroke)
            val sample = stroke.samples.first()
            val radius = stroke.style.sizePx / 2f
            val inverseZoom = 1f / transform.scale.coerceAtLeast(.08f)
            previewOutlinePaint.color = Color.argb(150, 0, 0, 0)
            previewOutlinePaint.strokeWidth = 3f * inverseZoom
            canvas.drawCircle(sample.position.x, sample.position.y, radius, previewOutlinePaint)
            previewOutlinePaint.color = Color.argb(210, 255, 255, 255)
            previewOutlinePaint.strokeWidth = 1.25f * inverseZoom
            canvas.drawCircle(sample.position.x, sample.position.y, radius, previewOutlinePaint)
            canvas.restore()
        }
    }

    private fun drawOnionForLayer(canvas: Canvas, layer: CanvasLayerRuntime) {
        val active = animation ?: return
        if (animationPlaying || active.isBackground(layer.id) || !layer.visible) return
        val current = active.selectedIndex
        fun drawNeighbor(index: Int, distance: Int, tint: Int) {
            if (index !in active.frames.indices) return
            bitmapPaint.alpha = (255f * active.onionOpacity * layer.opacity / distance).toInt().coerceIn(0, 255)
            bitmapPaint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            val frameId = active.frames[index].id
            when (layer) {
                is RasterLayerRuntime -> active.celRaster(layer.id, frameId)?.draw(canvas, bitmapPaint)
                is ImageLayerRuntime -> {
                    val transform = active.celImageTransform(layer.id, frameId)
                    val bitmap = layer.source.bitmapOrRequest()
                    if (transform != null && bitmap != null) drawImageLayer(canvas, layer, bitmap, transform)
                }
            }
            bitmapPaint.colorFilter = null
        }
        for (distance in active.onionBefore.coerceAtMost(current) downTo 1) {
            drawNeighbor(current - distance, distance, Color.rgb(239, 98, 108))
        }
        for (distance in active.onionAfter.coerceAtMost(active.frames.lastIndex - current) downTo 1) {
            drawNeighbor(current + distance, distance, Color.rgb(78, 149, 239))
        }
    }

    private fun drawStylusHoverPreview(canvas: Canvas, preview: StylusHoverPreview) {
        val halfWidth = preview.width.coerceAtLeast(.5f) / 2f
        val halfHeight = preview.height.coerceAtLeast(.5f) / 2f
        val bounds = RectF(
            preview.position.x - halfWidth,
            preview.position.y - halfHeight,
            preview.position.x + halfWidth,
            preview.position.y + halfHeight,
        )
        val inverseZoom = 1f / transform.scale.coerceAtLeast(.08f)
        canvas.save()
        canvas.rotate(
            Math.toDegrees(preview.rotationRadians.toDouble()).toFloat(),
            preview.position.x,
            preview.position.y,
        )
        previewOutlinePaint.color = Color.argb(175, 0, 0, 0)
        previewOutlinePaint.strokeWidth = 3f * inverseZoom
        canvas.drawOval(bounds, previewOutlinePaint)
        previewOutlinePaint.color = Color.argb(235, 255, 255, 255)
        previewOutlinePaint.strokeWidth = 1.25f * inverseZoom
        canvas.drawOval(bounds, previewOutlinePaint)
        canvas.restore()
    }

    private fun drawImageLayer(canvas: Canvas, layer: ImageLayerRuntime, bitmap: Bitmap, imageTransform: dev.tipstroke.core.model.ImageTransform = layer.transform) {
        val displayedWidth = layer.source.width * imageTransform.scale
        val displayedHeight = layer.source.height * imageTransform.scale
        val destination = RectF(
            imageTransform.centerX - displayedWidth / 2f,
            imageTransform.centerY - displayedHeight / 2f,
            imageTransform.centerX + displayedWidth / 2f,
            imageTransform.centerY + displayedHeight / 2f,
        )
        val outerSave = canvas.save()
        canvas.rotate(imageTransform.rotationDegrees, imageTransform.centerX, imageTransform.centerY)
        if (layer.mask.allocatedTileCount == 0) {
            canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
        } else {
            val layerSave = canvas.saveLayer(destination, bitmapPaint)
            bitmapPaint.alpha = 255
            canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
            canvas.save()
            canvas.translate(destination.left, destination.top)
            canvas.scale(imageTransform.scale, imageTransform.scale)
            bitmapPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            layer.mask.draw(canvas, bitmapPaint)
            bitmapPaint.xfermode = null
            canvas.restore()
            canvas.restoreToCount(layerSave)
        }
        canvas.restoreToCount(outerSave)
    }

    private fun drawSelection(canvas: Canvas) {
        selectionPaint.strokeWidth = 2f / transform.scale.coerceAtLeast(.08f)
        selectionPaint.pathEffect = DashPathEffect(
            floatArrayOf(10f / transform.scale.coerceAtLeast(.08f), 7f / transform.scale.coerceAtLeast(.08f)), 0f,
        )
        selectionRegion?.let { canvas.drawPath(it.toAndroidPath(selectionPreviewOffset.x, selectionPreviewOffset.y), selectionPaint) }
        if (selectionDraftPoints.size >= 2) {
            val draft = Path().apply {
                moveTo(selectionDraftPoints.first().x, selectionDraftPoints.first().y)
                selectionDraftPoints.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(draft, selectionPaint)
        }
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
