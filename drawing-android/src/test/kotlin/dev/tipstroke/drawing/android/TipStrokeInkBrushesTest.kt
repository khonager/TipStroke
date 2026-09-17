package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.drawing.PointerKind
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.drawing.StrokeStyle
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.BlendBehavior
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.RgbaColor
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

    @Test fun softAirbrushRasterPathHasOneContinuousFalloff() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val samples = (0..20).map { index ->
            StrokeSample(
                0,
                Point(48f + index * 8f, 128f),
                1f,
                0f,
                0f,
                index * 8_000_000L,
                0,
                PointerKind.STYLUS,
            )
        }
        StrokeCanvasPainter.draw(
            Canvas(bitmap),
            CompletedStroke(
                samples,
                StrokeStyle(
                    BrushPreset.Airbrush.copy(hardness = 0f),
                    64f,
                    1f,
                    RgbaColor(0f, 0f, 0f),
                    BlendBehavior.PAINT,
                ),
            ),
        )
        val falloff = (0..128).map { y -> Color.alpha(bitmap.getPixel(128, y)) }
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
        assertTrue(falloff.zipWithNext().all { (outer, inner) -> outer <= inner })
        assertEquals(0, falloff.first())
        assertTrue(falloff.last() >= 245)
        assertTrue(falloff.toSet().size > 20)
        val centerline = (80..176).map { x -> Color.alpha(bitmap.getPixel(x, 128)) }
        assertTrue(centerline.max() - centerline.min() <= 2)
        writeReviewTexture("airbrush-falloff.png", bitmap)
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
