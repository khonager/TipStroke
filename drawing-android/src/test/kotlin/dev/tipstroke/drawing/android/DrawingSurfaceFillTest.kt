package dev.tipstroke.drawing.android

import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import dev.tipstroke.core.drawing.PixelTool
import dev.tipstroke.core.model.FingerAction
import dev.tipstroke.core.model.GestureSettings
import dev.tipstroke.core.model.RgbaColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawingSurfaceFillTest {
    private fun surface() = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
        configureBlank(64, 64)
        val spec = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        measure(spec, spec); layout(0, 0, 600, 600)
        settings.emulateMouseWithTouch = false
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertTrue("Worker did not finish", predicate())
    }

    private fun drag(surface: DrawingSurface, fromX: Float, toX: Float) {
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to fromX, MotionEvent.ACTION_MOVE to toX, MotionEvent.ACTION_UP to toX)
            .forEachIndexed { i, (action, x) ->
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coords = MotionEvent.PointerCoords().apply { this.x = x; y = 300f; pressure = 1f; size = 1f }
                val event = MotionEvent.obtain(now, now + i * 16L, action, 1, arrayOf(properties), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                surface.dispatchTouchEvent(event); event.recycle()
            }
    }

    @Test fun dropHasImmediateFeedbackAndHonorsConfiguredDuration() {
        val surface = surface()
        surface.settings.gestures = GestureSettings(fillAnimationMillis = 250L)
        val raster = surface.getChildAt(0) as RasterCanvasView
        val store = raster.layerStack.selectedRaster()!!.tiles
        surface.dropColor(300f, 300f, RgbaColor(1f, 0f, 0f))
        assertNotNull(raster.fillFeedback) // No looper/worker wait before feedback.
        waitUntil { store.fillReveal != null }
        assertNull(raster.fillFeedback)
        shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)
        assertNull(store.fillReveal)
        assertEquals(Color.RED, store.colorAt(32, 32))
    }

    private fun finishHoverWork() {
        shadowOf(Looper.getMainLooper()).idleFor(130L, TimeUnit.MILLISECONDS)
        val barrier = FillPreparation.executor.submit { }
        waitUntil { barrier.isDone }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun stableHoverPreparesWithoutPaintingAndDropUsesReadyResultImmediately() {
        val surface = surface()
        val raster = surface.getChildAt(0) as RasterCanvasView
        val store = raster.layerStack.selectedRaster()!!.tiles
        surface.hoverFillColor(300f, 300f, RgbaColor(1f, 0f, 0f))
        finishHoverWork()
        assertFalse(store.history.canUndo())
        assertNull(raster.fillFeedback)
        surface.dropColor(300f, 300f, RgbaColor(1f, 0f, 0f))
        assertNotNull(store.fillReveal) // Ready result accepted in this call.
        assertEquals(Color.RED, store.colorAt(32, 32))
        surface.cancelFill()
    }

    @Test fun nearbyDropAcrossClosedLineDoesNotReuseWrongHoveredRegion() {
        val surface = surface()
        val raster = surface.getChildAt(0) as RasterCanvasView
        val store = raster.layerStack.selectedRaster()!!.tiles
        for (y in 0 until 64) store.putFillPixel(32, y, Color.BLACK)
        surface.hoverFillColor(299f, 300f, RgbaColor(1f, 0f, 0f))
        finishHoverWork()
        surface.dropColor(309f, 300f, RgbaColor(1f, 0f, 0f))
        waitUntil { store.fillReveal != null }
        assertEquals(Color.TRANSPARENT, store.colorAt(31, 32))
        assertEquals(Color.BLACK, store.colorAt(32, 32))
        assertEquals(Color.RED, store.colorAt(33, 32))
        surface.cancelFill()
    }

    @Test fun cancellingOrMovingAwayFromHoverNeverCommitsIt() {
        val surface = surface()
        val store = (surface.getChildAt(0) as RasterCanvasView).layerStack.selectedRaster()!!.tiles
        surface.hoverFillColor(300f, 300f, RgbaColor(1f, 0f, 0f))
        finishHoverWork()
        surface.hoverFillColor(400f, 400f, RgbaColor(1f, 0f, 0f))
        surface.cancelFillHover()
        val barrier = FillPreparation.executor.submit { }
        waitUntil { barrier.isDone }
        assertFalse(store.history.canUndo())
        assertEquals(Color.TRANSPARENT, store.colorAt(32, 32))
    }

    @Test fun panAndDrawingRemainAvailableForTheEntireTimedReveal() {
        val surface = surface()
        val raster = surface.getChildAt(0) as RasterCanvasView
        val store = raster.layerStack.selectedRaster()!!.tiles
        surface.dropColor(300f, 300f, RgbaColor(1f, 0f, 0f))
        waitUntil { store.fillReveal != null }
        surface.settings.gestures = GestureSettings(oneFingerDrag = FingerAction.NAVIGATE)
        val previous = raster.transform.panX
        drag(surface, 300f, 340f)
        assertNotEquals(previous, raster.transform.panX)
        assertNotNull(store.fillReveal)
        surface.settings.gestures = GestureSettings(oneFingerDrag = FingerAction.DRAW)
        surface.settings.pixelTool = PixelTool.PENCIL
        surface.settings.color = RgbaColor(0f, 0f, 1f)
        drag(surface, 300f, 320f)
        assertNotNull(store.fillReveal)
        val point = raster.screenToDocument(310f, 300f)
        assertEquals(Color.BLUE, store.colorAt(point.x.toInt(), point.y.toInt()))
        shadowOf(Looper.getMainLooper()).idleFor(2600, TimeUnit.MILLISECONDS)
        assertNull(store.fillReveal)
        assertEquals(Color.BLUE, store.colorAt(point.x.toInt(), point.y.toInt()))
        surface.undo()
        assertEquals(Color.RED, store.colorAt(point.x.toInt(), point.y.toInt()))
        surface.undo()
        assertEquals(Color.TRANSPARENT, store.colorAt(point.x.toInt(), point.y.toInt()))
    }

    @Test fun editsDuringPreparationAreIncludedWithoutBeingOverwritten() {
        val surface = surface()
        val raster = surface.getChildAt(0) as RasterCanvasView
        val store = raster.layerStack.selectedRaster()!!.tiles
        surface.dropColor(300f, 300f, RgbaColor(1f, 0f, 0f))
        // The main looper has not serviced the worker's first tile request yet.
        for (y in 0 until 64) store.putFillPixel(40, y, Color.BLACK)
        waitUntil { store.fillReveal != null }
        assertEquals(Color.RED, store.colorAt(32, 32))
        assertEquals(Color.BLACK, store.colorAt(40, 32))
        assertEquals(Color.TRANSPARENT, store.colorAt(41, 32))
        surface.cancelFill()
    }

    @Test fun workerSourceCacheEvictsAndReloadsInsteadOfRejectingLargeRegions() {
        val store = TileStore(17 * 256, 9 * 256)
        for (index in 0 until 130) store.putFillPixel((index % 17) * 256, (index / 17) * 256, Color.RED)
        val snapshot = store.fillSnapshot(android.os.Handler(Looper.getMainLooper()))
        val task = FillPreparation.executor.submit {
            for (index in 0 until 130) {
                assertEquals(Color.RED, snapshot.colorAt((index % 17) * 256, (index / 17) * 256))
                assertTrue(snapshot.allocatedTileBytes <= 32L * 1024 * 1024)
            }
            // The first tile was evicted; asking for it again must obtain its pixels.
            assertEquals(Color.RED, snapshot.colorAt(0, 0))
        }
        waitUntil { task.isDone }
        task.get()
        snapshot.discard()
        store.discard()
    }

    @Test fun cancellingPreparationDoesNotCommitLater() {
        val surface = surface()
        val store = (surface.getChildAt(0) as RasterCanvasView).layerStack.selectedRaster()!!.tiles
        surface.dropColor(300f, 300f, RgbaColor(1f, 0f, 0f))
        surface.cancelFill()
        val barrier = FillPreparation.executor.submit { }
        waitUntil { barrier.isDone }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(store.history.canUndo())
        assertEquals(Color.TRANSPARENT, store.colorAt(32, 32))
    }
}
