package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.services.storage.TestStorage
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.BrushEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BrushVisualInstrumentedTest {
    @Test fun productionBrushesRenderReviewableReferenceSheets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "brush-qa").apply { mkdirs() }
        listOf(BrushPreset.Pencil, BrushPreset.Airbrush).forEach { preset ->
            // Render the exact 100% setting as well as pressure variation so opacity regressions
            // cannot hide behind a deliberately light built-in default.
            val bitmap = BrushVisualHarness.render(preset.copy(opacity = 1f))
            val metrics = metrics(bitmap)
            val file = File(output, "${preset.id.value}.png")
            file.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            TestStorage().openOutputFile("brush-qa/${preset.id.value}.png").use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            Log.i("TipStrokeBrushQA", "${preset.displayName}: $metrics; ${file.absolutePath}")
            val minimumChanged = if (preset.engine == BrushEngine.AIRBRUSH) 30_000 else 12_000
            val minimumDarkness = if (preset.engine == BrushEngine.AIRBRUSH) 500_000L else 1_000_000L
            assertTrue("${preset.displayName} rendered too little final coverage: $metrics", metrics.changedPixels > minimumChanged)
            assertTrue("${preset.displayName} final output is too faint: $metrics", metrics.darkness > minimumDarkness)
            assertTrue("${preset.displayName} has no dark core at 100% opacity: $metrics", metrics.darkest <= 45)
            bitmap.recycle()
        }
        val inkPencilReference = BrushVisualHarness.render(BrushPreset.Pencil.copy(opacity = 1f), nativePencil = false)
        TestStorage().openOutputFile("brush-qa/pencil-ink-reference.png").use { stream ->
            check(inkPencilReference.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        inkPencilReference.recycle()
        val tiltStress = BrushVisualHarness.renderPencilTiltStress(BrushPreset.Pencil)
        TestStorage().openOutputFile("brush-qa/pencil-tilt-stress.png").use { stream ->
            check(tiltStress.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        tiltStress.recycle()
    }

    private fun metrics(bitmap: Bitmap): Metrics {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var changed = 0
        var darkness = 0L
        var darkest = 255
        pixels.forEach { pixel ->
            val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            val amount = 255 - brightness
            if (amount > 1) changed++
            darkness += amount
            darkest = minOf(darkest, brightness)
        }
        return Metrics(changed, darkness, darkest)
    }

    private data class Metrics(val changedPixels: Int, val darkness: Long, val darkest: Int)
}
