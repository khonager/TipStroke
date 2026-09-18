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
import dev.tipstroke.core.model.PressureCurve
import dev.tipstroke.core.model.RgbaColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TipStrokeInkBrushesTest {
    @Test fun flatPencilUsesABroadLightSideContact() {
        assertTrue(TipStrokeInkBrushes.PENCIL_MAX_TILT_WIDTH_MULTIPLIER >= 5f)
        assertTrue(TipStrokeInkBrushes.PENCIL_MAX_TILT_HEIGHT_MULTIPLIER >= 2.5f)
        assertTrue(TipStrokeInkBrushes.PENCIL_MIN_TILT_OPACITY_MULTIPLIER < .9f)
    }

    @Test fun pencilPreviewRespondsToTiltAndPressureBeforeCommit() {
        val style = StrokeStyle(
            BrushPreset.Pencil.copy(pressureToSize = PressureCurve(1f, 1f, 1f)),
            32f,
            1f,
            RgbaColor(0f, 0f, 0f),
            BlendBehavior.PAINT,
        )
        fun sample(x: Float, pressure: Float, tilt: Float = 0f) = StrokeSample(
            0, Point(x, 48f), pressure, tilt, 0f, x.toLong() * 1_000_000L, 0, PointerKind.STYLUS,
        )
        val upright = StrokeCanvasPainter.pencilTipDynamics(CompletedStroke(listOf(sample(48f, 1f)), style), sample(48f, 1f))
        val tiltedSample = sample(48f, 1f, (PI / 2).toFloat())
        val tilted = StrokeCanvasPainter.pencilTipDynamics(CompletedStroke(listOf(tiltedSample), style), tiltedSample)
        assertTrue("upright=$upright tilted=$tilted", tilted.width > upright.width * 5f)
        assertTrue("upright=$upright tilted=$tilted", tilted.height > upright.height * 2.5f)
        assertTrue("upright=$upright tilted=$tilted", tilted.alpha < upright.alpha * .9f)

        val bitmap = Bitmap.createBitmap(256, 96, Bitmap.Config.ARGB_8888)
        val samples = listOf(sample(32f, .05f), sample(80f, .05f), sample(176f, 1f), sample(224f, 1f))
        StrokeCanvasPainter.draw(Canvas(bitmap), CompletedStroke(samples, style))
        val lowPressureAlpha = Color.alpha(bitmap.getPixel(48, 48))
        val highPressureAlpha = Color.alpha(bitmap.getPixel(208, 48))
        assertTrue("low=$lowPressureAlpha high=$highPressureAlpha", highPressureAlpha > lowPressureAlpha + 100)
        bitmap.recycle()
    }

    @Test fun constantPressureCurveOmitsTheInvalidInkBehavior() {
        assertTrue(!pressureBehaviorEnabled(PressureCurve(1f, 1f, 1f)))
        assertTrue(pressureBehaviorEnabled(BrushPreset.Pencil.pressureToOpacity))
    }

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

    @Test fun airbrushPressureChangesOpacityAlongStroke() {
        val bitmap = Bitmap.createBitmap(256, 96, Bitmap.Config.ARGB_8888)
        val pressures = listOf(.05f, .05f, 1f, 1f)
        val samples = pressures.mapIndexed { index, pressure ->
            StrokeSample(0, Point(32f + index * 64f, 48f), pressure, 0f, 0f, index * 8_000_000L, 0, PointerKind.STYLUS)
        }
        StrokeCanvasPainter.draw(
            Canvas(bitmap),
            CompletedStroke(
                samples,
                StrokeStyle(
                    BrushPreset.Airbrush.copy(pressureToSize = PressureCurve(1f, 1f, 1f), hardness = 1f),
                    32f,
                    1f,
                    RgbaColor(0f, 0f, 0f),
                    BlendBehavior.PAINT,
                ),
            ),
        )

        val lightPressureAlpha = Color.alpha(bitmap.getPixel(48, 48))
        val fullPressureAlpha = Color.alpha(bitmap.getPixel(208, 48))
        assertTrue("low=$lightPressureAlpha high=$fullPressureAlpha", fullPressureAlpha > lightPressureAlpha + 100)
        bitmap.recycle()
    }

    @Test fun finishedInkPressureMaskChangesOpacityAlongStroke() {
        val bitmap = Bitmap.createBitmap(256, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val pressures = listOf(.05f, .05f, 1f, 1f)
        val samples = pressures.mapIndexed { index, pressure ->
            StrokeSample(0, Point(32f + index * 64f, 48f), pressure, 0f, 0f, index * 8_000_000L, 0, PointerKind.STYLUS)
        }
        val stroke = CompletedStroke(
            samples,
            StrokeStyle(
                BrushPreset.Ink.copy(pressureToSize = PressureCurve(1f, 1f, 1f)),
                24f,
                1f,
                RgbaColor(0f, 0f, 0f),
                BlendBehavior.PAINT,
            ),
        )

        StrokeCanvasPainter.applyPressureOpacityMask(bitmap, stroke, 0, 0)

        val lightPressureAlpha = Color.alpha(bitmap.getPixel(48, 48))
        val fullPressureAlpha = Color.alpha(bitmap.getPixel(208, 48))
        assertTrue("low=$lightPressureAlpha high=$fullPressureAlpha", fullPressureAlpha > lightPressureAlpha + 100)
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
