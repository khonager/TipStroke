package dev.tipstroke.drawing.android

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TipStrokeInkBrushesTest {
    @Test fun generatedPencilGrainIsOpaqueEnoughButNotFlat() {
        val bitmap = TipStrokeInkBrushes.pencilGrainTexture()
        val alphas = alphaRange(bitmap)
        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
        assertTrue(alphas.first >= 28)
        assertTrue(alphas.last > alphas.first)
        bitmap.recycle()
    }

    @Test fun generatedAirbrushTextureIsSparseAndContainsVisibleParticles() {
        val bitmap = TipStrokeInkBrushes.airbrushParticleTexture()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val visible = pixels.count { Color.alpha(it) > 0 }
        assertTrue(visible > 1_000)
        assertTrue(visible < pixels.size / 4)
        bitmap.recycle()
    }

    private fun alphaRange(bitmap: android.graphics.Bitmap): IntRange {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.minOf(Color::alpha)..pixels.maxOf(Color::alpha)
    }
}
