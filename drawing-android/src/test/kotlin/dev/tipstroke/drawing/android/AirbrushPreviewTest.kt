package dev.tipstroke.drawing.android

import android.graphics.*
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirbrushPreviewTest {
    private fun stroke(size: Float = 48f, pressure: Boolean = true) = CompletedStroke(
        (0..20).map { i -> StrokeSample(0, Point(100f + i * 15f, 256f), .3f + .7f * i / 20,
            0f, 0f, i * 10_000_000L, 0, PointerKind.STYLUS) },
        StrokeStyle(BrushPreset.Airbrush.copy(hardness = .2f,
            pressureToOpacity = if (pressure) PressureCurve(.2f, 1f, 1f) else PressureCurve(1f, 1f, 1f)),
            size, .8f, RgbaColor(.2f, .4f, .8f), BlendBehavior.PAINT),
    )

    @Test fun previewMatchesFullResolutionCoverageAtOneToOneZoom() {
        for (pressure in listOf(false, true)) {
            val stroke = stroke(pressure = pressure)
            val expected = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val actual = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            StrokeCanvasPainter.draw(Canvas(expected), stroke)
            AirbrushPreview().use { it.draw(Canvas(actual), stroke, 1f) }
            var error = 0L
            var count = 0
            for (y in 180..330) for (x in 50..460) {
                val a = Color.alpha(expected.getPixel(x, y))
                val b = Color.alpha(actual.getPixel(x, y))
                if (a > 0 || b > 0) { error += abs(a - b); count++ }
            }
            assertTrue(count > 0)
            assertTrue("Mean coverage error: ${error.toFloat() / count}", error.toFloat() / count < 2f)
            expected.recycle(); actual.recycle()
        }
    }

    @Test fun veryBroadSoftCommitHasContinuousTileSeamsAndHardTipsStaySharp() {
        for (hardness in listOf(0f, 1f)) {
            val stroke = stroke(2048f).let { it.copy(
                samples = listOf(it.samples.last().copy(position = Point(256f, 256f), pressure = 1f, opacityPressure = 1f)),
                style = it.style.copy(opacity = 1f, brush = it.style.brush.copy(hardness = hardness))) }
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            StrokeCanvasPainter.draw(Canvas(bitmap), stroke)
            for (y in 0 until 512) {
                assertTrue(abs(Color.alpha(bitmap.getPixel(255, y)) - Color.alpha(bitmap.getPixel(256, y))) <= 2)
            }
            if (hardness == 1f) assertEquals(255, Color.alpha(bitmap.getPixel(0, 0)))
            else assertTrue(Color.alpha(bitmap.getPixel(256, 256)) in 1..254)
            bitmap.recycle()
        }
    }

    @Test fun hugeZoomedOutBrushHasBoundedScratchAndReleasesIt() {
        val preview = AirbrushPreview()
        val bitmap = Bitmap.createBitmap(820, 820, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { scale(.1f, .1f) }
        val stroke = stroke(1800f).let { it.copy(samples = it.samples.map { sample ->
            sample.copy(position = Point(sample.position.x * 10, sample.position.y * 10))
        }) }
        preview.draw(canvas, stroke, .1f)
        assertTrue(preview.allocatedBytes in 1..(1024 * 1024 * 4))
        assertTrue(Color.alpha(bitmap.getPixel(250, 256)) > 0)
        preview.close()
        assertEquals(0, preview.allocatedBytes)
        bitmap.recycle()
    }
}
