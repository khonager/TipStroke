package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LassoFillTest {
    private val points = listOf(Point(20f, 20f), Point(400f, 20f), Point(400f, 400f))
    private fun stroke(points: List<Point> = this.points) = CompletedStroke(
        points.mapIndexed { i, p -> StrokeSample(0, p, .1f, 0f, 0f, i * 10_000_000L, 0, PointerKind.STYLUS) },
        StrokeStyle(BrushPreset.Blank, 300f, .5f, RgbaColor(1f, 0f, 0f), BlendBehavior.PAINT, lassoFill = true),
    )
    private fun render(stroke: CompletedStroke) = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also {
        StrokeCanvasPainter.draw(Canvas(it), stroke)
    }

    @Test fun closingEdgeFollowsTipAndRetracingRemovesWetFill() {
        val full = render(stroke())
        assertEquals(128, Color.alpha(full.getPixel(300, 100)))
        val retraced = render(stroke(points + points[1]))
        assertEquals(0, Color.alpha(retraced.getPixel(300, 100)))
        assertEquals(0, Color.alpha(full.getPixel(100, 300)))
    }

    @Test fun commitMatchesPreviewAcrossTilesAndRecordsPatchesWithOneUndo() {
        val stroke = stroke()
        val expected = render(stroke)
        val store = TileStore(512, 512)
        var recordedPatch = false
        store.recordingListener = { commands, _ -> recordedPatch = commands == null }
        store.commit(stroke)
        assertTrue(recordedPatch)
        for (y in 0 until 512 step 7) for (x in 0 until 512 step 7) {
            assertEquals("$x,$y", expected.getPixel(x, y), store.colorAt(x, y))
        }
        assertTrue(store.history.undo())
        assertEquals(0, store.allocatedTileCount)
        assertFalse(store.history.canUndo())
        assertTrue(store.history.redo())
        assertEquals(expected.getPixel(300, 100), store.colorAt(300, 100))
    }

    @Test fun selectionClipsFill() {
        val clip = SelectionRegion(listOf(Point(250f, 0f), Point(500f, 0f), Point(500f, 500f), Point(250f, 500f)))
        val result = render(stroke().let { it.copy(style = it.style.copy(selection = clip)) })
        assertEquals(0, Color.alpha(result.getPixel(200, 100)))
        assertEquals(128, Color.alpha(result.getPixel(300, 100)))
    }

    @Test fun nativeInputKeepsTilesUntouchedUntilReleaseAndCancelDiscardsPreview() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
            configureBlank(512, 512)
            layout(0, 0, 800, 800)
            settings.emulateMouseWithTouch = true
            settings.lassoFill = true
        }
        val raster = surface.getChildAt(0) as RasterCanvasView
        val stack = raster.layerStack
        var time = 0L
        fun send(action: Int, p: Point) {
            val xy = floatArrayOf(p.x, p.y).also(raster.transformMatrix::mapPoints)
            MotionEvent.obtain(0, time.also { time += 10 }, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }),
                arrayOf(MotionEvent.PointerCoords().apply { x = xy[0]; y = xy[1]; pressure = 1f }),
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0).also {
                surface.dispatchTouchEvent(it); it.recycle()
            }
        }
        fun draw() {
            send(MotionEvent.ACTION_DOWN, points[0])
            send(MotionEvent.ACTION_MOVE, points[1])
            send(MotionEvent.ACTION_MOVE, points[2])
            assertTrue(raster.previewStroke?.style?.lassoFill == true)
            assertEquals(0, stack.selectedStore()!!.allocatedTileCount)
        }
        draw()
        send(MotionEvent.ACTION_CANCEL, points[2])
        assertNull(raster.previewStroke)
        assertFalse(stack.selectedStore()!!.history.canUndo())
        draw()
        send(MotionEvent.ACTION_UP, points[2])
        assertNull(raster.previewStroke)
        assertTrue(Color.alpha(stack.selectedStore()!!.colorAt(300, 100)) > 0)
        surface.undo()
        assertEquals(0, stack.selectedStore()!!.allocatedTileCount)
    }
}
