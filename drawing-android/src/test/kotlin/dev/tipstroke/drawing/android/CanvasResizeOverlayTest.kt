package dev.tipstroke.drawing.android

import android.view.MotionEvent
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
class CanvasResizeOverlayTest {
    @Test fun draggingLeftHandlePreviewsAndAppliesWithoutChangingPixelsDuringPreview() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
            configureBlank(300, 200)
            layout(0, 0, 1000, 800)
        }
        var preview = 300 to 200
        surface.beginCanvasResize { width, height -> preview = width to height }
        val raster = surface.getChildAt(0) as RasterCanvasView
        val point = floatArrayOf(0f, 100f).also(raster.transformMatrix::mapPoints)
        fun send(action: Int, x: Float) {
            MotionEvent.obtain(0, 10, action, x, point[1], 0).also {
                surface.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, point[0])
        send(MotionEvent.ACTION_MOVE, point[0] - 70f)
        send(MotionEvent.ACTION_UP, point[0] - 70f)
        assertTrue(preview.first > 300)
        assertEquals(300, surface.canvasWidthPx)
        assertTrue(surface.hasCanvasResizeChanges)
        assertTrue(surface.applyCanvasResize())
        assertEquals(preview.first, surface.canvasWidthPx)
        assertEquals(200, surface.canvasHeightPx)
    }

    @Test fun cancelLeavesDocumentUnchanged() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
            configureBlank(300, 200)
            layout(0, 0, 1000, 800)
        }
        var preview = 300 to 200
        surface.beginCanvasResize { width, height -> preview = width to height }
        val raster = surface.getChildAt(0) as RasterCanvasView
        val point = floatArrayOf(300f, 100f).also(raster.transformMatrix::mapPoints)
        MotionEvent.obtain(0, 10, MotionEvent.ACTION_DOWN, point[0], point[1], 0).also {
            surface.dispatchTouchEvent(it); it.recycle()
        }
        MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, point[0] - 70f, point[1], 0).also {
            surface.dispatchTouchEvent(it); it.recycle()
        }
        MotionEvent.obtain(0, 30, MotionEvent.ACTION_UP, point[0] - 70f, point[1], 0).also {
            surface.dispatchTouchEvent(it); it.recycle()
        }
        assertTrue(preview.first < 300)
        assertEquals(300, surface.canvasWidthPx)
        surface.resetCanvasResizePreview()
        assertEquals(300 to 200, preview)
        surface.cancelCanvasResize()
        assertEquals(300, surface.canvasWidthPx)
        assertEquals(200, surface.canvasHeightPx)
        assertFalse(surface.hasCanvasResizeChanges)
    }
}
