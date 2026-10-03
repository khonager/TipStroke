package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.RecordingTiming
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimationInteractionTest {
    @Test fun liveRecordingPlaysAndAddsFramesUntilStopped() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication())
        surface.layout(0, 0, 600, 600)
        surface.configureBlank(256, 256)
        var latest: AnimationUiState? = null
        surface.animationListener = { latest = it }
        surface.enableAnimation(existingAsBackground = false)
        surface.setAnimationFps(10)

        assertTrue(surface.startLiveDrawingRecording())
        assertTrue(latest!!.playing)
        assertEquals(AnimationRecordingKind.LIVE_DRAWING, latest!!.recording)
        shadowOf(Looper.getMainLooper()).idleFor(260L, TimeUnit.MILLISECONDS)
        assertTrue(latest!!.frames.size >= 3)
        assertTrue(latest!!.selectedIndex >= 2)

        surface.cancelAnimationRecording()
        val count = latest!!.frames.size
        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
        assertEquals(count, latest!!.frames.size)
        assertFalse(latest!!.playing)
        assertNull(latest!!.recording)
    }

    @Test fun selectedPixelsMoveIntoANewLayerOnTheCurrentFrame() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication())
        surface.layout(0, 0, 600, 600)
        surface.configureBlank(512, 512)
        val view = surface.getChildAt(0) as RasterCanvasView
        val original = view.layerStack.selectedRaster()!!
        original.tiles.replaceTiles(mapOf(
            TileCoordinate(0, 0) to Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                setPixel(80, 90, Color.BLUE)
            },
        ))
        surface.enableAnimation(existingAsBackground = false)
        surface.selectAll()
        surface.moveSelectionToNewLayer()

        assertNotEquals(original.id, view.layerStack.selectedRaster()!!.id)
        assertEquals(0, Color.alpha(original.tiles.colorAt(80, 90)))
        assertEquals(Color.BLUE, view.layerStack.selectedRaster()!!.tiles.colorAt(80, 90))
    }

    @Test fun lassoSelectionCanMoveSparsePixelsWithoutCrashing() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication())
        surface.layout(0, 0, 600, 600)
        surface.configureBlank(512, 512)
        val view = surface.getChildAt(0) as RasterCanvasView
        view.layerStack.selectedRaster()!!.tiles.replaceTiles(mapOf(
            TileCoordinate(1, 1) to Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                setPixel(24, 24, Color.BLUE)
            },
        ))
        fun screen(x: Float, y: Float) = floatArrayOf(x, y).also(view.transformMatrix::mapPoints)
        val corners = listOf(screen(250f, 250f), screen(320f, 250f), screen(320f, 320f), screen(250f, 320f))
        val time = SystemClock.uptimeMillis()
        surface.setSelectionTool(SelectionTool.LASSO)
        surface.dispatchTouchEvent(touch(time, time, MotionEvent.ACTION_DOWN, corners[0][0], corners[0][1]))
        corners.drop(1).forEachIndexed { index, point ->
            surface.dispatchTouchEvent(touch(time, time + (index + 1) * 16L, MotionEvent.ACTION_MOVE, point[0], point[1]))
        }
        surface.dispatchTouchEvent(touch(time, time + 64L, MotionEvent.ACTION_UP, corners[0][0], corners[0][1]))
        surface.setSelectionMoveMode(true)
        val start = screen(280f, 280f)
        surface.dispatchTouchEvent(touch(time + 100, time + 100, MotionEvent.ACTION_DOWN, start[0], start[1]))
        surface.dispatchTouchEvent(touch(time + 100, time + 116, MotionEvent.ACTION_MOVE, start[0] + 40f, start[1]))
        surface.dispatchTouchEvent(touch(time + 100, time + 132, MotionEvent.ACTION_UP, start[0] + 40f, start[1]))

        val movedX = view.screenToDocument(start[0] + 40f, start[1]).x.toInt()
        assertEquals(0, Color.alpha(view.layerStack.selectedRaster()!!.tiles.colorAt(280, 280)))
        assertNotEquals(0, Color.alpha(view.layerStack.selectedRaster()!!.tiles.colorAt(movedX, 280)))
    }

    @Test fun recordingBackgroundImageMovementCreatesDistinctFrameTransforms() {
        val context = RuntimeEnvironment.getApplication()
        val source = java.io.File.createTempFile("tipstroke-motion", ".png")
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.BLUE)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            val surface = DrawingSurface(context)
            surface.layout(0, 0, 600, 600)
            surface.configureBlank(512, 512)
            surface.addImage(Uri.fromFile(source)).getOrThrow()
            val imageId = (surface.getChildAt(0) as RasterCanvasView).layerStack.selectedImage()!!.id
            var latest: AnimationUiState? = null
            surface.animationListener = { latest = it }
            surface.enableAnimation(existingAsBackground = true)
            surface.addAnimationFrame()
            surface.addAnimationFrame()
            surface.selectAnimationFrame(0)
            assertTrue(surface.armImageMotionRecording(2, RecordingTiming.FIT_RANGE))
            assertFalse(imageId in latest!!.backgroundLayerIds)
            val time = SystemClock.uptimeMillis()
            surface.dispatchTouchEvent(touch(time, time, MotionEvent.ACTION_DOWN, 300f, 300f))
            surface.dispatchTouchEvent(touch(time, time + 120, MotionEvent.ACTION_MOVE, 345f, 300f))
            surface.dispatchTouchEvent(touch(time, time + 240, MotionEvent.ACTION_MOVE, 390f, 300f))
            surface.dispatchTouchEvent(touch(time, time + 260, MotionEvent.ACTION_UP, 390f, 300f))
            assertNull(latest!!.recording)
            val view = surface.getChildAt(0) as RasterCanvasView
            surface.selectAnimationFrame(0)
            val firstX = view.layerStack.selectedImage()!!.transform.centerX
            surface.selectAnimationFrame(1)
            val middleX = view.layerStack.selectedImage()!!.transform.centerX
            surface.selectAnimationFrame(2)
            val lastX = view.layerStack.selectedImage()!!.transform.centerX
            assertTrue("Frame positions: $firstX, $middleX, $lastX", firstX < middleX && middleX < lastX)
            surface.selectAnimationFrame(0)
            surface.toggleAnimationPlayback()
            shadowOf(Looper.getMainLooper()).idleFor(220L, TimeUnit.MILLISECONDS)
            assertEquals(2, latest!!.selectedIndex)
            assertEquals(lastX, view.layerStack.selectedImage()!!.transform.centerX, .001f)
            surface.toggleAnimationPlayback()
        } finally { source.delete() }
    }

    private fun touch(down: Long, at: Long, action: Int, x: Float, y: Float) =
        MotionEvent.obtain(down, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
}
