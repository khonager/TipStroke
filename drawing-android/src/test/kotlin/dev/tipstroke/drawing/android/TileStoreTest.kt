package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.TileCoordinate
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

    @Test fun colorUsageFollowsPaintUndoAndRedo() {
        val store = TileStore(512, 512)
        val red = ink.copy(color = RgbaColor(1f, 0f, 0f))
        store.commit(stroke(Point(20f, 20f), Point(220f, 20f), red))
        assertEquals(1, store.snapshotColorUsage().size)

        assertTrue(store.history.undo())
        assertTrue(store.snapshotColorUsage().isEmpty())

        assertTrue(store.history.redo())
        assertEquals(1, store.snapshotColorUsage().size)
    }

    @Test fun eraserClearsToTransparencyRatherThanWhite() {
        val store = TileStore(512, 512)
        store.commit(stroke(Point(30f, 30f), Point(220f, 30f), ink))
        val erase = ink.copy(sizePx = 40f, blend = BlendBehavior.ERASE)
        store.beginLiveStroke()
        store.appendLiveStroke(stroke(Point(30f, 30f), Point(220f, 30f), erase))
        val result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        store.draw(Canvas(result), Paint())
        assertEquals(0, Color.alpha(result.getPixel(100, 30)))
        store.finishLiveStroke()
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

    @Test fun airbrushWetPainterMatchesCommittedPixels() {
        val airbrush = StrokeStyle(BrushPreset.Airbrush, 48f, .37f, RgbaColor(.8f, .2f, .1f), BlendBehavior.PAINT)
        val points = listOf(sample(Point(60f, 90f), 0), sample(Point(125f, 115f), 1), sample(Point(190f, 140f), 2))
        val completed = CompletedStroke(points, airbrush)
        val preview = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        StrokeCanvasPainter.draw(Canvas(preview), completed)
        val store = TileStore(256, 256)
        store.beginLiveStroke()
        store.appendLiveStroke(CompletedStroke(points.subList(0, 2), airbrush))
        store.appendLiveStroke(CompletedStroke(points.subList(1, 3), airbrush))
        store.finishLiveStroke()
        val committed = store.snapshotTiles().getValue(TileCoordinate(0, 0))
        val previewPixels = IntArray(256 * 256)
        val committedPixels = IntArray(256 * 256)
        preview.getPixels(previewPixels, 0, 256, 0, 0, 256, 256)
        committed.getPixels(committedPixels, 0, 256, 0, 0, 256, 256)
        assertArrayEquals(previewPixels, committedPixels)
        preview.recycle()
        committed.recycle()
    }

    @Test fun canceledLiveEraserRestoresPixelsAndDoesNotAddHistory() {
        val store = TileStore(256, 256)
        store.commit(stroke(Point(30f, 80f), Point(220f, 80f), ink))
        val historyBefore = store.history.estimatedBytes
        val before = store.snapshotTiles().getValue(TileCoordinate(0, 0))
        val erase = ink.copy(sizePx = 52f, blend = BlendBehavior.ERASE)
        store.beginLiveStroke()
        store.appendLiveStroke(stroke(Point(90f, 80f), Point(160f, 80f), erase))
        store.cancelLiveStroke()
        val restored = store.snapshotTiles().getValue(TileCoordinate(0, 0))
        val expected = IntArray(256 * 256)
        val actual = IntArray(256 * 256)
        before.getPixels(expected, 0, 256, 0, 0, 256, 256)
        restored.getPixels(actual, 0, 256, 0, 0, 256, 256)
        assertArrayEquals(expected, actual)
        assertEquals(historyBefore, store.history.estimatedBytes)
        before.recycle()
        restored.recycle()
    }

    @Test fun selectionClipRestrictsAnEraserStroke() {
        val store = TileStore(256, 256)
        store.commit(stroke(Point(20f, 80f), Point(230f, 80f), ink.copy(sizePx = 50f)))
        val erase = ink.copy(
            sizePx = 50f,
            blend = BlendBehavior.ERASE,
            selection = dev.tipstroke.core.geometry.SelectionRegion.rectangle(dev.tipstroke.core.geometry.Rect(100f, 0f, 150f, 256f)),
        )
        store.beginLiveStroke()
        store.appendLiveStroke(stroke(Point(20f, 80f), Point(230f, 80f), erase))
        store.finishLiveStroke()

        assertNotEquals(0, Color.alpha(store.colorAt(70, 80)))
        assertEquals(0, Color.alpha(store.colorAt(125, 80)))
        assertNotEquals(0, Color.alpha(store.colorAt(180, 80)))
    }

    @Test fun movesOnlySelectedPixelsAndUndoRestoresTheirOriginalPosition() {
        val store = TileStore(256, 256)
        store.commit(stroke(Point(20f, 80f), Point(100f, 80f), ink.copy(sizePx = 20f)))
        val selection = dev.tipstroke.core.geometry.SelectionRegion.rectangle(
            dev.tipstroke.core.geometry.Rect(40f, 60f, 80f, 100f),
        )

        store.moveSelection(selection, 100f, 0f)
        assertNotEquals(0, Color.alpha(store.colorAt(25, 80)))
        assertEquals(0, Color.alpha(store.colorAt(55, 80)))
        assertNotEquals(0, Color.alpha(store.colorAt(155, 80)))

        assertTrue(store.history.undo())
        assertNotEquals(0, Color.alpha(store.colorAt(55, 80)))
        assertEquals(0, Color.alpha(store.colorAt(155, 80)))
    }

    private fun stroke(a: Point, b: Point, style: StrokeStyle) = CompletedStroke(
        listOf(sample(a, 0), sample(b, 1)), style
    )

    private fun sample(point: Point, t: Long) = StrokeSample(1, point, 1f, 0f, 0f, t, 0, PointerKind.STYLUS)
}
