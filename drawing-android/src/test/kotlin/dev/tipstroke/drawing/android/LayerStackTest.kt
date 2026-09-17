package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import dev.tipstroke.core.geometry.Rect
import dev.tipstroke.core.geometry.SelectionRegion
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
class LayerStackTest {
    @Test fun selectedLayersCanBeDuplicated() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 512, 512) {}
        val original = stack.selectedRaster()!!
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(20, 30, Color.RED) }
        original.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to tile))

        val duplicatedIds = stack.duplicateSelected()

        assertEquals(1, duplicatedIds.size)
        assertEquals(2, stack.layers.size)
        assertEquals("Paint 1 copy", stack.selected().name)
        assertEquals(Color.RED, stack.selectedRaster()!!.tiles.colorAt(20, 30))
        assertNotSame(original.tiles, stack.selectedRaster()!!.tiles)
    }

    @Test fun selectedPixelsAreDuplicatedToANewSparseLayer() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 512, 512) {}
        val original = stack.selectedRaster()!!
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            setPixel(20, 30, Color.RED)
            setPixel(200, 210, Color.BLUE)
        }
        original.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to tile))
        val selection = SelectionRegion.rectangle(Rect(0f, 0f, 64f, 64f))

        val duplicatedIds = stack.duplicateSelection(selection)

        assertEquals(1, duplicatedIds.size)
        assertEquals(Color.RED, stack.selectedRaster()!!.tiles.colorAt(20, 30))
        assertEquals(Color.TRANSPARENT, stack.selectedRaster()!!.tiles.colorAt(200, 210))
        assertEquals(Color.BLUE, original.tiles.colorAt(200, 210))
    }

    @Test fun layersCanBeAddedToAndRemovedFromSelection() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 2048, 2048) {}
        val first = stack.selectedId
        val second = stack.addRaster()

        stack.toggleAdditionalSelection(first)
        assertEquals(setOf(first, second), stack.selectedLayerIds())
        assertEquals(2, stack.selectedRasters().size)

        stack.toggleAdditionalSelection(first)
        assertEquals(setOf(second), stack.selectedLayerIds())
        stack.toggleAdditionalSelection(second)
        assertEquals(setOf(second), stack.selectedLayerIds())
    }

    @Test fun selectedLayerCanBeRenamed() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 2048, 2048) {}

        stack.renameSelected("  Line art  ")
        assertEquals("Line art", stack.selected().name)

        stack.renameSelected("   ")
        assertEquals("Line art", stack.selected().name)
    }

    @Test fun paintLayersCanBeOrderedHiddenAndMadeTranslucent() {
        var invalidations = 0
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 2048, 2048) { invalidations++ }
        val first = stack.selectedId
        val second = stack.addRaster()

        stack.setOpacity(.42f)
        stack.toggleVisible(second)
        assertEquals(listOf(second, first), stack.summariesFrontToBack().map { it.id })
        assertEquals(.42f, stack.selected().opacity, .001f)
        assertFalse(stack.selected().visible)

        stack.moveSelected(towardFront = false)
        assertEquals(listOf(first, second), stack.summariesFrontToBack().map { it.id })
        assertTrue(stack.deleteSelected())
        assertEquals(1, stack.layers.size)
        assertTrue(invalidations >= 4)
    }
}
