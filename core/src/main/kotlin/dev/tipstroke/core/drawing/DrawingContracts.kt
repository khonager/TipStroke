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
data class StrokeStyle(
    val brush: BrushPreset,
    val sizePx: Float,
    val opacity: Float,
    val color: RgbaColor,
    val blend: BlendBehavior,
    /** Optional transient selection clip; it is never persisted as artwork. */
    val selection: SelectionRegion? = null,
)
data class CompletedStroke(val samples: List<StrokeSample>, val style: StrokeStyle) {
    val bounds: Rect by lazy {
        require(samples.isNotEmpty())
        // Custom tips can extend beyond half of the nominal brush size. Keep bounds
        // conservative so a rotated, tilted pencil or scattered airbrush particle
        // can never be clipped at a sparse-tile boundary.
        val radius = style.sizePx * when (style.brush.engine) {
            BrushEngine.PENCIL -> 1.35f
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
    var estimatedBytes: Long = 0; private set
    fun push(transaction: UndoTransaction) {
        undo.addLast(transaction); redo.clear(); estimatedBytes += transaction.estimatedBytes
        while (estimatedBytes > memoryBudgetBytes && undo.size > 1) estimatedBytes -= undo.removeFirst().estimatedBytes
    }
    fun undo(): Boolean = undo.removeLastOrNull()?.let { it.undo(); redo.addLast(it); true } ?: false
    fun redo(): Boolean = redo.removeLastOrNull()?.let { it.redo(); undo.addLast(it); true } ?: false
    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()
    fun clear() { undo.clear(); redo.clear(); estimatedBytes = 0 }
}
