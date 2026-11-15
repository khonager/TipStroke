package dev.tipstroke.core.drawing

import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.Rect
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.BlendBehavior
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.BrushEngine
import dev.tipstroke.core.model.RgbaColor

enum class PointerKind { STYLUS, ERASER_STYLUS, FINGER, MOUSE, UNKNOWN }
enum class PixelTool { PENCIL, LINE, DITHER }
data class StrokeSample(
    val pointerId: Int,
    val position: Point,
    val pressure: Float,
    val tiltRadians: Float,
    val orientationRadians: Float,
    val elapsedNanos: Long,
    val buttonState: Int,
    val kind: PointerKind,
    /** Pressure used only for opacity; may be stabilized without changing size taper. */
    val opacityPressure: Float = pressure,
)
/** Transient paint coverage, sampled in document pixels. */
interface StrokeCoverage { fun alphaAt(x: Int, y: Int): Int }

data class StrokeStyle(
    val brush: BrushPreset,
    val sizePx: Float,
    val opacity: Float,
    val color: RgbaColor,
    val blend: BlendBehavior,
    /** Optional transient selection clip; it is never persisted as artwork. */
    val selection: SelectionRegion? = null,
    /** Transient pixel tool; it does not change the saved raster document format. */
    val pixelTool: PixelTool? = null,
    val referenceStrokes: StrokeCoverage? = null,
)
data class CompletedStroke(val samples: List<StrokeSample>, val style: StrokeStyle) {
    /** Exact closure is emitted by fitted shapes and survives ordinary sample serialization. */
    val isClosed: Boolean get() = samples.size > 3 && samples.first().position == samples.last().position
    val bounds: Rect by lazy {
        require(samples.isNotEmpty())
        // Custom tips can extend beyond half of the nominal brush size. Keep bounds
        // conservative so a rotated, tilted pencil or scattered airbrush particle
        // can never be clipped at a sparse-tile boundary.
        val custom = style.brush.stamp
        val radius = style.sizePx * if (style.blend == BlendBehavior.ERASE) 1.35f
        else if (style.brush.ink != null) (1f + style.brush.ink.tiltSize) * .8f + 1f
        else if (custom != null) (1f + custom.tiltSize) * (1f + custom.scatter)
        else when (style.brush.engine) {
            BrushEngine.PENCIL -> maxOf(
                1.75f,
                // Pencil's base contact is 18% of nominal size. Half of that is .09;
                // .1 leaves room for its bounded edge variation at the largest custom boost.
                style.brush.pencilPointSize *
                    (1f + 9f * style.brush.pencilShadeSize) * .1f,
            )
            BrushEngine.AIRBRUSH -> 1.35f
            BrushEngine.INK -> .55f
        }
        Rect(samples.minOf { it.position.x }, samples.minOf { it.position.y },
            samples.maxOf { it.position.x }, samples.maxOf { it.position.y }).expanded(radius)
    }
}

interface LiveStrokeRenderer { fun cancelAll() }
interface StrokeRasterizer { fun commit(stroke: CompletedStroke): Set<TileCoordinate> }
interface LayerCompositor { fun invalidateTiles(tiles: Set<TileCoordinate>) }
interface UndoTransaction { val estimatedBytes: Long; fun undo(); fun redo() }

class UndoHistory(private val memoryBudgetBytes: Long) {
    private val undo = ArrayDeque<UndoTransaction>()
    private val redo = ArrayDeque<UndoTransaction>()
    // Snapshot transactions exchange the current and opposite state on undo/redo,
    // so their retained size can change. Derive the total instead of caching it.
    val estimatedBytes: Long get() = undo.sumOf { it.estimatedBytes } + redo.sumOf { it.estimatedBytes }
    fun push(transaction: UndoTransaction) {
        redo.clear()
        undo.addLast(transaction)
        trim()
    }
    fun undo(): Boolean = undo.removeLastOrNull()?.let { it.undo(); redo.addLast(it); trim(preserveRedo = true); true } ?: false
    fun redo(): Boolean = redo.removeLastOrNull()?.let { it.redo(); undo.addLast(it); trim(); true } ?: false
    private fun trim(preserveRedo: Boolean = false) {
        while (undo.size + redo.size > 1 &&
            (estimatedBytes > memoryBudgetBytes || undo.size + redo.size > 256)) {
            // Remove only the farthest operation on either side, preserving a
            // contiguous path through history and the most recent swapped edit.
            if (undo.size > 1 || (preserveRedo && undo.isNotEmpty())) undo.removeFirst()
            else if (redo.isNotEmpty()) redo.removeFirst() else undo.removeFirst()
        }
    }
    /** Transfers ownership of the newest edit into a compound operation. */
    fun takeLatestUndoTransaction(): UndoTransaction? = undo.removeLastOrNull()
    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()
    fun clear() { undo.clear(); redo.clear() }
}
