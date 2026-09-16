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
            val bitmap = BrushVisualHarness.render(preset)
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
            bitmap.recycle()
        }
    }

    private fun metrics(bitmap: Bitmap): Metrics {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var changed = 0
        var darkness = 0L
        pixels.forEach { pixel ->
            val amount = 255 - ((Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3)
            if (amount > 1) changed++
            darkness += amount
        }
        return Metrics(changed, darkness)
    }

    private data class Metrics(val changedPixels: Int, val darkness: Long)
}
