package dev.tipstroke.drawing.android

import android.graphics.*
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
class StampBrushRenderingTest {
    private fun stroke(brush: BrushPreset = BrushPreset.Blank): CompletedStroke {
        val samples = (0..30).map { i -> StrokeSample(0, Point(200f + i * 10f, 240f + i), .8f, .5f, 0f, i * 10_000_000L, 0, PointerKind.STYLUS) }
        return CompletedStroke(samples, StrokeStyle(brush, 42f, .8f, RgbaColor(.2f, .3f, .8f), BlendBehavior.PAINT))
    }

    @Test fun liveAndCommittedStampsMatchAcrossTilesAndUndoAsOneStroke() {
        val brush = BrushPreset.Blank.copy(stamp = StampBrush(shape = StampShape.SQUARE, grain = StampGrain.PAPER,
            rotationJitter = 50f, scatter = .6f, count = 2, hueJitter = .3f))
        val stroke = stroke(brush)
        val expected = TileStore(768, 512).also { it.commit(stroke) }.snapshotTiles()
        val live = TileStore(768, 512)
        var captured: List<ReplayPaint>? = emptyList()
        live.recordingListener = { commands, _ -> captured = commands }
        live.beginLiveStroke()
        live.appendLiveStroke(stroke.copy(samples = stroke.samples.take(1)))
        stroke.samples.zipWithNext().forEach { (a, b) -> live.appendLiveStroke(stroke.copy(samples = listOf(a, b))) }
        live.finishLiveStroke(stroke)
        val actual = live.snapshotTiles()
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (key, bitmap) -> assertTrue("Tile $key differs", bitmap.sameAs(actual.getValue(key))) }
        assertNull("Custom strokes must record exact patches", captured)
        assertTrue(live.history.undo())
        assertEquals(0, live.allocatedTileCount)
        assertFalse(live.history.canUndo())
        assertTrue(live.history.redo())
        assertTrue(actual.getValue(TileCoordinate(1, 1)).sameAs(live.snapshotTiles().getValue(TileCoordinate(1, 1))))
    }

    @Test fun arbitraryMaskAndGrainChangeTheActualPaintedPixels() {
        fun render(brush: BrushPreset): Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also {
            val one = stroke(brush).copy(samples = listOf(StrokeSample(0, Point(64f, 64f), 1f, 0f, 0f, 0, 0, PointerKind.STYLUS)))
            StrokeCanvasPainter.draw(Canvas(it), one)
        }
        val blank = render(BrushPreset.Blank)
        assertTrue(Color.alpha(blank.getPixel(64, 64)) > 0)
        val hole = BrushTexture(16, 16, ByteArray(256) { i -> if (i % 16 in 5..10 && i / 16 in 5..10) 0 else -1 })
        val shaped = render(BrushPreset.Blank.copy(stamp = StampBrush(shape = StampShape.IMAGE, shapeTexture = hole)))
        assertEquals(0, Color.alpha(shaped.getPixel(64, 64)))
        assertTrue(Color.alpha(shaped.getPixel(49, 64)) > 0)
        val grain = render(BrushPreset.Blank.copy(stamp = StampBrush(grain = StampGrain.LINES, grainDepth = 1f, grainScale = 64f)))
        assertFalse(blank.sameAs(grain))
    }

    @Test fun drawingSurfacePaintsCustomBrushWhileDownAndCommitsWithoutChangingPixels() {
        val surface = DrawingSurface(org.robolectric.RuntimeEnvironment.getApplication()).apply {
            layout(0, 0, 800, 800); configureBlank(512, 512)
            settings.brush = BrushPreset.Blank.copy(stamp = StampBrush(grain = StampGrain.PAPER))
            settings.sizePx = 32f
        }
        val raster = surface.getChildAt(0) as RasterCanvasView
        var canUndo = false
        surface.historyListener = { undo, _ -> canUndo = undo }
        val down = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, time: Long) {
            val properties = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_STYLUS }
            val point = android.view.MotionEvent.PointerCoords().apply { this.x = x; y = 400f; pressure = 1f; size = 1f }
            val event = android.view.MotionEvent.obtain(down, down + time, action, 1, arrayOf(properties), arrayOf(point), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
            try { surface.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        event(android.view.MotionEvent.ACTION_DOWN, 280f, 0)
        event(android.view.MotionEvent.ACTION_MOVE, 480f, 10)
        val store = requireNotNull(raster.layerStack.selectedStore())
        val wet = store.snapshotTiles()
        assertTrue(wet.isNotEmpty())
        assertFalse(canUndo)
        assertNull(raster.previewStroke)
        event(android.view.MotionEvent.ACTION_UP, 480f, 20)
        val dry = store.snapshotTiles()
        assertTrue(dry.isNotEmpty())
        wet.forEach { (key, bitmap) ->
            val result = dry[key] ?: Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            assertTrue(bitmap.sameAs(result))
        }
        assertTrue(canUndo)
        surface.undo()
        assertEquals(0, store.allocatedTileCount)
    }

    @Test fun cancelledLiveCustomStrokeRestoresExistingArtwork() {
        val store = TileStore(768, 512)
        store.commit(stroke(BrushPreset.Pencil))
        val before = store.snapshotTiles()
        store.beginLiveStroke(); store.appendLiveStroke(stroke()); store.cancelLiveStroke()
        val after = store.snapshotTiles()
        assertEquals(before.keys, after.keys)
        before.forEach { (key, bitmap) -> assertTrue(bitmap.sameAs(after.getValue(key))) }
    }
}
