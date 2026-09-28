package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.RgbaColor
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
class BrushStrokePreviewViewTest {
    @Test fun pencilPreviewIsAContinuousNativeStroke() {
        val bitmap = render(BrushPreset.Pencil)
        val occupiedColumns = (0 until bitmap.width).count { x ->
            (0 until bitmap.height).any { y -> Color.alpha(bitmap.getPixel(x, y)) > 0 }
        }

        assertTrue(occupiedColumns > bitmap.width * .9f)
    }

    @Test fun airbrushPreviewContainsTheRasterPaintersSoftAlphaFalloff() {
        val bitmap = render(BrushPreset.Airbrush, opacity = BrushPreset.Airbrush.opacity)
        val alphaValues = buildSet {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                Color.alpha(bitmap.getPixel(x, y)).takeIf { it > 0 }?.let(::add)
            }
        }

        assertTrue(alphaValues.size > 20)
        assertTrue(alphaValues.any { it in 1..64 })
    }

    private fun render(brush: BrushPreset, opacity: Float = 1f): Bitmap {
        val width = 600
        val height = 160
        val view = BrushStrokePreviewView(RuntimeEnvironment.getApplication()).apply {
            setPreview(brush, erasing = false, opacity, RgbaColor(.25f, .25f, .28f))
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, width, height)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
}
