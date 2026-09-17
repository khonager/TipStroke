package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import dev.tipstroke.core.geometry.TileCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisiblePaletteTest {
    @Test fun paletteUsesCompositedVisibleLayers() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 64, 64) {}
        val redLayer = stack.layers.single() as RasterLayerRuntime
        redLayer.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to solidTile(Color.RED)))
        stack.addRaster()
        val blueLayer = stack.selectedRaster()!!
        blueLayer.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to solidTile(Color.BLUE)))
        blueLayer.opacity = .5f

        val blended = stack.visiblePalette(1).single()
        assertTrue(blended.red in .4f..6f)
        assertTrue(blended.green < .1f)
        assertTrue(blended.blue in .4f..6f)

        blueLayer.visible = false
        val visibleOnly = stack.visiblePalette(1).single()
        assertTrue(visibleOnly.red > .9f)
        assertTrue(visibleOnly.green < .1f)
        assertTrue(visibleOnly.blue < .1f)
    }

    @Test fun paletteReturnsRequestedMostUsedAverageColors() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 64, 64) {}
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 64) {
            bitmap.setPixel(x, y, if (x < 42) Color.RED else Color.BLUE)
        }
        (stack.layers.single() as RasterLayerRuntime).tiles.replaceTiles(
            mapOf(TileCoordinate(0, 0) to bitmap),
        )

        val palette = stack.visiblePalette(2)
        assertEquals(2, palette.size)
        assertTrue(palette.first().red > .9f)
        assertTrue(palette.last().blue > .9f)
    }

    private fun solidTile(color: Int) = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
    }
}
