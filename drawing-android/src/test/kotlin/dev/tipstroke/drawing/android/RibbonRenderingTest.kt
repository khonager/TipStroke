package dev.tipstroke.drawing.android

import android.graphics.*
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
class RibbonRenderingTest {
    @Test fun previewMatchesSparseCommitAcrossTileBoundary() {
        val points = listOf(Point(200f, 100f), Point(300f, 100f), Point(300f, 300f), Point(400f, 200f))
        val stroke = CompletedStroke(points.map { StrokeSample(0, it, 1f, 0f, 0f, 0, 0, PointerKind.STYLUS) },
            StrokeStyle(BrushPreset.Blank, 50f, .5f, RgbaColor(0f, 0f, 1f), BlendBehavior.PAINT, ribbon = true))
        val preview = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        StrokeCanvasPainter.draw(Canvas(preview), stroke)
        val store = TileStore(512, 512)
        var patch = false
        store.recordingListener = { commands, _ -> patch = commands == null }
        store.commit(stroke)
        var filled = 0
        for (y in 0 until 512 step 3) for (x in 0 until 512 step 3) {
            // Skia's clipped edge coverage can round by a few alpha units per tile.
            assertTrue("coverage at $x,$y: ${Color.alpha(preview.getPixel(x, y))} vs ${Color.alpha(store.colorAt(x, y))}", kotlin.math.abs(Color.alpha(preview.getPixel(x, y)) - Color.alpha(store.colorAt(x, y))) <= 8)
            if (Color.alpha(preview.getPixel(x, y)) > 3) assertEquals(Color.blue(preview.getPixel(x, y)), Color.blue(store.colorAt(x, y)))
            if (Color.alpha(preview.getPixel(x, y)) > 0) filled++
        }
        assertTrue(filled > 100)
        assertTrue(patch)
        assertTrue(store.history.undo())
        assertEquals(0, store.allocatedTileCount)
    }
}
