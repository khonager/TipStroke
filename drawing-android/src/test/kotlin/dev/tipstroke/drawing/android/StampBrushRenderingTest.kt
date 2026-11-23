package dev.tipstroke.drawing.android

import android.graphics.*
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import kotlin.math.roundToInt
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

    @Test fun calligraphyMakesHairlinesBroadStrokesAndOpenCounters() {
        val brush = BrushPreset.Calligraphy
        val style = StrokeStyle(brush, brush.baseSizePx, 1f, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT)
        fun render(points: List<Point>): Bitmap {
            val samples = points.mapIndexed { i, point ->
                StrokeSample(0, point, .8f, 0f, 0f, i * 16_000_000L, 0, PointerKind.FINGER)
            }
            return Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also {
                StrokeCanvasPainter.draw(Canvas(it), CompletedStroke(samples, style))
            }
        }
        val hairline = render(listOf(Point(30f, 115f), Point(130f, 45f)))
        val broad = render(listOf(Point(45f, 30f), Point(115f, 130f)))
        val thinWidth = (55..105).count { Color.alpha(hairline.getPixel(80, it)) >= 128 }
        val broadWidth = (55..105).count { Color.alpha(broad.getPixel(it, 80)) >= 128 }
        assertTrue("Hairline $thinWidth, broad stroke $broadWidth", thinWidth > 0 && broadWidth >= 4 * thinWidth)
        val loop = render((0..64).map {
            val angle = it * Math.PI / 32
            Point(80f + 24f * kotlin.math.cos(angle).toFloat(), 80f + 40f * kotlin.math.sin(angle).toFloat())
        })
        assertEquals(0, Color.alpha(loop.getPixel(80, 80)))
        assertTrue(Color.alpha(loop.getPixel(104, 80)) > 200)
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

    @Test fun easyFingerKeepsFlowAndItsWetPixelsAcrossTileBoundaries() {
        // Reproduction from the supplied easy finger tipbrush, including dense spacing.
        val brush = BrushPreset.Blank.copy(baseSizePx = 180f, hardness = 1f, spacing = .02f,
            pressureToSize = PressureCurve(1f, 1f), pressureToOpacity = PressureCurve(1f, 1f),
            speedTaper = 0f, stamp = StampBrush(grainDepth = 0f, grainScale = 2f,
                rotationDegrees = 10.057312f, flow = .5834822f))
        val stroke = stroke(brush).copy(style = stroke(brush).style.copy(sizePx = 180f, opacity = 1f))
        val store = TileStore(768, 512)
        store.beginLiveStroke()
        store.appendLiveStroke(stroke.copy(samples = stroke.samples.take(1)))
        assertTrue(kotlin.math.abs((255 * .5834822f).roundToInt() - Color.alpha(store.colorAt(200, 240))) <= 1)
        stroke.samples.zipWithNext().forEach { (a, b) ->
            store.appendLiveStroke(stroke.copy(samples = listOf(a, b)))
        }
        val wet = store.snapshotTiles()
        store.finishLiveStroke(stroke)
        val expected = TileStore(768, 512).also { it.commit(stroke) }.snapshotTiles()
        assertEquals(expected.keys, wet.keys)
        expected.forEach { (key, bitmap) -> assertTrue("Dense stamp tile $key differs", bitmap.sameAs(wet.getValue(key))) }
        assertTrue(store.history.undo())
        assertEquals(0, store.allocatedTileCount)
        assertTrue(store.history.redo())
        val restored = store.snapshotTiles()
        wet.forEach { (key, bitmap) -> assertTrue(bitmap.sameAs(restored.getValue(key))) }
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

    @Test fun stampTipsKeepEarlierMarksVisibleUntilLift() {
        val presets = BrushPreset.libraryBuiltIns.filter { it.stamp != null } + BrushPreset.Blank
        presets.forEach { assertLiveStrokeVisible(it) }
    }

    private fun assertLiveStrokeVisible(preset: BrushPreset) {
        org.robolectric.shadows.ShadowBuild.setFingerprint("robolectric")
        val surface = DrawingSurface(org.robolectric.RuntimeEnvironment.getApplication()).apply {
            val exact = android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY)
            measure(exact, exact)
            layout(0, 0, 800, 800)
            configureBlank(512, 512)
            settings.brush = preset
            settings.sizePx = 32f
        }
        // Exercise device stroke routing after setup, which prewarms native Ink on hardware.
        org.robolectric.shadows.ShadowBuild.setFingerprint("test-device")
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
        fun frame() = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888).also { raster.draw(Canvas(it)) }
        fun earlyRegion(frame: Bitmap) = Bitmap.createBitmap(frame, 288, 392, 60, 16)
        val blank = earlyRegion(frame())
        event(android.view.MotionEvent.ACTION_DOWN, 280f, 0)
        event(android.view.MotionEvent.ACTION_MOVE, 480f, 10)
        val store = requireNotNull(raster.layerStack.selectedStore())
        val firstTile = store.snapshotTiles().entries.firstOrNull { it.key.x == 0 }
            ?: throw AssertionError("${preset.displayName} lost its starting tile before lift")
        val firstFrame = frame()
        val earlyMark = earlyRegion(firstFrame)
        assertFalse("${preset.displayName} was invisible during the stroke", earlyMark.sameAs(blank))
        event(android.view.MotionEvent.ACTION_MOVE, 550f, 15)
        val wet = store.snapshotTiles()
        assertTrue(firstTile.value.sameAs(wet.getValue(firstTile.key)))
        val laterFrame = frame()
        assertTrue("${preset.displayName} lost its first marks on screen",
            earlyMark.sameAs(earlyRegion(laterFrame)))
        assertFalse(canUndo)
        assertNull(raster.previewStroke)
        event(android.view.MotionEvent.ACTION_UP, 550f, 20)
        val dry = store.snapshotTiles()
        assertTrue(dry.isNotEmpty())
        wet.forEach { (key, bitmap) ->
            val result = dry[key] ?: Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            assertTrue(bitmap.sameAs(result))
        }
        assertTrue("${preset.displayName} changed on lift", laterFrame.sameAs(frame()))
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
