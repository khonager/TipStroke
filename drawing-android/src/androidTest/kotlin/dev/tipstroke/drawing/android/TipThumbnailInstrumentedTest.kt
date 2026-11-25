package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tipstroke.core.drawing.PixelTool
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.RgbaColor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TipThumbnailInstrumentedTest {
    @Test fun nativeSpecimensFitFolderAndPickerAtDifferentDisplayDensities() {
        val view = BrushStrokePreviewView(ApplicationProvider.getApplicationContext<Context>())
        val tips = BrushPreset.libraryBuiltIns + PixelTool.presets
        val failures = mutableListOf<String>()
        for ((width, height) in listOf(16 to 16, 20 to 20, 24 to 24, 96 to 48)) {
            view.layout(0, 0, width, height)
            for (tip in tips.filter { it.ink == null || Build.VERSION.SDK_INT >= 34 }) {
                view.setPreview(tip, false, tip.opacity, RgbaColor(1f, 1f, 1f), thumbnail = true)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                for (density in listOf(160, 480)) {
                    bitmap.eraseColor(Color.TRANSPARENT)
                    bitmap.density = density
                    view.draw(Canvas(bitmap))
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    val label = "${tip.displayName} at ${width}x${height} and ${density} dpi"
                    if (pixels.none { Color.alpha(it) > 0 }) failures += "$label is empty"
                    if (!(0 until height).all { y ->
                        Color.alpha(pixels[y * width]) == 0 && Color.alpha(pixels[(y + 1) * width - 1]) == 0
                    }) failures += "$label reaches the horizontal edge"
                }
                bitmap.recycle()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
