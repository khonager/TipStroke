package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import dev.tipstroke.core.geometry.TileCoordinate
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
class LayerCapacityAndRecallTest {
    @Test fun capacityAccountsForCanvasSizeDeviceAndTileRounding() {
        assertEquals(10, LayerCapacity.maximum(256, 2048, 2048))
        assertEquals(1, LayerCapacity.maximum(256, 8192, 8192))
        assertEquals(64, LayerCapacity.maximum(256, 64, 64))
        assertTrue(LayerCapacity.maximum(512, 2048, 2048) > LayerCapacity.maximum(256, 2048, 2048))
        assertTrue(LayerCapacity.maximum(256, 2049, 2049) < LayerCapacity.maximum(256, 2048, 2048))
    }

    @Test fun fullDocumentRejectsAddsImportsAndCopiesWithoutDeletingPixels() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply { configureBlank(8192, 8192) }
        val raster = surface.getChildAt(0) as RasterCanvasView
        val stack = raster.layerStack
        val initial = stack.selectedRaster()!!
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(1, 1, Color.RED) }
        initial.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to tile))
        surface.selectAll()
        var warning: String? = null
        surface.capacityWarningListener = { warning = it }
        surface.addPaintLayer()
        surface.duplicateSelectedLayers()
        surface.duplicateSelection()
        surface.moveSelectionToNewLayer()
        assertTrue(surface.addImage(Uri.parse("content://unused/photo")).isFailure)
        assertEquals(1, stack.layers.size)
        assertEquals(Color.RED, initial.tiles.colorAt(1, 1))
        assertTrue(warning!!.contains("Layer limit"))
        // Existing oversized documents retain their layers and editing operations.
        stack.addRaster()
        surface.addPaintLayer()
        assertEquals(2, stack.layers.size)
        surface.selectLayer(initial.id)
        surface.deleteSelection()
        assertEquals(Color.TRANSPARENT, initial.tiles.colorAt(1, 1))
        surface.undo()
        assertEquals(Color.RED, initial.tiles.colorAt(1, 1))
    }

    @Test fun recallSurvivesDeselectAndNewLayerButNotNewDocumentOrResize() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply { configureBlank(64, 64) }
        val raster = surface.getChildAt(0) as RasterCanvasView
        assertFalse(surface.restoreSelection())
        surface.selectAll()
        val region = raster.selectionRegion
        surface.clearSelection()
        surface.clearSelection()
        surface.addPaintLayer()
        assertTrue(surface.restoreSelection())
        assertEquals(region, raster.selectionRegion)
        surface.clearSelection()
        surface.configureBlank(128, 128)
        assertFalse(surface.restoreSelection())
        surface.selectAll()
        assertTrue(surface.resizeCanvas(0, 0, 16, 16))
        assertFalse(surface.restoreSelection())
    }
}
