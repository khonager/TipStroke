package dev.tipstroke.drawing.android

import android.graphics.Color
import dev.tipstroke.core.geometry.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorPushTest {
    private fun store() = TileStore(512, 512).apply {
        for (y in 230..280) for (x in 230..255) putFillPixel(x, y, Color.BLUE)
    }
    @Test fun pushesAcrossTileBoundaryAndUndoesAsOneGesture() {
        val store = store()
        store.beginSmudge()
        store.pushColor(250f, 255f, 262f, 255f, 30f, 1f)
        assertTrue(Color.alpha(store.colorAt(260, 255)) > 200)
        assertEquals(Color.BLUE, store.colorAt(230, 230))
        store.finishSmudge()
        assertTrue(store.history.undo())
        assertEquals(0, Color.alpha(store.colorAt(260, 255)))
        assertFalse(store.history.canUndo())
        assertTrue(store.history.redo())
        assertTrue(Color.alpha(store.colorAt(260, 255)) > 200)
    }
    @Test fun cancellationRestoresPixelsAndSelectionProtectsOutside() {
        val store = store()
        val clip = SelectionRegion(listOf(Point(0f,0f),Point(256f,0f),Point(256f,512f),Point(0f,512f)))
        store.beginSmudge()
        store.pushColor(250f,255f,262f,255f,30f,1f,clip)
        assertEquals(0, Color.alpha(store.colorAt(260,255)))
        store.cancelSmudge()
        assertEquals(Color.BLUE, store.colorAt(255,255))
        assertFalse(store.history.canUndo())
    }
}
