package dev.tipstroke.drawing.android

import android.graphics.*
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.geometry.TileGrid
import dev.tipstroke.core.model.BlendBehavior
import dev.tipstroke.core.model.BrushEngine
import kotlin.math.*

class TileStore(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val tileSize: Int = TileGrid.DEFAULT_TILE_SIZE,
    historyBudgetBytes: Long = 96L * 1024 * 1024,
) : StrokeRasterizer {
    private val tiles = mutableMapOf<TileCoordinate, Bitmap>()
    val history = UndoHistory(historyBudgetBytes)
    var lastDirtyTiles: Set<TileCoordinate> = emptySet(); private set
    val allocatedTileCount get() = tiles.size
    private var smudgeBefore: MutableMap<TileCoordinate, Bitmap?>? = null
    private val smudgeTouched = mutableSetOf<TileCoordinate>()

    override fun commit(stroke: CompletedStroke): Set<TileCoordinate> {
        val dirty = TileGrid.intersecting(stroke.bounds, canvasWidth, canvasHeight, tileSize)
        if (dirty.isEmpty()) return emptySet()
        val before = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        dirty.forEach { coordinate ->
            val bitmap = tiles.getOrPut(coordinate) { Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) }
            drawStroke(Canvas(bitmap), coordinate, stroke)
            if (bitmap.isFullyTransparent()) { bitmap.recycle(); tiles.remove(coordinate) }
        }
        val after = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        history.push(TileSnapshotTransaction(this, before, after))
        lastDirtyTiles = dirty
        return dirty
    }

    /** Rasterizes Ink's finalized mesh into pixels, then lets the caller discard the Ink stroke. */
    fun commitInk(stroke: CompletedStroke, inkStroke: Stroke, renderer: CanvasStrokeRenderer): Set<TileCoordinate> {
        val dirty = TileGrid.intersecting(stroke.bounds, canvasWidth, canvasHeight, tileSize)
        if (dirty.isEmpty()) return emptySet()
        val before = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        dirty.forEach { coordinate ->
            val bitmap = tiles.getOrPut(coordinate) { Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) }
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.translate((-coordinate.x * tileSize).toFloat(), (-coordinate.y * tileSize).toFloat())
            renderer.draw(canvas, inkStroke, Matrix())
            canvas.restore()
            if (bitmap.isFullyTransparent()) { bitmap.recycle(); tiles.remove(coordinate) }
        }
        val after = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        history.push(TileSnapshotTransaction(this, before, after))
        lastDirtyTiles = dirty
        return dirty
    }

    fun draw(canvas: Canvas, paint: Paint) {
        tiles.forEach { (coordinate, bitmap) ->
            canvas.drawBitmap(bitmap, (coordinate.x * tileSize).toFloat(), (coordinate.y * tileSize).toFloat(), paint)
        }
    }

    internal fun snapshotTiles(): Map<TileCoordinate, Bitmap> =
        tiles.mapValues { (_, bitmap) -> bitmap.copy(Bitmap.Config.ARGB_8888, false) }

    internal fun replaceTiles(replacement: Map<TileCoordinate, Bitmap>) {
        tiles.values.forEach(Bitmap::recycle)
        tiles.clear()
        tiles.putAll(replacement)
        lastDirtyTiles = replacement.keys
        history.clear()
    }

    internal fun colorAt(x: Int, y: Int): Int {
        if (x !in 0 until canvasWidth || y !in 0 until canvasHeight) return Color.TRANSPARENT
        val coordinate = TileCoordinate(x / tileSize, y / tileSize)
        return tiles[coordinate]?.getPixel(x % tileSize, y % tileSize) ?: Color.TRANSPARENT
    }

    internal fun beginSmudge() {
        smudgeBefore = mutableMapOf()
        smudgeTouched.clear()
    }

    internal fun smudge(fromX: Float, fromY: Float, toX: Float, toY: Float, radius: Float, strength: Float) {
        val before = smudgeBefore ?: return
        val safeRadius = radius.coerceIn(4f, 96f)
        val diameter = (safeRadius * 2f).roundToInt().coerceAtLeast(2)
        val patch = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
        val patchCanvas = Canvas(patch)
        val sourceLeft = fromX - safeRadius
        val sourceTop = fromY - safeRadius
        TileGrid.intersecting(
            dev.tipstroke.core.geometry.Rect(sourceLeft, sourceTop, sourceLeft + diameter, sourceTop + diameter),
            canvasWidth, canvasHeight, tileSize,
        ).forEach { coordinate ->
            tiles[coordinate]?.let { bitmap ->
                patchCanvas.drawBitmap(bitmap, coordinate.x * tileSize - sourceLeft, coordinate.y * tileSize - sourceTop, null)
            }
        }
        val destination = dev.tipstroke.core.geometry.Rect(toX - safeRadius, toY - safeRadius, toX + safeRadius, toY + safeRadius)
        val dirty = TileGrid.intersecting(destination, canvasWidth, canvasHeight, tileSize)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (strength.coerceIn(.05f, 1f) * 255).roundToInt() }
        dirty.forEach { coordinate ->
            if (coordinate !in before) before[coordinate] = tiles[coordinate]?.copy(Bitmap.Config.ARGB_8888, false)
            val bitmap = tiles.getOrPut(coordinate) { Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) }
            val tileCanvas = Canvas(bitmap)
            val localX = toX - coordinate.x * tileSize
            val localY = toY - coordinate.y * tileSize
            tileCanvas.save()
            tileCanvas.clipPath(Path().apply { addCircle(localX, localY, safeRadius, Path.Direction.CW) })
            tileCanvas.drawBitmap(patch, localX - safeRadius, localY - safeRadius, paint)
            tileCanvas.restore()
        }
        patch.recycle()
        smudgeTouched += dirty
        lastDirtyTiles = dirty
    }

    internal fun finishSmudge() {
        val before = smudgeBefore ?: return
        smudgeBefore = null
        if (smudgeTouched.isEmpty()) return
        smudgeTouched.forEach { coordinate ->
            tiles[coordinate]?.let { bitmap -> if (bitmap.isFullyTransparent()) { bitmap.recycle(); tiles.remove(coordinate) } }
        }
        val after = smudgeTouched.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        history.push(TileSnapshotTransaction(this, before, after))
        lastDirtyTiles = smudgeTouched.toSet()
        smudgeTouched.clear()
    }

    private fun drawStroke(canvas: Canvas, coordinate: TileCoordinate, stroke: CompletedStroke) {
        canvas.save()
        canvas.translate((-coordinate.x * tileSize).toFloat(), (-coordinate.y * tileSize).toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = stroke.style.sizePx
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
            color = stroke.style.color.toArgb()
            alpha = (stroke.style.opacity.coerceIn(0f, 1f) * 255).roundToInt()
            xfermode = if (stroke.style.blend == BlendBehavior.ERASE) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
            if (stroke.style.brush.engine == BrushEngine.AIRBRUSH) maskFilter = BlurMaskFilter(stroke.style.sizePx * .35f, BlurMaskFilter.Blur.NORMAL)
        }
        val samples = stroke.samples
        if (samples.size == 1) {
            val s = samples.first(); paint.style = Paint.Style.FILL
            canvas.drawCircle(s.position.x, s.position.y, stroke.style.sizePx * s.pressureSize(stroke) / 2f, paint)
        } else {
            for (i in 1 until samples.size) {
                val a = samples[i - 1]; val b = samples[i]
                paint.strokeWidth = stroke.style.sizePx * ((a.pressureSize(stroke) + b.pressureSize(stroke)) / 2f)
                paint.alpha = (stroke.style.opacity * b.pressureOpacity(stroke) * 255).roundToInt().coerceIn(0, 255)
                canvas.drawLine(a.position.x, a.position.y, b.position.x, b.position.y, paint)
            }
        }
        canvas.restore()
    }

    private fun dev.tipstroke.core.drawing.StrokeSample.pressureSize(stroke: CompletedStroke) = stroke.style.brush.pressureToSize.map(pressure)
    private fun dev.tipstroke.core.drawing.StrokeSample.pressureOpacity(stroke: CompletedStroke) = stroke.style.brush.pressureToOpacity.map(pressure)
    private fun dev.tipstroke.core.model.RgbaColor.toArgb() = Color.argb((alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

    private fun Bitmap.isFullyTransparent(): Boolean {
        val row = IntArray(width)
        for (y in 0 until height) { getPixels(row, 0, width, 0, y, width, 1); if (row.any { Color.alpha(it) != 0 }) return false }
        return true
    }

    private fun restore(snapshot: Map<TileCoordinate, Bitmap?>) {
        snapshot.forEach { (coordinate, saved) ->
            tiles.remove(coordinate)?.recycle()
            saved?.let { tiles[coordinate] = it.copy(Bitmap.Config.ARGB_8888, true) }
        }
        lastDirtyTiles = snapshot.keys
    }

    private class TileSnapshotTransaction(
        private val store: TileStore,
        private val before: Map<TileCoordinate, Bitmap?>,
        private val after: Map<TileCoordinate, Bitmap?>,
    ) : UndoTransaction {
        override val estimatedBytes = (before.values.count { it != null } + after.values.count { it != null }).toLong() * store.tileSize * store.tileSize * 4
        override fun undo() = store.restore(before)
        override fun redo() = store.restore(after)
    }
}
