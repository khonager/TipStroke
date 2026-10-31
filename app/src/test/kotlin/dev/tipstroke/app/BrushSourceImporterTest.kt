package dev.tipstroke.app

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrushSourceImporterTest {
    private fun source(bitmap: Bitmap) = ByteArrayOutputStream().also {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    @Test fun transparentBlackArtworkUsesAlphaAndOpaqueArtworkUsesLuminance() {
        val transparent = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(transparent).drawRect(4f, 4f, 12f, 12f, android.graphics.Paint().apply { color = Color.BLACK })
        val shape = BrushSourceImporter.read(ByteArrayInputStream(source(transparent)))
        assertEquals(255, shape.coverage(128, 128))
        assertEquals(0, shape.coverage(0, 0))
        val opaque = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        android.graphics.Canvas(opaque).drawRect(4f, 4f, 12f, 12f, android.graphics.Paint().apply { color = Color.BLACK })
        val grain = BrushSourceImporter.read(ByteArrayInputStream(source(opaque)))
        assertEquals(0, grain.coverage(128, 128))
        assertEquals(255, grain.coverage(0, 0))
        assertEquals(255, grain.inverted().coverage(128, 128))
        assertThrows(IllegalArgumentException::class.java) { BrushSourceImporter.read(ByteArrayInputStream("invalid".toByteArray())) }
    }
}
