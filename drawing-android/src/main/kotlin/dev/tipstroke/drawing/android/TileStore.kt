package dev.tipstroke.drawing.android

import android.graphics.*
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.geometry.TileGrid
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
    private var liveStrokeBefore: MutableMap<TileCoordinate, Bitmap?>? = null
    private val liveStrokeTouched = mutableSetOf<TileCoordinate>()
    private val colorUsage = mutableMapOf<Int, Long>()

    override fun commit(stroke: CompletedStroke): Set<TileCoordinate> {
        val dirty = TileGrid.intersecting(stroke.bounds, canvasWidth, canvasHeight, tileSize)
        if (dirty.isEmpty()) return emptySet()
        val before = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        val preparedPaint = StrokeCanvasPainter.preparePaint(stroke)
        dirty.forEach { coordinate ->
            val bitmap = tiles.getOrPut(coordinate) { Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) }
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.translate((-coordinate.x * tileSize).toFloat(), (-coordinate.y * tileSize).toFloat())
            StrokeCanvasPainter.draw(canvas, stroke, preparedPaint)
            canvas.restore()
            if (bitmap.isFullyTransparent()) { bitmap.recycle(); tiles.remove(coordinate) }
        }
        val after = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        history.push(TileSnapshotTransaction(this, before, after, usageFor(stroke)))
        lastDirtyTiles = dirty
        return dirty
    }

    /** Starts a cancellable stroke that mutates only newly touched sparse tiles as samples arrive. */
    internal fun beginLiveStroke() {
        check(liveStrokeBefore == null) { "A live stroke is already active" }
        liveStrokeBefore = mutableMapOf()
        liveStrokeTouched.clear()
    }

    /** Applies one new point or segment and returns only the tiles that need screen invalidation. */
    internal fun appendLiveStroke(stroke: CompletedStroke): Set<TileCoordinate> {
        val before = liveStrokeBefore ?: return emptySet()
        val candidates = TileGrid.intersecting(stroke.bounds, canvasWidth, canvasHeight, tileSize)
        val dirty = if (stroke.style.blend == dev.tipstroke.core.model.BlendBehavior.ERASE) {
            candidates.filterTo(mutableSetOf()) { it in tiles }
        } else candidates
        if (dirty.isEmpty()) return emptySet()
        val preparedPaint = StrokeCanvasPainter.preparePaint(stroke)
        dirty.forEach { coordinate ->
            if (coordinate !in before) before[coordinate] = tiles[coordinate]?.copy(Bitmap.Config.ARGB_8888, false)
            val bitmap = tiles.getOrPut(coordinate) { Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) }
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.translate((-coordinate.x * tileSize).toFloat(), (-coordinate.y * tileSize).toFloat())
            StrokeCanvasPainter.draw(canvas, stroke, preparedPaint)
            canvas.restore()
        }
        liveStrokeTouched += dirty
        lastDirtyTiles = dirty
        return dirty
    }

    internal fun finishLiveStroke(stroke: CompletedStroke? = null): Set<TileCoordinate> {
        val before = liveStrokeBefore ?: return emptySet()
        liveStrokeBefore = null
        if (liveStrokeTouched.isEmpty()) return emptySet()
        liveStrokeTouched.forEach { coordinate ->
            tiles[coordinate]?.let { bitmap -> if (bitmap.isFullyTransparent()) { bitmap.recycle(); tiles.remove(coordinate) } }
        }
        val after = liveStrokeTouched.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        history.push(TileSnapshotTransaction(this, before, after, stroke?.let(::usageFor)))
        return liveStrokeTouched.toSet().also { lastDirtyTiles = it; liveStrokeTouched.clear() }
    }

    internal fun cancelLiveStroke(): Set<TileCoordinate> {
        val before = liveStrokeBefore ?: return emptySet()
        liveStrokeBefore = null
        val dirty = liveStrokeTouched.toSet()
        restore(before)
        liveStrokeTouched.clear()
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
            stroke.style.clipBounds?.normalized()?.let { canvas.clipRect(it.left, it.top, it.right, it.bottom) }
            renderer.draw(canvas, inkStroke, Matrix())
            canvas.restore()
            if (bitmap.isFullyTransparent()) { bitmap.recycle(); tiles.remove(coordinate) }
        }
        val after = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        history.push(TileSnapshotTransaction(this, before, after, usageFor(stroke)))
        lastDirtyTiles = dirty
        return dirty
    }

    fun draw(canvas: Canvas, paint: Paint) {
        val visible = canvas.clipBounds
        tiles.forEach { (coordinate, bitmap) ->
            val left = coordinate.x * tileSize
            val top = coordinate.y * tileSize
            if (visible.intersects(left, top, left + tileSize, top + tileSize)) {
                canvas.drawBitmap(bitmap, left.toFloat(), top.toFloat(), paint)
            }
        }
    }

    internal fun snapshotTiles(): Map<TileCoordinate, Bitmap> =
        tiles.mapValues { (_, bitmap) -> bitmap.copy(Bitmap.Config.ARGB_8888, false) }

    internal fun snapshotColorUsage(): Map<Int, Long> = colorUsage.toMap()

    internal fun replaceColorUsage(replacement: Map<Int, Long>) {
        colorUsage.clear()
        replacement.filterValues { it > 0L }.forEach { (argb, weight) -> colorUsage[argb] = weight }
    }

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

    private fun usageFor(stroke: CompletedStroke): Pair<Int, Long>? {
        if (stroke.style.blend != dev.tipstroke.core.model.BlendBehavior.PAINT) return null
        val color = stroke.style.color
        val argb = Color.argb(
            (color.alpha * 255).roundToInt().coerceIn(0, 255),
            (color.red * 255).roundToInt().coerceIn(0, 255),
            (color.green * 255).roundToInt().coerceIn(0, 255),
            (color.blue * 255).roundToInt().coerceIn(0, 255),
        )
        val pathLength = stroke.samples.zipWithNext().sumOf { (first, second) ->
            hypot(
                (second.position.x - first.position.x).toDouble(),
                (second.position.y - first.position.y).toDouble(),
            )
        }
        val footprint = max(pathLength * stroke.style.sizePx, stroke.style.sizePx * stroke.style.sizePx.toDouble())
        val weight = (footprint * stroke.style.opacity * color.alpha).roundToLong().coerceAtLeast(1L)
        return argb to weight
    }

    private fun applyUsage(usage: Pair<Int, Long>?, direction: Long) {
        usage ?: return
        val updated = colorUsage.getOrDefault(usage.first, 0L) + usage.second * direction
        if (updated > 0L) colorUsage[usage.first] = updated else colorUsage.remove(usage.first)
    }

    private class TileSnapshotTransaction(
        private val store: TileStore,
        private val before: Map<TileCoordinate, Bitmap?>,
        private val after: Map<TileCoordinate, Bitmap?>,
        private val colorUsage: Pair<Int, Long>? = null,
    ) : UndoTransaction {
        init { store.applyUsage(colorUsage, 1L) }
        override val estimatedBytes = (before.values.count { it != null } + after.values.count { it != null }).toLong() * store.tileSize * store.tileSize * 4
        override fun undo() { store.restore(before); store.applyUsage(colorUsage, -1L) }
        override fun redo() { store.restore(after); store.applyUsage(colorUsage, 1L) }
    }
}
