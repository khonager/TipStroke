package dev.tipstroke.drawing.android

import android.graphics.Color
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.Rect
import dev.tipstroke.core.geometry.SelectionRegion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedFillTest {
    private fun finish(fill: AnimatedFill) {
        var iterations = 0
        while (!fill.complete) { fill.advance(); check(++iterations < 10000) }
    }

    @Test fun closedBoundaryContainsFillAndOneUndoRestoresSparseTiles() {
        val store = TileStore(300, 32)
        for (y in 0 until 32) store.putFillPixel(260, y, Color.BLACK)
        val fill = AnimatedFill(store, 250, 10, Color.RED, null)
        finish(fill)
        assertEquals(Color.TRANSPARENT, store.colorAt(250, 10)) // Preview is isolated.
        assertEquals(Color.RED, fill.preview.colorAt(259, 10))
        assertEquals(Color.TRANSPARENT, fill.preview.colorAt(261, 10))
        store.commitFill(fill.preview, Color.RED, fill.pixelCount)
        fill.preview.discard()
        assertEquals(Color.RED, store.colorAt(259, 10))
        assertTrue(store.history.undo())
        assertEquals(Color.TRANSPARENT, store.colorAt(259, 10))
        assertEquals(Color.BLACK, store.colorAt(260, 10))
        assertFalse(store.history.canUndo())
        assertTrue(store.history.redo())
        assertEquals(Color.RED, store.colorAt(259, 10))
    }

    @Test fun waveSpreadsThroughSinglePixelGapAndCanBeDiscarded() {
        val store = TileStore(64, 64)
        for (y in 0 until 64) if (y != 50) store.putFillPixel(32, y, Color.BLACK)
        val fill = AnimatedFill(store, 30, 2, Color.BLUE, null)
        fill.advance()
        assertEquals(Color.TRANSPARENT, fill.preview.colorAt(34, 2))
        finish(fill)
        assertEquals(Color.BLUE, fill.preview.colorAt(34, 2))
        fill.preview.discard()
        assertEquals(Color.TRANSPARENT, store.colorAt(30, 2))
        assertFalse(store.history.canUndo())
    }

    @Test fun selectionAndCanvasEdgesConstrainFill() {
        val store = TileStore(300, 20)
        val fill = AnimatedFill(store, 258, 8, Color.GREEN,
            SelectionRegion.rectangle(Rect(254f, 5f, 300f, 15f)))
        finish(fill)
        assertEquals(Color.GREEN, fill.preview.colorAt(299, 14))
        assertEquals(Color.TRANSPARENT, fill.preview.colorAt(253, 8))
        assertEquals(Color.TRANSPARENT, fill.preview.colorAt(258, 15))
        assertEquals(460L, fill.pixelCount)
    }

    @Test fun sameColorAndTransparentDropsDoNotCreateHistory() {
        val store = TileStore(16, 16)
        store.putFillPixel(1, 1, Color.RED)
        assertTrue(AnimatedFill(store, 1, 1, Color.RED, null).complete)
        assertTrue(AnimatedFill(store, 2, 2, Color.TRANSPARENT, null).complete)
        assertFalse(store.history.canUndo())
    }
}
