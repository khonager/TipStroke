package dev.tipstroke.drawing.android

import android.graphics.*
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.geometry.TileGrid
import dev.tipstroke.core.geometry.SelectionRegion
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
    val allocatedTileBytes: Long get() = allocatedTileCount.toLong() * tileSize * tileSize * 4L
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
            val pressureOpacity = pressureBehaviorEnabled(stroke.style.brush.pressureToOpacity)
            val rendered = if (pressureOpacity) Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) else bitmap
            val canvas = Canvas(rendered)
            canvas.save()
            canvas.translate((-coordinate.x * tileSize).toFloat(), (-coordinate.y * tileSize).toFloat())
            stroke.style.selection?.let { canvas.clipPath(it.toAndroidPath()) }
            renderer.draw(canvas, inkStroke, Matrix())
            canvas.restore()
            if (pressureOpacity) {
                StrokeCanvasPainter.applyPressureOpacityMask(
                    rendered,
                    stroke,
                    coordinate.x * tileSize,
                    coordinate.y * tileSize,
                )
                Canvas(bitmap).drawBitmap(rendered, 0f, 0f, null)
                rendered.recycle()
            }
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

    internal fun duplicate(selection: SelectionRegion? = null): TileStore {
        val duplicate = TileStore(canvasWidth, canvasHeight, tileSize)
        val copiedTiles = if (selection == null) {
            snapshotTiles()
        } else {
            val path = selection.toAndroidPath()
            TileGrid.intersecting(selection.bounds, canvasWidth, canvasHeight, tileSize).mapNotNull { coordinate ->
                val source = tiles[coordinate] ?: return@mapNotNull null
                val copy = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
                Canvas(copy).apply {
                    save()
                    translate((-coordinate.x * tileSize).toFloat(), (-coordinate.y * tileSize).toFloat())
                    clipPath(path)
                    drawBitmap(source, (coordinate.x * tileSize).toFloat(), (coordinate.y * tileSize).toFloat(), null)
                    restore()
                }
                if (copy.isFullyTransparent()) {
                    copy.recycle()
                    null
                } else coordinate to copy
            }.toMap()
        }
        duplicate.replaceTiles(copiedTiles)
        duplicate.replaceColorUsage(colorUsage)
        return duplicate
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
        val safeRadius = radius.roundToInt().coerceIn(4, 96)
        val safeStrength = strength.coerceIn(.05f, 1f)
        val diameter = safeRadius * 2 + 1
        val sourceLeft = fromX.roundToInt() - safeRadius
        val sourceTop = fromY.roundToInt() - safeRadius
        val destinationLeft = toX.roundToInt() - safeRadius
        val destinationTop = toY.roundToInt() - safeRadius
        fun capturePatch(left: Int, top: Int): Bitmap =
            Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888).also { patch ->
                val patchCanvas = Canvas(patch)
                TileGrid.intersecting(
                    dev.tipstroke.core.geometry.Rect(left.toFloat(), top.toFloat(), (left + diameter).toFloat(), (top + diameter).toFloat()),
                    canvasWidth, canvasHeight, tileSize,
                ).forEach { coordinate ->
                    tiles[coordinate]?.let { bitmap ->
                        patchCanvas.drawBitmap(bitmap, (coordinate.x * tileSize - left).toFloat(), (coordinate.y * tileSize - top).toFloat(), null)
                    }
                }
            }
        val sourcePatch = capturePatch(sourceLeft, sourceTop)
        if (sourcePatch.isFullyTransparent()) {
            sourcePatch.recycle()
            return
        }
        val destinationPatch = capturePatch(destinationLeft, destinationTop)
        val source = dev.tipstroke.core.geometry.Rect(sourceLeft.toFloat(), sourceTop.toFloat(), (sourceLeft + diameter).toFloat(), (sourceTop + diameter).toFloat())
        val destination = dev.tipstroke.core.geometry.Rect(destinationLeft.toFloat(), destinationTop.toFloat(), (destinationLeft + diameter).toFloat(), (destinationTop + diameter).toFloat())
        val dirty = TileGrid.intersecting(source, canvasWidth, canvasHeight, tileSize) +
            TileGrid.intersecting(destination, canvasWidth, canvasHeight, tileSize)

        // Exchange the source and destination colors rather than clearing the pickup
        // circle. On fully painted areas this preserves opaque coverage while blending;
        // at transparent edges it moves the same premultiplied color/alpha outward.
        val sourcePixels = IntArray(diameter * diameter)
        val destinationPixels = IntArray(diameter * diameter)
        sourcePatch.getPixels(sourcePixels, 0, diameter, 0, 0, diameter, diameter)
        destinationPatch.getPixels(destinationPixels, 0, diameter, 0, 0, diameter, diameter)
        val deltaAlpha = FloatArray(diameter * diameter)
        val deltaRed = FloatArray(diameter * diameter)
        val deltaGreen = FloatArray(diameter * diameter)
        val deltaBlue = FloatArray(diameter * diameter)
        val center = safeRadius + .5f
        for (y in 0 until diameter) for (x in 0 until diameter) {
            val edgeCoverage = (safeRadius + .5f - hypot(x + .5f - center, y + .5f - center)).coerceIn(0f, 1f)
            if (edgeCoverage <= 0f) continue
            val index = y * diameter + x
            val sourceColor = sourcePixels[index]
            val destinationColor = destinationPixels[index]
            // Half strength is the stable maximum for overlapping exchange chains.
            // Every delta added to the source is subtracted from its destination.
            val amount = safeStrength * .5f * edgeCoverage
            val sourceAlpha = Color.alpha(sourceColor).toFloat()
            val destinationAlpha = Color.alpha(destinationColor).toFloat()
            deltaAlpha[index] = (destinationAlpha - sourceAlpha) * amount
            deltaRed[index] = (Color.red(destinationColor) * destinationAlpha / 255f - Color.red(sourceColor) * sourceAlpha / 255f) * amount
            deltaGreen[index] = (Color.green(destinationColor) * destinationAlpha / 255f - Color.green(sourceColor) * sourceAlpha / 255f) * amount
            deltaBlue[index] = (Color.blue(destinationColor) * destinationAlpha / 255f - Color.blue(sourceColor) * sourceAlpha / 255f) * amount
        }
        fun deltaIndex(globalX: Int, globalY: Int, left: Int, top: Int): Int {
            val localX = globalX - left
            val localY = globalY - top
            return if (localX in 0 until diameter && localY in 0 until diameter) localY * diameter + localX else -1
        }
        for (y in 0 until diameter) for (x in 0 until diameter) {
            val index = y * diameter + x
            val sourceColor = sourcePixels[index]
            val destinationColor = destinationPixels[index]
            val destinationOverlap = deltaIndex(sourceLeft + x, sourceTop + y, destinationLeft, destinationTop)
            val sourceOverlap = deltaIndex(destinationLeft + x, destinationTop + y, sourceLeft, sourceTop)
            sourcePixels[index] = colorFromPremultiplied(
                Color.alpha(sourceColor) + deltaAlpha[index] - destinationOverlap.takeIf { it >= 0 }?.let(deltaAlpha::get).orZero(),
                Color.red(sourceColor) * Color.alpha(sourceColor) / 255f + deltaRed[index] - destinationOverlap.takeIf { it >= 0 }?.let(deltaRed::get).orZero(),
                Color.green(sourceColor) * Color.alpha(sourceColor) / 255f + deltaGreen[index] - destinationOverlap.takeIf { it >= 0 }?.let(deltaGreen::get).orZero(),
                Color.blue(sourceColor) * Color.alpha(sourceColor) / 255f + deltaBlue[index] - destinationOverlap.takeIf { it >= 0 }?.let(deltaBlue::get).orZero(),
            )
            destinationPixels[index] = colorFromPremultiplied(
                Color.alpha(destinationColor) - deltaAlpha[index] + sourceOverlap.takeIf { it >= 0 }?.let(deltaAlpha::get).orZero(),
                Color.red(destinationColor) * Color.alpha(destinationColor) / 255f - deltaRed[index] + sourceOverlap.takeIf { it >= 0 }?.let(deltaRed::get).orZero(),
                Color.green(destinationColor) * Color.alpha(destinationColor) / 255f - deltaGreen[index] + sourceOverlap.takeIf { it >= 0 }?.let(deltaGreen::get).orZero(),
                Color.blue(destinationColor) * Color.alpha(destinationColor) / 255f - deltaBlue[index] + sourceOverlap.takeIf { it >= 0 }?.let(deltaBlue::get).orZero(),
            )
        }
        sourcePatch.setPixels(sourcePixels, 0, diameter, 0, 0, diameter, diameter)
        destinationPatch.setPixels(destinationPixels, 0, diameter, 0, 0, diameter, diameter)

        dirty.forEach { coordinate ->
            if (coordinate !in before) before[coordinate] = tiles[coordinate]?.copy(Bitmap.Config.ARGB_8888, false)
        }
        val replacePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        fun writePatch(patch: Bitmap, left: Int, top: Int, bounds: dev.tipstroke.core.geometry.Rect) {
            TileGrid.intersecting(bounds, canvasWidth, canvasHeight, tileSize).forEach { coordinate ->
                val bitmap = tiles.getOrPut(coordinate) { Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) }
                Canvas(bitmap).apply {
                    save()
                    clipPath(Path().apply {
                        addCircle(
                            (left + safeRadius - coordinate.x * tileSize).toFloat(),
                            (top + safeRadius - coordinate.y * tileSize).toFloat(),
                            safeRadius.toFloat(),
                            Path.Direction.CW,
                        )
                    })
                    drawBitmap(
                        patch,
                        (left - coordinate.x * tileSize).toFloat(),
                        (top - coordinate.y * tileSize).toFloat(),
                        replacePaint,
                    )
                    restore()
                }
            }
        }
        writePatch(sourcePatch, sourceLeft, sourceTop, source)
        writePatch(destinationPatch, destinationLeft, destinationTop, destination)
        sourcePatch.recycle()
        destinationPatch.recycle()
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

    /** Moves only pixels inside [selection] without copying the full canvas or layer. */
    internal fun moveSelection(selection: SelectionRegion, deltaX: Float, deltaY: Float): Set<TileCoordinate> {
        if (abs(deltaX) < .01f && abs(deltaY) < .01f) return emptySet()
        val sourceTiles = TileGrid.intersecting(selection.bounds, canvasWidth, canvasHeight, tileSize)
        if (sourceTiles.isEmpty()) return emptySet()
        val destinationRegion = selection.translated(deltaX, deltaY)
        val destinationTiles = TileGrid.intersecting(destinationRegion.bounds, canvasWidth, canvasHeight, tileSize)
        val dirty = sourceTiles + destinationTiles
        val before = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        val sourceSnapshots = sourceTiles.associateWith { before[it] }

        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        sourceTiles.forEach { coordinate ->
            tiles[coordinate]?.let { bitmap ->
                Canvas(bitmap).apply {
                    save()
                    translate((-coordinate.x * tileSize).toFloat(), (-coordinate.y * tileSize).toFloat())
                    drawPath(selection.toAndroidPath(), clearPaint)
                    restore()
                }
            }
        }
        clearPaint.xfermode = null

        val copyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        destinationTiles.forEach { destination ->
            val bitmap = tiles.getOrPut(destination) { Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) }
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.translate((-destination.x * tileSize).toFloat(), (-destination.y * tileSize).toFloat())
            canvas.clipPath(destinationRegion.toAndroidPath())
            sourceSnapshots.forEach { (source, snapshot) ->
                snapshot?.let {
                    canvas.drawBitmap(
                        it,
                        source.x * tileSize + deltaX,
                        source.y * tileSize + deltaY,
                        copyPaint,
                    )
                }
            }
            canvas.restore()
        }

        dirty.forEach { coordinate ->
            tiles[coordinate]?.let { bitmap -> if (bitmap.isFullyTransparent()) { bitmap.recycle(); tiles.remove(coordinate) } }
        }
        val after = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        history.push(TileSnapshotTransaction(this, before, after))
        lastDirtyTiles = dirty
        return dirty
    }

    private fun Bitmap.isFullyTransparent(): Boolean {
        val row = IntArray(width)
        for (y in 0 until height) { getPixels(row, 0, width, 0, y, width, 1); if (row.any { Color.alpha(it) != 0 }) return false }
        return true
    }

    private fun colorFromPremultiplied(alphaValue: Float, red: Float, green: Float, blue: Float): Int {
        val alpha = alphaValue.coerceIn(0f, 255f)
        if (alpha < .5f) return Color.TRANSPARENT
        return Color.argb(
            alpha.roundToInt().coerceIn(0, 255),
            (red.coerceIn(0f, alpha) * 255f / alpha).roundToInt().coerceIn(0, 255),
            (green.coerceIn(0f, alpha) * 255f / alpha).roundToInt().coerceIn(0, 255),
            (blue.coerceIn(0f, alpha) * 255f / alpha).roundToInt().coerceIn(0, 255),
        )
    }

    private fun Float?.orZero() = this ?: 0f

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
