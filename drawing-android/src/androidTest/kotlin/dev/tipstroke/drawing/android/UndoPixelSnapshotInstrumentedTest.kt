package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tipstroke.core.geometry.TileCoordinate
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UndoPixelSnapshotInstrumentedTest {
    @Test fun compressedSnapshotsPreservePremultipliedPixelsAndDeletions() {
        val tiles = (0 until 36).associate { index ->
            TileCoordinate(index, 0) to Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                // Include translucent pixels; color conversion through PNG/getPixels can
                // round premultiplied channels and would fail this byte-level check.
                eraseColor(0x73572a91)
                setPixel(index, index, 0x194382e7)
            }
        } + (TileCoordinate(40, 0) to null)
        fun bytes(bitmap: Bitmap) = ByteBuffer.allocate(bitmap.byteCount).also(bitmap::copyPixelsToBuffer).array()
        val snapshot = UndoPixelSnapshot(tiles)
        assertTrue(snapshot.estimatedBytes < 1024 * 1024)
        repeat(2) {
            snapshot.withDecoded { decoded ->
                assertEquals(tiles.keys, decoded.keys)
                tiles.forEach { (coordinate, original) ->
                    if (original == null) assertNull(decoded[coordinate])
                    else assertArrayEquals(bytes(original), bytes(requireNotNull(decoded[coordinate])))
                }
            }
        }
    }
}
