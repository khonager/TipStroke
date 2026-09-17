package dev.tipstroke.drawing.android

import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DrawingSurfaceGestureTest {
    @Test fun remainingFingerIsRebasedAfterTwoFingerNavigation() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
            configureBlank(512, 512)
            measure(exactly(1000), exactly(1000))
            layout(0, 0, 1000, 1000)
        }
        val raster = surface.getChildAt(0) as RasterCanvasView
        val downTime = 1_000L

        surface.dispatchTouchEvent(event(downTime, downTime, MotionEvent.ACTION_DOWN, listOf(0 to (200f to 200f))))
        surface.dispatchTouchEvent(event(
            downTime, downTime + 10,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(0 to (200f to 200f), 1 to (400f to 200f)),
        ))
        surface.dispatchTouchEvent(event(
            downTime, downTime + 20, MotionEvent.ACTION_MOVE,
            listOf(0 to (210f to 210f), 1 to (460f to 210f)),
        ))
        val afterPinch = raster.transform
        assertNotEquals(1f, afterPinch.scale)

        surface.dispatchTouchEvent(event(
            downTime, downTime + 30,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(0 to (210f to 210f), 1 to (460f to 210f)),
        ))
        surface.dispatchTouchEvent(event(
            downTime, downTime + 40, MotionEvent.ACTION_MOVE,
            listOf(0 to (212f to 210f)),
        ))

        assertEquals(afterPinch.panX, raster.transform.panX, .001f)
        assertEquals(afterPinch.panY, raster.transform.panY, .001f)
    }

    @Test fun fitZoomPercentagePublishesTheActualScale() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
            measure(exactly(1000), exactly(800))
            layout(0, 0, 1000, 800)
            configureBlank(2000, 1000)
        }
        var reportedZoom = 0f
        surface.diagnosticsListener = { reportedZoom = it.zoom }

        surface.resetView()

        val actualZoom = (surface.getChildAt(0) as RasterCanvasView).transform.scale
        assertEquals(actualZoom, reportedZoom, .0001f)
        assertNotEquals(1f, reportedZoom)
    }

    private fun event(
        downTime: Long,
        eventTime: Long,
        action: Int,
        pointers: List<Pair<Int, Pair<Float, Float>>>,
    ): MotionEvent {
        val properties = pointers.map { (id, _) ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coordinates = pointers.map { (_, point) ->
            MotionEvent.PointerCoords().apply {
                x = point.first
                y = point.second
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        return MotionEvent.obtain(
            downTime, eventTime, action, pointers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, 0, 0,
        )
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
