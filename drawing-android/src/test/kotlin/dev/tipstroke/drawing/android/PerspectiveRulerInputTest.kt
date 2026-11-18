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
class PerspectiveRulerInputTest {
    @Test fun togglingRulerKeepsPlacedVanishingPoints() {
        val surface=DrawingSurface(RuntimeEnvironment.getApplication())
        surface.setPerspectiveMode(dev.tipstroke.core.geometry.PerspectiveMode.ONE_POINT)
        surface.settings.perspective=surface.settings.perspective!!.copy(first=Point(-100f,300f))
        surface.setPerspectiveMode(dev.tipstroke.core.geometry.PerspectiveMode.OFF)
        assertNull(surface.settings.perspective)
        surface.setPerspectiveMode(dev.tipstroke.core.geometry.PerspectiveMode.TWO_POINT)
        assertEquals(Point(-100f,300f),surface.settings.perspective!!.first)
    }

    private val points = listOf(Point(20f, 20f), Point(400f, 20f), Point(400f, 400f))
    @Test fun magneticSamplesStayOnChosenGuideAndCommitOnRelease() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
            configureBlank(512, 512)
            layout(0, 0, 800, 800)
            settings.emulateMouseWithTouch = true
            setPerspectiveMode(dev.tipstroke.core.geometry.PerspectiveMode.ONE_POINT)
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
            assertTrue(raster.previewStroke != null)
            assertEquals(0, stack.selectedStore()!!.allocatedTileCount)
        }
        draw()
        send(MotionEvent.ACTION_CANCEL, points[2])
        assertNull(raster.previewStroke)
        assertFalse(stack.selectedStore()!!.history.canUndo())
        draw()
        assertTrue(raster.previewStroke!!.samples.all { kotlin.math.abs(it.position.y-20f)<.01f })
        send(MotionEvent.ACTION_UP, points[2])
        assertNull(raster.previewStroke)
        assertTrue(stack.selectedStore()!!.allocatedTileCount > 0)
        surface.undo()
        assertEquals(0, stack.selectedStore()!!.allocatedTileCount)
    }
}
