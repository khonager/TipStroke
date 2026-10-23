package dev.tipstroke.drawing.android

import android.graphics.Color
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.*
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceStrokesTest {
    private fun source() = TileStore(512, 256).apply {
        for (x in 248..265) for (y in 20..40) putFillPixel(x, y, Color.argb(128, 0, 0, 255))
    }
    private fun stroke(coverage: StrokeCoverage, blend: BlendBehavior = BlendBehavior.PAINT, selection: SelectionRegion? = null) = CompletedStroke(
        listOf(230f, 280f).mapIndexed { i, x -> StrokeSample(0, Point(x, 30f), 1f, 0f, 0f, i * 10_000_000L, 0, PointerKind.STYLUS) },
        StrokeStyle(BrushPreset.Ink, 20f, 1f, RgbaColor(1f, 0f, 0f), blend, selection, referenceStrokes = coverage),
    )
    @Test fun crossLayerCoverageKeepsSoftEdgesAndSupportsUndoAcrossTiles() {
        val source = source()
        val destination = TileStore(512, 256)
        destination.commit(stroke(ReferenceStrokes(source, false)))
        assertEquals(0, Color.alpha(destination.colorAt(240, 30)))
        for (x in listOf(250, 260)) assertEquals(128, Color.alpha(destination.colorAt(x, 30)))
        assertEquals(Color.argb(128, 0, 0, 255), source.colorAt(250, 30))
        assertTrue(destination.history.undo())
        assertEquals(0, destination.allocatedTileCount)
        assertTrue(destination.history.redo())
        assertEquals(128, Color.alpha(destination.colorAt(260, 30)))
    }
    @Test fun sameLayerShadingPreservesAlphaThroughoutLiveStrokeAndCancellation() {
        val store = source()
        val stroke = stroke(ReferenceStrokes(store, true))
        store.beginLiveStroke()
        repeat(3) { store.appendLiveStroke(stroke) }
        assertEquals(128, Color.alpha(store.colorAt(250, 30)))
        assertEquals(0, Color.blue(store.colorAt(250, 30)))
        assertEquals(0, Color.alpha(store.colorAt(240, 30)))
        store.cancelLiveStroke()
        assertEquals(Color.argb(128, 0, 0, 255), store.colorAt(250, 30))
    }
    @Test fun referenceCombinesWithSubtractedSelectionAndErasing() {
        val reference = source()
        val destination = TileStore(512, 256).apply {
            for (x in 230..280) putFillPixel(x, 30, Color.GREEN)
        }
        val all = SelectionRegion.rectangle(Rect(0f, 0f, 512f, 256f))
        val hole = SelectionRegion.rectangle(Rect(256f, 20f, 270f, 40f))
        destination.commit(stroke(ReferenceStrokes(reference, false), BlendBehavior.ERASE, all.combined(hole, SelectionOperation.SUBTRACT)))
        assertEquals(255, Color.alpha(destination.colorAt(240, 30)))
        assertEquals(127, Color.alpha(destination.colorAt(250, 30)))
        assertEquals(255, Color.alpha(destination.colorAt(260, 30)))
    }
    @Test fun emptyReferenceDoesNotCreatePaint() {
        val destination = TileStore(512, 256)
        destination.commit(stroke(ReferenceStrokes(TileStore(512, 256), false)))
        assertEquals(0, destination.allocatedTileCount)
    }
}
