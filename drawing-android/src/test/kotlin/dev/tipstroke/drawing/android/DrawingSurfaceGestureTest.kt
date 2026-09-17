package dev.tipstroke.drawing.android

import android.view.MotionEvent
import android.view.View
import dev.tipstroke.core.drawing.PointerKind
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DrawingSurfaceGestureTest {
    @Test fun pencilInkSamplesAreSafeForNativeBatchValidation() {
        val samples = listOf(
            StrokeSample(0, Point(10f, 20f), Float.NaN, Float.NaN, Float.NaN, 0L, 0, PointerKind.STYLUS),
            StrokeSample(0, Point(11f, 21f), 2f, 2f, 8f, 0L, 0, PointerKind.STYLUS),
            StrokeSample(0, Point(Float.NaN, 22f), 1f, 0f, 0f, 0L, 0, PointerKind.STYLUS),
        )

        val normalized = normalizedPencilInkSamples(samples)

        assertEquals(2, normalized.size)
        assertEquals(0L, normalized[0].elapsedNanos)
        assertEquals(1_000_000L, normalized[1].elapsedNanos)
        assertEquals(.5f, normalized[0].pressure, .001f)
        assertEquals(1f, normalized[1].pressure, .001f)
        assertEquals((Math.PI / 2).toFloat(), normalized[1].tiltRadians, .001f)
        assertTrue(normalized.all { it.orientationRadians.isFinite() })
    }

    @Test fun galleryOrientationSnapsToTheNearestQuarterTurn() {
        assertEquals(0, galleryQuarterTurns(44f))
        assertEquals(1, galleryQuarterTurns(46f))
        assertEquals(2, galleryQuarterTurns(181f))
        assertEquals(3, galleryQuarterTurns(-100f))
        assertEquals(0, galleryQuarterTurns(359f))
    }

    @Test fun pinchKeepsTheDocumentPointUnderItsCentroidFixed() {
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
        val anchorBefore = raster.screenToDocument(300f, 200f)
        val scaleBefore = raster.transform.scale

        surface.dispatchTouchEvent(event(
            downTime, downTime + 20, MotionEvent.ACTION_MOVE,
            listOf(0 to (140f to 240f), 1 to (540f to 240f)),
        ))

        val anchorAfter = raster.screenToDocument(340f, 240f)
        assertEquals(anchorBefore.x, anchorAfter.x, .001f)
        assertEquals(anchorBefore.y, anchorAfter.y, .001f)
        assertEquals(scaleBefore * 2f, raster.transform.scale, .001f)
    }

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
