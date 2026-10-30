package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import dev.tipstroke.core.geometry.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionTransformTest {
    private fun store(): TileStore = TileStore(8192, 8192).apply {
        val first = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(254, 40, Color.RED) }
        val second = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            setPixel(1, 43, Color.BLUE); setPixel(14, 40, Color.GREEN)
        }
        replaceTiles(mapOf(TileCoordinate(0, 0) to first, TileCoordinate(1, 0) to second))
    }
    private val selection = SelectionRegion.rectangle(Rect(254f, 40f, 258f, 44f))

    @Test fun rotateResizeAndBothFlipsPreserveUnselectedPixelsAndUndo() {
        val transforms = listOf(
            SelectionTransform(Point(256f, 42f), rotationDegrees = 90f),
            SelectionTransform(Point(256f, 42f), scaleX = -1f),
            SelectionTransform(Point(256f, 42f), scaleY = -1f),
            SelectionTransform(Point(256f, 42f), scaleX = 2f, scaleY = 2f),
        )
        transforms.forEach { transform ->
            val store = store()
            val sibling = store.duplicate()
            store.transformSelection(selection, transform, filtered = false)
            val red = transform.map(Point(254.5f, 40.5f))
            val blue = transform.map(Point(257.5f, 43.5f))
            assertEquals("Transform $transform", Color.RED, store.colorAt(floor(red.x).toInt(), floor(red.y).toInt()))
            assertEquals(Color.BLUE, store.colorAt(floor(blue.x).toInt(), floor(blue.y).toInt()))
            assertEquals(Color.GREEN, store.colorAt(270, 40))
            assertEquals(Color.RED, sibling.colorAt(254, 40))
            assertTrue(store.allocatedTileCount <= 4)
            assertTrue(store.history.undo())
            assertEquals(Color.RED, store.colorAt(254, 40))
            assertEquals(Color.BLUE, store.colorAt(257, 43))
            assertTrue(store.history.redo())
            assertEquals("Transform $transform", Color.RED, store.colorAt(floor(red.x).toInt(), floor(red.y).toInt()))
            store.discard(); sibling.discard()
        }
    }

    @Test fun previewCancelAndApplyKeepOneUndoStepAcrossSelectedLayers() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication())
        surface.layout(0, 0, 512, 512); surface.configureBlank(512, 512)
        val view = surface.getChildAt(0) as RasterCanvasView
        val stack = view.layerStack
        val first = stack.selectedRaster()!!
        first.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(20, 20, Color.RED) }))
        surface.addPaintLayer()
        val second = stack.selectedRaster()!!
        second.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(30, 20, Color.BLUE) }))
        surface.toggleLayerSelection(first.id)
        surface.selectAll()
        assertTrue(surface.beginSelectionTransform())
        surface.previewSelectionTransform(-1f, 1f, 0f, false)
        assertNotNull(view.selectionPreviewMatrix)
        assertEquals(Color.RED, first.tiles.colorAt(20, 20))
        surface.cancelSelectionTransform()
        assertNull(view.selectionPreviewMatrix)
        assertEquals(Color.BLUE, second.tiles.colorAt(30, 20))
        assertTrue(surface.beginSelectionTransform())
        surface.previewSelectionTransform(-1f, 1f, 0f, false)
        assertTrue(surface.applySelectionTransform())
        assertEquals(Color.RED, first.tiles.colorAt(491, 20))
        assertEquals(Color.BLUE, second.tiles.colorAt(481, 20))
        surface.undo()
        assertEquals(Color.RED, first.tiles.colorAt(20, 20))
        assertEquals(Color.BLUE, second.tiles.colorAt(30, 20))
        surface.redo()
        assertEquals(Color.BLUE, second.tiles.colorAt(481, 20))
    }
}
