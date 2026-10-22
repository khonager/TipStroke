package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.drawing.StrokeStyle
import dev.tipstroke.core.drawing.PointerKind
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.BlendBehavior
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.RgbaColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FillRevealTest {
    private fun prepare(size: Int): Pair<TileStore, FillReveal> {
        val store = TileStore(size, size)
        val fill = AnimatedFill(store, 0, 0, Color.RED, null, prepareReveal = true)
        while (!fill.complete) fill.advance()
        val reveal = FillReveal(fill.takeRevealMasks(), 1000L)
        store.commitFill(fill.preview, Color.RED, fill.pixelCount, reveal)
        fill.discard()
        return store to reveal
    }

    private fun render(store: TileStore, scale: Float = 1f): Bitmap {
        val bitmap = Bitmap.createBitmap((store.canvasWidth * scale).toInt(), (store.canvasHeight * scale).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { scale(scale, scale); store.draw(this, Paint()) }
        return bitmap
    }

    @Test fun durationIs2500MillisecondsForDifferentAreasAndZoomLevels() {
        for (size in listOf(32, 512)) {
            val (store, reveal) = prepare(size)
            assertFalse(reveal.update(1000L))
            assertEquals(Color.TRANSPARENT, render(store).getPixel(size / 2, size / 2))
            assertFalse(reveal.update(2250L))
            for (zoom in listOf(1f, 2f)) {
                val image = render(store, zoom)
                assertEquals(Color.RED, image.getPixel((size * .25f * zoom).toInt(), (size * .25f * zoom).toInt()))
                assertEquals(Color.TRANSPARENT, image.getPixel((size * .9f * zoom).toInt(), (size * .9f * zoom).toInt()))
            }
            assertFalse(reveal.update(3499L))
            assertTrue(reveal.update(3500L))
            assertEquals(Color.RED, render(store).getPixel(size - 1, size - 1))
            reveal.discard()
            store.fillReveal = null
        }
    }

    @Test fun translucentSourceAndLayerOpacityDoNotDoubleCompositeDuringReveal() {
        val store = TileStore(16, 16)
        for (y in 0 until 16) store.putFillPixel(8, y, Color.argb(128, 0, 0, 0))
        val fill = AnimatedFill(store, 0, 8, Color.RED, null, prepareReveal = true)
        while (!fill.complete) fill.advance()
        val reveal = FillReveal(fill.takeRevealMasks(), 0L)
        store.commitFill(fill.preview, Color.RED, fill.pixelCount, reveal)
        fill.discard()
        reveal.update(0L)
        assertEquals(Color.argb(128, 0, 0, 0), render(store).getPixel(8, 8))
        reveal.update(2500L)
        val expected = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { alpha = 128 }
        store.draw(Canvas(actual), paint)
        store.fillReveal = null
        store.draw(Canvas(expected), paint)
        assertEquals(expected.getPixel(8, 8), actual.getPixel(8, 8))
        assertEquals(expected.getPixel(2, 8), actual.getPixel(2, 8))
        reveal.discard()
    }

    @Test fun drawingDuringRevealStaysAboveFillAndUndoOrderIsPreserved() {
        val (store, reveal) = prepare(512)
        reveal.update(1100L)
        val brush = StrokeStyle(BrushPreset.Ink, 16f, 1f, RgbaColor(0f, 0f, 1f), BlendBehavior.PAINT)
        store.commit(CompletedStroke(listOf(StrokeSample(1, Point(400f, 400f), 1f, 0f, 0f, 0L, 0, PointerKind.STYLUS)), brush))
        assertEquals(Color.BLUE, render(store).getPixel(400, 400))
        reveal.update(3500L)
        assertEquals(Color.BLUE, render(store).getPixel(400, 400))
        reveal.discard(); store.fillReveal = null
        assertTrue(store.history.undo())
        assertEquals(Color.RED, store.colorAt(400, 400))
        assertTrue(store.history.undo())
        assertEquals(Color.TRANSPARENT, store.colorAt(400, 400))
        assertTrue(store.history.redo())
        assertEquals(Color.RED, store.colorAt(400, 400))
    }

    @Test fun erasingDuringRevealIsNotPaintedOverWhenAnimationFinishes() {
        val (store, reveal) = prepare(512)
        reveal.update(1200L)
        val erase = StrokeStyle(BrushPreset.Ink, 16f, 1f, RgbaColor(0f, 0f, 0f), BlendBehavior.ERASE)
        store.commit(CompletedStroke(listOf(StrokeSample(1, Point(400f, 400f), 1f, 0f, 0f, 0L, 0, PointerKind.STYLUS)), erase))
        assertEquals(Color.TRANSPARENT, render(store).getPixel(400, 400))
        reveal.update(3500L)
        assertEquals(Color.TRANSPARENT, render(store).getPixel(400, 400))
        reveal.discard(); store.fillReveal = null
    }
}
