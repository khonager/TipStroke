package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
class TileStoreTest {
    private val ink = StrokeStyle(BrushPreset.Ink, 24f, 1f, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT)

    @Test fun allocatesOnlyIntersectingTilesAndUndoRemovesThem() {
        val store = TileStore(2048, 2048)
        store.commit(stroke(Point(250f, 80f), Point(270f, 80f), ink))
        assertEquals(2, store.allocatedTileCount)
        assertTrue(store.history.undo())
        assertEquals(0, store.allocatedTileCount)
        assertTrue(store.history.redo())
        assertEquals(2, store.allocatedTileCount)
    }

    @Test fun eraserClearsToTransparencyRatherThanWhite() {
        val store = TileStore(512, 512)
        store.commit(stroke(Point(30f, 30f), Point(220f, 30f), ink))
        val erase = ink.copy(sizePx = 40f, blend = BlendBehavior.ERASE)
        store.commit(stroke(Point(30f, 30f), Point(220f, 30f), erase))
        val result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        store.draw(Canvas(result), Paint())
        assertEquals(0, Color.alpha(result.getPixel(100, 30)))
    }

    @Test fun fingerSmudgeIsGroupedIntoOneUndoStep() {
        val store = TileStore(512, 512)
        store.commit(stroke(Point(80f, 80f), Point(120f, 80f), ink))
        val historyBefore = store.history.estimatedBytes
        store.beginSmudge()
        store.smudge(100f, 80f, 150f, 80f, 28f, .8f)
        store.smudge(150f, 80f, 190f, 90f, 28f, .8f)
        store.finishSmudge()
        assertTrue(store.history.estimatedBytes > historyBefore)
        assertTrue(store.history.undo())
    }

    private fun stroke(a: Point, b: Point, style: StrokeStyle) = CompletedStroke(
        listOf(sample(a, 0), sample(b, 1)), style
    )

    private fun sample(point: Point, t: Long) = StrokeSample(1, point, 1f, 0f, 0f, t, 0, PointerKind.STYLUS)
}
