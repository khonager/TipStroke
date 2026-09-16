package dev.tipstroke.drawing.android

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TipStrokeInkBrushesTest {
    @Test fun generatedPencilGrainIsOpaqueEnoughButNotFlat() {
        val bitmap = TipStrokeInkBrushes.pencilGrainTexture()
        val alphas = alphaRange(bitmap)
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
        assertTrue(alphas.first >= 130)
        assertTrue(alphas.last > alphas.first)
        writeReviewTexture("pencil-grain.png", bitmap)
        bitmap.recycle()
    }

    @Test fun generatedAirbrushFieldIsSparseAndUsesHardEdgedPigmentPixels() {
        val bitmap = TipStrokeInkBrushes.airbrushParticleTexture(1234, .08f)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val visible = pixels.count { Color.alpha(it) > 0 }
        assertTrue(visible in 3_000..9_000)
        assertTrue(pixels.any { Color.alpha(it) == 0 })
        assertTrue(pixels.any { Color.alpha(it) > 150 })
        writeReviewTexture("airbrush-pigment-field.png", bitmap)
        bitmap.recycle()
    }

    private fun alphaRange(bitmap: android.graphics.Bitmap): IntRange {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.minOf(Color::alpha)..pixels.maxOf(Color::alpha)
    }

    private fun writeReviewTexture(name: String, bitmap: android.graphics.Bitmap) {
        val output = File("build/qa/brush-textures/$name")
        output.parentFile?.mkdirs()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val alphaPreview = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
        alphaPreview.setPixels(pixels.map { pixel ->
            val alpha = Color.alpha(pixel)
            Color.rgb(alpha, alpha, alpha)
        }.toIntArray(), 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        output.outputStream().use { check(alphaPreview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        alphaPreview.recycle()
    }
}
