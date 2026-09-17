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
    @Test fun generatedPencilGrainUsesOpaqueGraphiteAndExposedPaper() {
        val bitmap = TipStrokeInkBrushes.pencilGrainTexture()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val opaqueRatio = pixels.count { Color.alpha(it) == 255 } / pixels.size.toFloat()
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
        assertTrue(pixels.all { Color.alpha(it) == 0 || Color.alpha(it) == 255 })
        assertTrue(opaqueRatio in .8f..9f)
        writeReviewTexture("pencil-grain.png", bitmap)
        bitmap.recycle()
    }

    @Test fun softAirbrushProfileIsFeatheredAndHardnessCollapsesTheFalloff() {
        val soft = TipStrokeInkBrushes.softAirbrushProfile(0f)
        val hard = TipStrokeInkBrushes.softAirbrushProfile(1f)
        assertEquals(10, soft.size)
        assertEquals(1f, soft.first().scale)
        assertTrue(soft.zipWithNext().all { (outer, inner) -> outer.scale > inner.scale })
        assertTrue(soft.first().opacity < soft.last().opacity)
        assertEquals(1f, soft.last().opacity)
        val compositedOpacity = 1f - soft.fold(1f) { remaining, coat -> remaining * (1f - coat.opacity) }
        assertEquals(1f, compositedOpacity, .0001f)
        assertTrue(hard.all { it.scale == 1f })
        assertEquals(soft.sumOf { it.opacity.toDouble() }, hard.sumOf { it.opacity.toDouble() }, .0001)
    }

    @Test fun airbrushCoatOpacityTextureIsUniformAndRendererSafe() {
        val bitmap = TipStrokeInkBrushes.uniformAlphaTexture(.16f)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertEquals(4, bitmap.width)
        assertTrue(pixels.all { Color.alpha(it) == 40 })
        bitmap.recycle()
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
