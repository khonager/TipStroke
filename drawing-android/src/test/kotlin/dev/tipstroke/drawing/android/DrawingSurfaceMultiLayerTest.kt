package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.BrushPreset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawingSurfaceMultiLayerTest {
    private fun surface() = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
        configureBlank(64, 64)
        val spec = View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY)
        measure(spec, spec); layout(0, 0, 64, 64)
        (getChildAt(0) as RasterCanvasView).updateTransform(32f, 32f, 1f, 0f)
        settings.emulateMouseWithTouch = true
        settings.brush = BrushPreset.Airbrush
        settings.eraserHardness = 1f
        settings.sizePx = 12f
    }

    private fun paint(layer: RasterLayerRuntime, x: Int, y: Int, color: Int) {
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        tile.setPixel(x, y, color)
        layer.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to tile))
    }

    private fun touch(surface: DrawingSurface, action: Int, x: Float, y: Float) {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f }
        MotionEvent.obtain(1_000, 1_020, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also {
            surface.dispatchTouchEvent(it); it.recycle()
        }
    }

    @Test fun groupMovesLiveAcrossLayersThenCommitsAndUndoesAsOneEdit() {
        val surface = surface()
        val raster = surface.getChildAt(0) as RasterCanvasView
        val stack = raster.layerStack
        val first = stack.selectedRaster()!!
        paint(first, 16, 16, Color.RED)
        val secondId = stack.addRaster()
        val second = stack.selectedRaster()!!
        paint(second, 16, 24, Color.BLUE)
        stack.group(secondId, first.id)
        surface.selectLayerGroup(first.groupId!!)
        assertEquals(setOf(first.id, second.id), stack.selectedLayerIds())
        surface.setSelectionMoveMode(true) // With no marquee, Move selects the whole canvas.
        touch(surface, MotionEvent.ACTION_DOWN, 16f, 16f)
        touch(surface, MotionEvent.ACTION_MOVE, 26f, 16f)

        val preview = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        raster.draw(Canvas(preview))
        assertEquals(Color.RED, preview.getPixel(26, 16))
        assertEquals(Color.BLUE, preview.getPixel(26, 24))
        assertEquals(Color.WHITE, preview.getPixel(16, 24))
        assertEquals(Color.RED, first.tiles.colorAt(16, 16)) // Preview has not mutated tiles.
        assertEquals(Color.TRANSPARENT, first.tiles.colorAt(26, 16))
        preview.recycle()

        touch(surface, MotionEvent.ACTION_UP, 26f, 16f)
        assertEquals(Color.RED, first.tiles.colorAt(26, 16))
        assertEquals(Color.BLUE, second.tiles.colorAt(26, 24))
        surface.undo()
        assertEquals(Color.RED, first.tiles.colorAt(16, 16))
        assertEquals(Color.BLUE, second.tiles.colorAt(16, 24))
        surface.redo()
        assertEquals(Color.RED, first.tiles.colorAt(26, 16))
        assertEquals(Color.BLUE, second.tiles.colorAt(26, 24))
    }

    @Test fun cancelledMoveDiscardsOnlyPreview() {
        val surface = surface()
        val raster = surface.getChildAt(0) as RasterCanvasView
        val layer = raster.layerStack.selectedRaster()!!
        paint(layer, 16, 16, Color.RED)
        surface.setSelectionMoveMode(true)
        touch(surface, MotionEvent.ACTION_DOWN, 16f, 16f)
        touch(surface, MotionEvent.ACTION_MOVE, 26f, 16f)
        touch(surface, MotionEvent.ACTION_CANCEL, 26f, 16f)
        assertTrue(raster.movingLayerIds.isEmpty())
        assertEquals(Color.RED, layer.tiles.colorAt(16, 16))
        assertEquals(Color.TRANSPARENT, layer.tiles.colorAt(26, 16))
        assertFalse(layer.tiles.history.canUndo())
    }

    @Test fun multiEraseIsLiveCancellableAndUndoableWithoutTouchingOtherLayers() {
        val surface = surface()
        val stack = (surface.getChildAt(0) as RasterCanvasView).layerStack
        val first = stack.selectedRaster()!!
        stack.addRaster()
        val second = stack.selectedRaster()!!
        stack.addRaster()
        val untouched = stack.selectedRaster()!!
        listOf(first, second, untouched).forEach { paint(it, 16, 16, Color.RED) }
        stack.select(second.id)
        stack.toggleAdditionalSelection(first.id)
        surface.settings.erasing = true

        touch(surface, MotionEvent.ACTION_DOWN, 16f, 16f)
        assertEquals(0, Color.alpha(first.tiles.colorAt(16, 16)))
        assertEquals(0, Color.alpha(second.tiles.colorAt(16, 16)))
        assertEquals(Color.RED, untouched.tiles.colorAt(16, 16))
        touch(surface, MotionEvent.ACTION_CANCEL, 16f, 16f)
        assertEquals(Color.RED, first.tiles.colorAt(16, 16))
        assertEquals(Color.RED, second.tiles.colorAt(16, 16))

        touch(surface, MotionEvent.ACTION_DOWN, 16f, 16f)
        touch(surface, MotionEvent.ACTION_UP, 16f, 16f)
        surface.undo()
        assertEquals(Color.RED, first.tiles.colorAt(16, 16))
        assertEquals(Color.RED, second.tiles.colorAt(16, 16))
        surface.redo()
        assertEquals(0, Color.alpha(first.tiles.colorAt(16, 16)))
        assertEquals(0, Color.alpha(second.tiles.colorAt(16, 16)))
        assertEquals(Color.RED, untouched.tiles.colorAt(16, 16))
    }

    @Test fun mixedGroupEraseTransformsImageMaskAndPreservesSource() {
        val surface = surface()
        val stack = (surface.getChildAt(0) as RasterCanvasView).layerStack
        val paint = stack.selectedRaster()!!
        paint(paint, 32, 32, Color.RED)
        val source = File.createTempFile("group-image", ".png", RuntimeEnvironment.getApplication().cacheDir)
        try {
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.BLUE)
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            surface.addImage(Uri.fromFile(source)).getOrThrow()
            val image = stack.selectedImage()!!
            stack.toggleAdditionalSelection(paint.id)
            surface.settings.erasing = true
            touch(surface, MotionEvent.ACTION_DOWN, 32f, 32f)
            touch(surface, MotionEvent.ACTION_UP, 32f, 32f)
            assertEquals(0, Color.alpha(paint.tiles.colorAt(32, 32)))
            assertTrue(Color.alpha(image.mask.colorAt(8, 8)) > 0)
            surface.undo()
            assertEquals(Color.RED, paint.tiles.colorAt(32, 32))
            assertEquals(0, Color.alpha(image.mask.colorAt(8, 8)))
            assertEquals(Color.BLUE, android.graphics.BitmapFactory.decodeFile(source.path).getPixel(8, 8))
        } finally { source.delete() }
    }
}
