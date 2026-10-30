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
    internal var recordingListener: ((List<ReplayPaint>?, Map<TileCoordinate, Bitmap?>) -> Unit)? = null
    internal var captureReplayPreview = false
    private val replayPreview = ReplayCapture()
    private var pencilPreviewDot = false
    private val replayLive = ReplayCapture()
    private val tiles = mutableMapOf<TileCoordinate, Bitmap>()
    private var fillTileReader: ((TileCoordinate) -> Bitmap?)? = null
    private val sampledFillTiles = mutableSetOf<TileCoordinate>()
    val history = UndoHistory(historyBudgetBytes)
    private val tileContentBounds = mutableMapOf<TileCoordinate, Rect>()
    private val dirtyContentBoundsTiles = mutableSetOf<TileCoordinate>()
    private var contentBoundsInitialized = false
    internal var revision = 0L
        private set
    internal var fillReveal: FillReveal? = null
    var lastDirtyTiles: Set<TileCoordinate> = emptySet()
        private set(value) {
            field = value
            revision++
            fillReveal?.editedTiles?.addAll(value)
            dirtyContentBoundsTiles += value
        }
    val allocatedTileCount get() = tiles.size
    val allocatedTileBytes: Long get() = allocatedTileCount.toLong() * tileSize * tileSize * 4L
    private var smudgeBefore: MutableMap<TileCoordinate, Bitmap?>? = null
    private val smudgeTouched = mutableSetOf<TileCoordinate>()
    private var liveStrokeBefore: MutableMap<TileCoordinate, Bitmap?>? = null
    private val liveStrokeTouched = mutableSetOf<TileCoordinate>()
    private val colorUsage = mutableMapOf<Int, Long>()

    internal fun putFillPixel(x: Int, y: Int, color: Int): TileCoordinate {
        val coordinate = TileCoordinate(x / tileSize, y / tileSize)
        revision++
        fillReveal?.editedTiles?.add(coordinate)
        tiles.getOrPut(coordinate) { Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) }
            .setPixel(x % tileSize, y % tileSize, color)
        return coordinate
    }

    internal fun commitFill(overlay: TileStore, color: Int, pixels: Long, reveal: FillReveal? = null): Set<TileCoordinate> {
        val dirty = overlay.tiles.keys.toSet()
        if (dirty.isEmpty()) return emptySet()
        val before = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        // Preview pixels are final replacement colors, not a source-over tint. Clear
        // only its nontransparent footprint before copying to preserve partial-alpha edges.
        val clearFilled = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            colorFilter = ColorMatrixColorFilter(floatArrayOf(
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 255f, 0f,
            ))
        }
        dirty.forEach { coordinate ->
            val destination = tiles.getOrPut(coordinate) { Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) }
            val replacement = overlay.tiles.getValue(coordinate)
            Canvas(destination).apply {
                drawBitmap(replacement, 0f, 0f, clearFilled)
                drawBitmap(replacement, 0f, 0f, null)
            }
        }
        val after = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        history.push(TileSnapshotTransaction(this, before, after, color to pixels))
        lastDirtyTiles = dirty
        reveal?.before?.putAll(before)
        fillReveal = reveal
        return dirty
    }

    /** Worker-owned source: capture only tiles it reaches, one short main-thread copy at a time.
     * The caller checks the live revision before commit and retries any inconsistent read.
     */
    internal fun fillSnapshot(handler: android.os.Handler): TileStore = TileStore(canvasWidth, canvasHeight).also { snapshot ->
        snapshot.fillTileReader = { coordinate ->
            val copy = java.util.concurrent.FutureTask<Bitmap?> {
                tiles[coordinate]?.copy(Bitmap.Config.ARGB_8888, false)
            }
            check(handler.post(copy)) { "Drawing surface is no longer available." }
            copy.get()
        }
    }

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
        history.push(TileSnapshotTransaction(this, before, after, usageFor(stroke), listOf(ReplayPaint(stroke))))
        lastDirtyTiles = dirty
        return dirty
    }

    /** Starts a cancellable stroke that mutates only newly touched sparse tiles as samples arrive. */
    internal fun beginLiveStroke() {
        check(liveStrokeBefore == null) { "A live stroke is already active" }
        liveStrokeBefore = mutableMapOf()
        liveStrokeTouched.clear()
        replayLive.clear()
    }

    /** Applies one new point or segment and returns only the tiles that need screen invalidation. */
    internal fun appendLiveStroke(stroke: CompletedStroke): Set<TileCoordinate> {
        val before = liveStrokeBefore ?: return emptySet()
        if (recordingListener != null) replayLive.add(ReplayPaint(stroke))
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

    /** Adds a Pencil segment to an isolated transparent preview using source replacement. */
    internal fun appendPencilPreview(
        stroke: CompletedStroke,
        capStart: Boolean = false,
        capEnd: Boolean = false,
        skipFirstSegment: Boolean = false,
    ): Set<TileCoordinate> {
        val moving = stroke.samples.zipWithNext().any { (a, b) ->
            hypot(a.position.x - b.position.x, a.position.y - b.position.y) >= .05f
        }
        // Replace the provisional contact mark once the stroke becomes a ribbon. Only the
        // sparse dot tiles are discarded, once per stroke; no layer pixels are touched.
        val removed = if (pencilPreviewDot && moving) tiles.keys.toSet().also { discard() } else emptySet()
        if (capStart && !moving && tiles.isEmpty()) pencilPreviewDot = true
        if (captureReplayPreview) replayPreview.add(ReplayPaint(stroke, true, capStart, capEnd, skipFirstSegment))
        val dirty = TileGrid.intersecting(stroke.bounds, canvasWidth, canvasHeight, tileSize)
        dirty.forEach { coordinate ->
            val bitmap = tiles.getOrPut(coordinate) { Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) }
            Canvas(bitmap).apply {
                save()
                translate((-coordinate.x * tileSize).toFloat(), (-coordinate.y * tileSize).toFloat())
                StrokeCanvasPainter.drawPencilOverlay(this, stroke, capStart, capEnd, skipFirstSegment)
                restore()
            }
        }
        lastDirtyTiles = dirty + removed
        return lastDirtyTiles
    }

    /** Composites an isolated sparse preview once and records the result as one undo step. */
    internal fun commitOverlay(overlay: TileStore, stroke: CompletedStroke): Set<TileCoordinate> {
        val dirty = overlay.tiles.keys.toSet()
        if (dirty.isEmpty()) return emptySet()
        val before = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        dirty.forEach { coordinate ->
            val source = overlay.tiles[coordinate] ?: return@forEach
            val destination = tiles.getOrPut(coordinate) {
                Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
            }
            Canvas(destination).drawBitmap(source, 0f, 0f, null)
            if (destination.isFullyTransparent()) {
                destination.recycle()
                tiles.remove(coordinate)
            }
        }
        val after = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        history.push(TileSnapshotTransaction(this, before, after, usageFor(stroke), overlay.replayPreview.commands()))
        lastDirtyTiles = dirty
        return dirty
    }

    /** Releases all pixels owned by a transient preview store. */
    internal fun discard() {
        pencilPreviewDot = false
        tiles.values.forEach(Bitmap::recycle)
        tiles.clear()
        tileContentBounds.clear()
        dirtyContentBoundsTiles.clear()
        contentBoundsInitialized = false
        lastDirtyTiles = emptySet()
        history.clear()
        replayPreview.clear()
        replayLive.clear()
    }

    internal fun finishLiveStroke(stroke: CompletedStroke? = null): Set<TileCoordinate> {
        val before = liveStrokeBefore ?: return emptySet()
        liveStrokeBefore = null
        if (liveStrokeTouched.isEmpty()) return emptySet()
        liveStrokeTouched.forEach { coordinate ->
            tiles[coordinate]?.let { bitmap -> if (bitmap.isFullyTransparent()) { bitmap.recycle(); tiles.remove(coordinate) } }
        }
        val after = liveStrokeTouched.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        history.push(TileSnapshotTransaction(this, before, after, stroke?.let(::usageFor), replayLive.commands()))
        replayLive.clear()
        return liveStrokeTouched.toSet().also { lastDirtyTiles = it; liveStrokeTouched.clear() }
    }

    internal fun cancelLiveStroke(): Set<TileCoordinate> {
        val before = liveStrokeBefore ?: return emptySet()
        liveStrokeBefore = null
        replayLive.clear()
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
        history.push(TileSnapshotTransaction(this, before, after, usageFor(stroke), listOf(ReplayPaint(stroke, ink = inkStroke))))
        lastDirtyTiles = dirty
        return dirty
    }

    internal fun photoMaskTiles(): Map<TileCoordinate, Bitmap> = tiles

    fun draw(canvas: Canvas, paint: Paint) {
        val visible = canvas.clipBounds
        tiles.forEach { (coordinate, bitmap) ->
            val left = coordinate.x * tileSize
            val top = coordinate.y * tileSize
            if (visible.intersects(left, top, left + tileSize, top + tileSize)) {
                val reveal = fillReveal
                if (reveal != null && coordinate in reveal.masks && coordinate !in reveal.editedTiles) {
                    reveal.drawTile(canvas, coordinate, bitmap, paint, tileSize)
                } else canvas.drawBitmap(bitmap, left.toFloat(), top.toFloat(), paint)
            }
        }
    }

    internal fun snapshotTiles(): Map<TileCoordinate, Bitmap> =
        tiles.mapValues { (_, bitmap) -> bitmap.copy(Bitmap.Config.ARGB_8888, false) }

    internal fun snapshotColorUsage(): Map<Int, Long> = colorUsage.toMap()

    /** Moves existing pixels by an integer offset into a new finite canvas, without scaling. */
    internal fun resized(width: Int, height: Int, offsetX: Int, offsetY: Int): TileStore {
        val result = TileStore(width, height, tileSize)
        tiles.forEach { (coordinate, bitmap) ->
            val sourceLeft = coordinate.x * tileSize
            val sourceTop = coordinate.y * tileSize
            val oldLeft = maxOf(sourceLeft, 0)
            val oldTop = maxOf(sourceTop, 0)
            val oldRight = minOf(sourceLeft + tileSize, canvasWidth)
            val oldBottom = minOf(sourceTop + tileSize, canvasHeight)
            val left = maxOf(oldLeft, -offsetX)
            val top = maxOf(oldTop, -offsetY)
            val right = minOf(oldRight, width - offsetX)
            val bottom = minOf(oldBottom, height - offsetY)
            if (left >= right || top >= bottom) return@forEach
            val firstX = (left + offsetX) / tileSize
            val lastX = (right - 1 + offsetX) / tileSize
            val firstY = (top + offsetY) / tileSize
            val lastY = (bottom - 1 + offsetY) / tileSize
            for (tileY in firstY..lastY) for (tileX in firstX..lastX) {
                val destLeft = tileX * tileSize
                val destTop = tileY * tileSize
                val copyLeft = maxOf(left, destLeft - offsetX)
                val copyTop = maxOf(top, destTop - offsetY)
                val copyRight = minOf(right, destLeft + tileSize - offsetX)
                val copyBottom = minOf(bottom, destTop + tileSize - offsetY)
                val destination = result.tiles.getOrPut(TileCoordinate(tileX, tileY)) {
                    Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
                }
                Canvas(destination).drawBitmap(
                    bitmap,
                    Rect(copyLeft - sourceLeft, copyTop - sourceTop, copyRight - sourceLeft, copyBottom - sourceTop),
                    Rect(copyLeft + offsetX - destLeft, copyTop + offsetY - destTop,
                        copyRight + offsetX - destLeft, copyBottom + offsetY - destTop),
                    null,
                )
            }
        }
        result.tiles.entries.removeAll { (_, bitmap) ->
            if (bitmap.isFullyTransparent()) { bitmap.recycle(); true } else false
        }
        result.lastDirtyTiles = result.tiles.keys.toSet()
        result.replaceColorUsage(colorUsage)
        return result
    }

    internal fun contentBounds(): RectF? {
        if (!contentBoundsInitialized) {
            dirtyContentBoundsTiles += tiles.keys
            contentBoundsInitialized = true
        }
        dirtyContentBoundsTiles.forEach { coordinate ->
            val bitmap = tiles[coordinate]
            val localBounds = bitmap?.let(::opaqueBounds)
            if (localBounds == null) tileContentBounds.remove(coordinate)
            else tileContentBounds[coordinate] = Rect(
                coordinate.x * tileSize + localBounds.left,
                coordinate.y * tileSize + localBounds.top,
                coordinate.x * tileSize + localBounds.right,
                coordinate.y * tileSize + localBounds.bottom,
            )
        }
        dirtyContentBoundsTiles.clear()
        if (tileContentBounds.isEmpty()) return null
        var left = canvasWidth
        var top = canvasHeight
        var right = 0
        var bottom = 0
        tileContentBounds.values.forEach { bounds ->
            left = minOf(left, bounds.left)
            top = minOf(top, bounds.top)
            right = maxOf(right, bounds.right)
            bottom = maxOf(bottom, bounds.bottom)
        }
        return RectF(
            left.coerceIn(0, canvasWidth).toFloat(),
            top.coerceIn(0, canvasHeight).toFloat(),
            right.coerceIn(0, canvasWidth).toFloat(),
            bottom.coerceIn(0, canvasHeight).toFloat(),
        )
    }

    private fun opaqueBounds(bitmap: Bitmap): Rect? {
        var left = bitmap.width
        var top = bitmap.height
        var right = 0
        var bottom = 0
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width) {
                if (Color.alpha(row[x]) == 0) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
        }
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }

    internal fun replaceColorUsage(replacement: Map<Int, Long>) {
        colorUsage.clear()
        replacement.filterValues { it > 0L }.forEach { (argb, weight) -> colorUsage[argb] = weight }
    }

    internal fun replaceTiles(replacement: Map<TileCoordinate, Bitmap>) {
        tiles.values.forEach(Bitmap::recycle)
        tiles.clear()
        tiles.putAll(replacement)
        tileContentBounds.clear()
        dirtyContentBoundsTiles.clear()
        contentBoundsInitialized = false
        lastDirtyTiles = replacement.keys
        history.clear()
    }

    internal fun duplicate(selection: SelectionRegion? = null): TileStore {
        val duplicate = TileStore(canvasWidth, canvasHeight, tileSize)
        val copiedTiles = if (selection == null) {
            tiles.mapValues { (_, bitmap) -> bitmap.copy(Bitmap.Config.ARGB_8888, true) }
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

    /** Removes only selected source pixels after they have been copied to another cel. */
    internal fun cutSelection(selection: SelectionRegion): Set<TileCoordinate> {
        val dirty = TileGrid.intersecting(selection.bounds, canvasWidth, canvasHeight, tileSize)
            .filterTo(mutableSetOf()) { it in tiles }
        if (dirty.isEmpty()) return emptySet()
        val before = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val path = selection.toAndroidPath()
        dirty.forEach { coordinate ->
            val bitmap = tiles[coordinate] ?: return@forEach
            Canvas(bitmap).apply {
                save()
                translate((-coordinate.x * tileSize).toFloat(), (-coordinate.y * tileSize).toFloat())
                drawPath(path, clear)
                restore()
            }
            if (bitmap.isFullyTransparent()) { bitmap.recycle(); tiles.remove(coordinate) }
        }
        val after = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }
        history.push(TileSnapshotTransaction(this, before, after))
        lastDirtyTiles = dirty
        return dirty
    }

    internal fun referenceAlpha(coordinate: TileCoordinate): ByteArray? {
        val bitmap = tiles[coordinate] ?: return null
        val pixels = IntArray(tileSize * tileSize)
        bitmap.getPixels(pixels, 0, tileSize, 0, 0, tileSize, tileSize)
        return ByteArray(pixels.size) { Color.alpha(pixels[it]).toByte() }
    }

    internal fun colorAt(x: Int, y: Int): Int {
        if (x !in 0 until canvasWidth || y !in 0 until canvasHeight) return Color.TRANSPARENT
        val coordinate = TileCoordinate(x / tileSize, y / tileSize)
        fillTileReader?.let { reader ->
            if (sampledFillTiles.add(coordinate)) {
                // Bound the worker's cache, not the size of the reachable region.
                // Evicted source tiles can be sampled again; revision validation at
                // commit still rejects any edits made during preparation.
                if (allocatedTileBytes >= 32L * 1024 * 1024) {
                    val oldest = tiles.entries.first()
                    tiles.remove(oldest.key)
                    sampledFillTiles.remove(oldest.key)
                    oldest.value.recycle()
                }
                reader(coordinate)?.let { tiles[coordinate] = it }
            }
        }
        return tiles[coordinate]?.getPixel(x % tileSize, y % tileSize) ?: Color.TRANSPARENT
    }

    internal fun beginSmudge() {
        smudgeBefore = mutableMapOf()
        smudgeTouched.clear()
    }

    internal fun smudge(fromX: Float, fromY: Float, toX: Float, toY: Float, radius: Float, strength: Float) {
        val before = smudgeBefore ?: return
        val safeRadius = radius.roundToInt().coerceIn(4, 96)
        if (strength <= 0f) return
        val safeStrength = strength.coerceIn(0f, 1f)
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
        val selectionPath = selection.toAndroidPath()
        val selectedPixels = TileGrid.intersecting(selection.bounds, canvasWidth, canvasHeight, tileSize)
            .mapNotNull { coordinate ->
                val source = tiles[coordinate] ?: return@mapNotNull null
                val clipped = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
                Canvas(clipped).apply {
                    save()
                    translate((-coordinate.x * tileSize).toFloat(), (-coordinate.y * tileSize).toFloat())
                    clipPath(selectionPath)
                    drawBitmap(source, (coordinate.x * tileSize).toFloat(), (coordinate.y * tileSize).toFloat(), null)
                    restore()
                }
                if (clipped.isFullyTransparent()) { clipped.recycle(); null } else coordinate to clipped
            }.toMap()
        if (selectedPixels.isEmpty()) return emptySet()
        val sourceTiles = selectedPixels.keys
        val destinationRegion = selection.translated(deltaX, deltaY)
        val destinationTiles = sourceTiles.flatMapTo(mutableSetOf()) { coordinate ->
            TileGrid.intersecting(dev.tipstroke.core.geometry.Rect(
                coordinate.x * tileSize + deltaX,
                coordinate.y * tileSize + deltaY,
                (coordinate.x + 1) * tileSize + deltaX,
                (coordinate.y + 1) * tileSize + deltaY,
            ), canvasWidth, canvasHeight, tileSize)
        }
        val dirty = sourceTiles + destinationTiles
        val before = dirty.associateWith { tiles[it]?.copy(Bitmap.Config.ARGB_8888, false) }

        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        sourceTiles.forEach { coordinate ->
            tiles[coordinate]?.let { bitmap ->
                Canvas(bitmap).apply {
                    save()
                    translate((-coordinate.x * tileSize).toFloat(), (-coordinate.y * tileSize).toFloat())
                    drawPath(selectionPath, clearPaint)
                    restore()
                }
            }
        }
        clearPaint.xfermode = null

        val copyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val destinationPath = destinationRegion.toAndroidPath()
        destinationTiles.forEach { destination ->
            val bitmap = tiles.getOrPut(destination) { Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888) }
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.translate((-destination.x * tileSize).toFloat(), (-destination.y * tileSize).toFloat())
            canvas.clipPath(destinationPath)
            // A translated destination tile overlaps at most four source tiles.
            // Avoid scanning the entire selection for every destination tile.
            val sourceLeft = floor((destination.x * tileSize - deltaX) / tileSize).toInt()
            val sourceTop = floor((destination.y * tileSize - deltaY) / tileSize).toInt()
            for (y in sourceTop..sourceTop + 1) for (x in sourceLeft..sourceLeft + 1) {
                val snapshot = selectedPixels[TileCoordinate(x, y)] ?: continue
                canvas.drawBitmap(snapshot, x * tileSize + deltaX, y * tileSize + deltaY, copyPaint)
            }
            canvas.restore()
        }
        selectedPixels.values.forEach(Bitmap::recycle)

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
        commands: List<ReplayPaint>? = null,
    ) : UndoTransaction {
        init { store.applyUsage(colorUsage, 1L); store.recordingListener?.invoke(commands, after) }
        override val estimatedBytes = (before.values.count { it != null } + after.values.count { it != null }).toLong() * store.tileSize * store.tileSize * 4
        override fun undo() { store.restore(before); store.recordingListener?.invoke(null, before); store.applyUsage(colorUsage, -1L) }
        override fun redo() { store.restore(after); store.recordingListener?.invoke(null, after); store.applyUsage(colorUsage, 1L) }
    }
}
