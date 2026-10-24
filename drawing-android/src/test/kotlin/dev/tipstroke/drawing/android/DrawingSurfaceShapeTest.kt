package dev.tipstroke.drawing.android

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.tipstroke.core.model.BrushPreset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawingSurfaceShapeTest {
    private fun surface() = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
        layout(0, 0, 800, 800)
        configureBlank(512, 512)
        settings.brush = BrushPreset.Airbrush
        settings.sizePx = 8f
    }

    private fun send(surface: DrawingSurface, action: Int, x: Float, y: Float, finger: Boolean = false) {
        val properties = arrayListOf(MotionEvent.PointerProperties().apply { id = 4; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayListOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
        if (finger) {
            properties += MotionEvent.PointerProperties().apply { id = 9; toolType = MotionEvent.TOOL_TYPE_FINGER }
            coords += MotionEvent.PointerCoords().apply { this.x = 650f; this.y = 650f; pressure = 1f }
        }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, properties.size, properties.toTypedArray(), coords.toTypedArray(), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { surface.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun holdSnapsPreviewAndLiftCommitsOneUndoStep() {
        val surface = surface()
        val raster = surface.getChildAt(0) as RasterCanvasView
        var undo = false
        surface.historyListener = { canUndo, _ -> undo = canUndo }
        send(surface, MotionEvent.ACTION_DOWN, 250f, 350f)
        send(surface, MotionEvent.ACTION_MOVE, 350f, 353f)
        send(surface, MotionEvent.ACTION_MOVE, 450f, 350f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        val snapped = requireNotNull(raster.previewStroke)
        assertTrue(snapped.samples.size > 3)
        assertEquals(1, snapped.samples.map { it.position.y }.distinct().size)
        assertFalse(undo)
        send(surface, MotionEvent.ACTION_UP, 450f, 350f)
        assertNull(raster.previewStroke)
        assertTrue(undo)
        surface.undo()
        assertFalse(undo)
    }

    @Test fun eraserShapeRollsBackLiveTilesOnCancellation() {
        for (brush in listOf(BrushPreset.Ink, BrushPreset.Pencil, BrushPreset.Airbrush)) {
            val surface = surface().apply { settings.brush = brush; settings.erasing = true }
            val raster = surface.getChildAt(0) as RasterCanvasView
            val store = raster.layerStack.selectedRaster()!!.tiles
            val tiles = (0..1).flatMap { x -> (0..1).map { y ->
                dev.tipstroke.core.geometry.TileCoordinate(x, y) to
                    android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.RED)
                    }
            } }.toMap()
            store.replaceTiles(tiles)
            val center = raster.screenToDocument(350f, 350f)
            send(surface, MotionEvent.ACTION_DOWN, 250f, 350f)
            send(surface, MotionEvent.ACTION_MOVE, 350f, 353f)
            send(surface, MotionEvent.ACTION_MOVE, 450f, 350f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            assertTrue(android.graphics.Color.alpha(store.colorAt(center.x.toInt(), center.y.toInt())) < 255)
            send(surface, MotionEvent.ACTION_CANCEL, 450f, 350f)
            assertEquals(android.graphics.Color.RED, store.colorAt(center.x.toInt(), center.y.toInt()))
            assertFalse(store.history.canUndo())
        }
    }

    @Test fun liftBeforeHoldDoesNotSnapTheNextStroke() {
        val surface = surface()
        val raster = surface.getChildAt(0) as RasterCanvasView
        send(surface, MotionEvent.ACTION_DOWN, 250f, 350f)
        send(surface, MotionEvent.ACTION_MOVE, 350f, 353f)
        send(surface, MotionEvent.ACTION_MOVE, 450f, 350f)
        send(surface, MotionEvent.ACTION_UP, 450f, 350f)
        send(surface, MotionEvent.ACTION_DOWN, 300f, 450f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertEquals(1, requireNotNull(raster.previewStroke).samples.size)
        send(surface, MotionEvent.ACTION_CANCEL, 300f, 450f)
    }

    @Test fun cancellationDiscardsSnappedPreview() {
        val surface = surface()
        var undo = false
        surface.historyListener = { canUndo, _ -> undo = canUndo }
        send(surface, MotionEvent.ACTION_DOWN, 250f, 350f)
        send(surface, MotionEvent.ACTION_MOVE, 350f, 353f)
        send(surface, MotionEvent.ACTION_MOVE, 450f, 350f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        send(surface, MotionEvent.ACTION_CANCEL, 450f, 350f)
        assertNull((surface.getChildAt(0) as RasterCanvasView).previewStroke)
        assertFalse(undo)
    }

    @Test fun liftingModifierFingerDoesNotFinishStylusStroke() {
        val surface = surface()
        val raster = surface.getChildAt(0) as RasterCanvasView
        send(surface, MotionEvent.ACTION_DOWN, 250f, 350f)
        send(surface, MotionEvent.ACTION_MOVE, 350f, 353f)
        send(surface, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 350f, 353f, true)
        send(surface, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 350f, 353f, true)
        assertNotNull(raster.previewStroke)
        send(surface, MotionEvent.ACTION_UP, 450f, 350f)
        assertNull(raster.previewStroke)
    }

    @Test fun selectionConstraintTracksFingerAndCommitsWhenStylusLiftsFirst() {
        for (tool in listOf(SelectionTool.RECTANGLE, SelectionTool.ELLIPSE)) {
            val surface = surface()
            val raster = surface.getChildAt(0) as RasterCanvasView
            surface.setSelectionTool(tool)
            send(surface, MotionEvent.ACTION_DOWN, 250f, 300f)
            send(surface, MotionEvent.ACTION_MOVE, 450f, 400f)
            val free = requireNotNull(raster.selectionRegion).bounds
            assertTrue(free.right - free.left > free.bottom - free.top)
            send(surface, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 450f, 400f, true)
            val square = requireNotNull(raster.selectionRegion).bounds
            assertEquals(square.right - square.left, square.bottom - square.top, .01f)
            send(surface, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 450f, 400f, true)
            val released = requireNotNull(raster.selectionRegion).bounds
            assertTrue(released.right - released.left > released.bottom - released.top)
            send(surface, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 450f, 400f, true)
            send(surface, MotionEvent.ACTION_POINTER_UP, 450f, 400f, true)
            val committed = requireNotNull(raster.selectionRegion).bounds
            assertEquals(committed.right - committed.left, committed.bottom - committed.top, .01f)
            assertTrue(raster.selectionDraftPoints.isEmpty())
        }
    }
}
