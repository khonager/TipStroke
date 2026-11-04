package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import dev.tipstroke.core.model.*
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

    @Test fun creatorPreviewUsesActualPresetSize() {
        val brush = BrushPreset.Pencil.copy(pencilShadeSize = 0f, pencilGrain = 0f)
        val small = render(brush.copy(baseSizePx = 4f), usePresetSize = true)
        val large = render(brush.copy(baseSizePx = 24f), usePresetSize = true)
        fun paintedPixels(bitmap: Bitmap): Int = (0 until bitmap.width).sumOf { x ->
            (0 until bitmap.height).count { y -> Color.alpha(bitmap.getPixel(x, y)) > 0 }
        }
        assertTrue(paintedPixels(large) > paintedPixels(small) * 2)
    }

    @Test fun thumbnailsRenderCustomSourcesTiltAndColorAndInvalidateCachedPixels() {
        val view = BrushStrokePreviewView(RuntimeEnvironment.getApplication()).apply {
            measure(View.MeasureSpec.makeMeasureSpec(112, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(72, View.MeasureSpec.EXACTLY))
            layout(0, 0, 112, 72)
        }
        fun thumbnail(preset: BrushPreset): Bitmap {
            view.setPreview(preset, false, preset.opacity, RgbaColor(.22f, .3f, .72f), thumbnail = true)
            return Bitmap.createBitmap(112, 72, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val tip = BrushPreset.Blank.copy(spacing = .3f)
        val original = thumbnail(tip)
        org.junit.Assert.assertTrue(original.sameAs(thumbnail(tip)))
        val stamp = requireNotNull(tip.stamp)
        val sparseMask = BrushTexture(4, 4, ByteArray(16) { if (it % 4 == 0) 255.toByte() else 0 })
        val shaped = tip.copy(stamp = stamp.copy(shape = StampShape.IMAGE, shapeTexture = sparseMask))
        org.junit.Assert.assertFalse(original.sameAs(thumbnail(shaped)))
        val grained = tip.copy(stamp = stamp.copy(grain = StampGrain.IMAGE, grainTexture = sparseMask, grainDepth = 1f, grainScale = 32f))
        org.junit.Assert.assertFalse(original.sameAs(thumbnail(grained)))
        org.junit.Assert.assertFalse(original.sameAs(thumbnail(tip.copy(stamp = stamp.copy(tiltSize = 3f)))))
        org.junit.Assert.assertFalse(original.sameAs(thumbnail(tip.copy(stamp = stamp.copy(hueJitter = 1f)))))
        org.junit.Assert.assertTrue(original.sameAs(thumbnail(tip)))
        // A completely empty imported source must remain empty, not fall back to a generic icon.
        val blank = thumbnail(tip.copy(stamp = stamp.copy(shape = StampShape.IMAGE, shapeTexture = BrushTexture(1, 1, byteArrayOf(0)))))
        assertTrue((0 until blank.width).all { x -> (0 until blank.height).all { y -> Color.alpha(blank.getPixel(x, y)) == 0 } })
    }

    private fun render(brush: BrushPreset, opacity: Float = 1f, usePresetSize: Boolean = false): Bitmap {
        val width = 600
        val height = 160
        val view = BrushStrokePreviewView(RuntimeEnvironment.getApplication()).apply {
            setPreview(brush, erasing = false, opacity, RgbaColor(.25f, .25f, .28f), usePresetSize)
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, width, height)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
}
