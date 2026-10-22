package dev.tipstroke.drawing.android

import android.graphics.Color
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.Rect
import dev.tipstroke.core.geometry.SelectionRegion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedFillTest {
    private fun finish(fill: AnimatedFill) {
        var iterations = 0
        while (!fill.complete) { fill.advance(); check(++iterations < 10000) }
    }

    @Test fun closedBoundaryContainsFillAndOneUndoRestoresSparseTiles() {
        val store = TileStore(300, 32)
        for (y in 0 until 32) store.putFillPixel(260, y, Color.BLACK)
        val fill = AnimatedFill(store, 250, 10, Color.RED, null)
        finish(fill)
        assertEquals(Color.TRANSPARENT, store.colorAt(250, 10)) // Preview is isolated.
        assertEquals(Color.RED, fill.preview.colorAt(259, 10))
        assertEquals(Color.TRANSPARENT, fill.preview.colorAt(261, 10))
        store.commitFill(fill.preview, Color.RED, fill.pixelCount)
        fill.preview.discard()
        assertEquals(Color.RED, store.colorAt(259, 10))
        assertTrue(store.history.undo())
        assertEquals(Color.TRANSPARENT, store.colorAt(259, 10))
        assertEquals(Color.BLACK, store.colorAt(260, 10))
        assertFalse(store.history.canUndo())
        assertTrue(store.history.redo())
        assertEquals(Color.RED, store.colorAt(259, 10))
    }

    @Test fun waveSpreadsThroughSinglePixelGapAndCanBeDiscarded() {
        val store = TileStore(64, 64)
        for (y in 0 until 64) if (y != 50) store.putFillPixel(32, y, Color.BLACK)
        val fill = AnimatedFill(store, 30, 2, Color.BLUE, null)
        fill.advance()
        assertEquals(Color.TRANSPARENT, fill.preview.colorAt(34, 2))
        finish(fill)
        assertEquals(Color.BLUE, fill.preview.colorAt(34, 2))
        fill.preview.discard()
        assertEquals(Color.TRANSPARENT, store.colorAt(30, 2))
        assertFalse(store.history.canUndo())
    }

    @Test fun selectionAndCanvasEdgesConstrainFill() {
        val store = TileStore(300, 20)
        val fill = AnimatedFill(store, 258, 8, Color.GREEN,
            SelectionRegion.rectangle(Rect(254f, 5f, 300f, 15f)))
        finish(fill)
        assertEquals(Color.GREEN, fill.preview.colorAt(299, 14))
        assertEquals(Color.TRANSPARENT, fill.preview.colorAt(253, 8))
        assertEquals(Color.TRANSPARENT, fill.preview.colorAt(258, 15))
        assertEquals(460L, fill.pixelCount)
    }

    @Test fun frontIsCircularAndReachesBeyondTheOldWaveSpeed() {
        val fill = AnimatedFill(TileStore(128, 128), 64, 64, Color.RED, null)
        // Time budgeting can split a radius across callbacks; wait for the diagonal.
        while (fill.preview.colorAt(80, 80) == Color.TRANSPARENT) fill.advance()
        assertEquals(Color.RED, fill.preview.colorAt(86, 64))
        assertEquals(Color.TRANSPARENT, fill.preview.colorAt(86, 86))
    }

    @Test fun overlapUnderpaintsTransparentEdgesWithoutEatingSolidInk() {
        val store = TileStore(16, 16)
        for (y in 0 until 16) {
            store.putFillPixel(8, y, Color.argb(128, 0, 0, 0))
            store.putFillPixel(9, y, Color.BLACK)
        }
        val fill = AnimatedFill(store, 2, 8, Color.RED, null)
        finish(fill)
        store.commitFill(fill.preview, Color.RED, fill.pixelCount)
        val edge = store.colorAt(8, 8)
        assertEquals(255, Color.alpha(edge))
        assertTrue(Color.red(edge) in 126..128)
        assertEquals(0, Color.green(edge))
        assertEquals(Color.BLACK, store.colorAt(9, 8))
        assertEquals(Color.TRANSPARENT, store.colorAt(10, 8))
        assertTrue(store.history.undo())
        assertEquals(Color.argb(128, 0, 0, 0), store.colorAt(8, 8))
    }

    @Test fun overlapRemovesWhiteMatteFromFlattenedAntialiasing() {
        val store = TileStore(16, 16)
        for (y in 0 until 16) for (x in 0 until 16) {
            store.putFillPixel(x, y, when (x) { 8 -> Color.rgb(128, 128, 128); 9 -> Color.BLACK; else -> Color.WHITE })
        }
        val fill = AnimatedFill(store, 2, 8, Color.RED, null)
        finish(fill)
        store.commitFill(fill.preview, Color.RED, fill.pixelCount)
        assertEquals(Color.rgb(128, 0, 0), store.colorAt(8, 8))
        assertEquals(Color.BLACK, store.colorAt(9, 8))
        assertEquals(Color.WHITE, store.colorAt(10, 8))
    }

    @Test fun twoPixelDiagonalCornerCoverageDoesNotCrossSolidInk() {
        val store = TileStore(16, 16)
        val tile = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
        tile.eraseColor(Color.BLACK)
        tile.setPixel(6, 6, Color.TRANSPARENT)
        tile.setPixel(7, 7, Color.argb(128, 0, 0, 0))
        tile.setPixel(8, 8, Color.argb(192, 0, 0, 0))
        store.replaceTiles(mapOf(dev.tipstroke.core.geometry.TileCoordinate(0, 0) to tile))
        val fill = AnimatedFill(store, 6, 6, Color.RED, null)
        finish(fill)
        store.commitFill(fill.preview, Color.RED, fill.pixelCount)
        assertEquals(Color.rgb(127, 0, 0), store.colorAt(7, 7))
        assertEquals(Color.rgb(63, 0, 0), store.colorAt(8, 8))
        assertEquals(Color.BLACK, store.colorAt(7, 6))
        assertEquals(Color.BLACK, store.colorAt(6, 7))
        assertEquals(Color.BLACK, store.colorAt(9, 9))
    }

    @Test fun recoloringReplacesOldColorInTranslucentFringeWithoutMakingItOpaque() {
        val store = TileStore(16, 16)
        for (y in 0 until 16) {
            for (x in 0..6) store.putFillPixel(x, y, Color.RED)
            store.putFillPixel(7, y, Color.argb(160, 255, 0, 0))
            store.putFillPixel(8, y, Color.argb(64, 255, 0, 0))
        }
        val fill = AnimatedFill(store, 3, 8, Color.BLUE, null)
        finish(fill)
        store.commitFill(fill.preview, Color.BLUE, fill.pixelCount)
        assertEquals(Color.BLUE, store.colorAt(6, 8))
        assertEquals(Color.argb(160, 0, 0, 255), store.colorAt(7, 8))
        assertEquals(Color.argb(64, 0, 0, 255), store.colorAt(8, 8))
        assertEquals(Color.TRANSPARENT, store.colorAt(9, 8))
        assertTrue(store.history.undo())
        assertEquals(Color.argb(160, 255, 0, 0), store.colorAt(7, 8))
        assertTrue(store.history.redo())
        assertEquals(Color.argb(160, 0, 0, 255), store.colorAt(7, 8))
    }

    @Test fun evenTheFaintestOldColorFringeIsRecolored() {
        val store = TileStore(16, 1)
        for (x in 0..6) store.putFillPixel(x, 0, Color.RED)
        store.putFillPixel(7, 0, Color.argb(1, 255, 0, 0))
        val fill = AnimatedFill(store, 2, 0, Color.BLUE, null)
        finish(fill)
        store.commitFill(fill.preview, Color.BLUE, fill.pixelCount)
        assertEquals(Color.argb(1, 0, 0, 255), store.colorAt(7, 0))
        assertEquals(Color.TRANSPARENT, store.colorAt(8, 0))
    }

    @Test fun repeatedRecoloringRemovesOldColorFromBothSoftPixelsOfAnOutline() {
        val store = TileStore(16, 16)
        for (y in 0 until 16) for (x in 0 until 16) {
            store.putFillPixel(x, y, when (x) {
                7 -> Color.rgb(192, 0, 0)
                8 -> Color.rgb(64, 0, 0)
                9 -> Color.BLACK
                else -> if (x < 7) Color.RED else Color.WHITE
            })
        }
        for (newColor in listOf(Color.GREEN, Color.BLUE)) {
            val fill = AnimatedFill(store, 2, 8, newColor, null)
            finish(fill)
            store.commitFill(fill.preview, newColor, fill.pixelCount)
            fill.discard()
            assertEquals(if (newColor == Color.GREEN) Color.rgb(0, 192, 0) else Color.rgb(0, 0, 192), store.colorAt(7, 8))
            assertEquals(if (newColor == Color.GREEN) Color.rgb(0, 64, 0) else Color.rgb(0, 0, 64), store.colorAt(8, 8))
            assertEquals(Color.BLACK, store.colorAt(9, 8))
            assertEquals(Color.WHITE, store.colorAt(10, 8))
        }
    }

    @Test fun openBackgroundSpillsPastTheOld128TileLimit() {
        // A connected background with narrow channels spans 153 tiles. It used to
        // throw away the entire fill as soon as the 129th preview tile was reached.
        val store = TileStore(17 * 256, 9 * 256)
        val tiles = mutableMapOf<dev.tipstroke.core.geometry.TileCoordinate, android.graphics.Bitmap>()
        for (ty in 0 until 9) for (tx in 0 until 17) {
            val bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            bitmap.setPixels(IntArray(256), 0, 256, 0, 0, 256, 1)
            if (tx == 0) bitmap.setPixels(IntArray(256), 0, 1, 0, 0, 1, 256)
            tiles[dev.tipstroke.core.geometry.TileCoordinate(tx, ty)] = bitmap
        }
        store.replaceTiles(tiles)
        val fill = AnimatedFill(store, 1, 0, Color.BLUE, null, prepareReveal = true)
        finish(fill)
        assertEquals(153, fill.preview.allocatedTileCount)
        assertEquals(Color.BLUE, fill.preview.colorAt(17 * 256 - 1, 8 * 256))
        assertEquals(Color.BLUE, fill.preview.colorAt(0, 9 * 256 - 1))
        assertEquals(Color.TRANSPARENT, fill.preview.colorAt(1, 1))
        fill.takeRevealMasks().values.forEach(android.graphics.Bitmap::recycle)
        store.commitFill(fill.preview, Color.BLUE, fill.pixelCount)
        fill.discard()
        assertEquals(Color.BLUE, store.colorAt(17 * 256 - 1, 8 * 256))
        assertEquals(Color.BLACK, store.colorAt(1, 1))
        assertTrue(store.history.undo())
        assertEquals(Color.TRANSPARENT, store.colorAt(17 * 256 - 1, 8 * 256))
        store.discard()
    }

    @Test fun sameColorAndTransparentDropsDoNotCreateHistory() {
        val store = TileStore(16, 16)
        store.putFillPixel(1, 1, Color.RED)
        assertTrue(AnimatedFill(store, 1, 1, Color.RED, null).complete)
        assertTrue(AnimatedFill(store, 2, 2, Color.TRANSPARENT, null).complete)
        assertFalse(store.history.canUndo())
    }
}
