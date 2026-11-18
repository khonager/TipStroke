package dev.tipstroke.drawing.android

import android.graphics.Color
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
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
class PaintInsideTest {
    @Test fun shadingPreservesSoftAlphaAndCannotGrowIntoTransparentTiles() {
        val store = TileStore(512, 512)
        store.putFillPixel(255, 50, Color.argb(128, 255, 0, 0))
        store.putFillPixel(256, 50, Color.RED)
        val style = StrokeStyle(BrushPreset.Ink, 30f, 1f, RgbaColor(0f, 0f, 1f), BlendBehavior.PAINT,
            referenceStrokes = ReferenceStrokes(store, true))
        val samples = listOf(StrokeSample(0, Point(256f, 50f), 1f, 0f, 0f, 0, 0, PointerKind.STYLUS))
        store.commit(CompletedStroke(samples, style))
        assertEquals(128, Color.alpha(store.colorAt(255, 50)))
        assertEquals(255, Color.alpha(store.colorAt(256, 50)))
        assertEquals(0, Color.alpha(store.colorAt(257, 50)))
        assertTrue(Color.blue(store.colorAt(256, 50)) > 0)
        assertTrue(store.history.undo())
        assertEquals(Color.RED, store.colorAt(256, 50))
    }
}
