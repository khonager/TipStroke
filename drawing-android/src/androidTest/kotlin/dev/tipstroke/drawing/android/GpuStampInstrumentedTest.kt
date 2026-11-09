package dev.tipstroke.drawing.android

import android.graphics.*
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GpuStampInstrumentedTest {
    @Test fun stampBatchesPreserveCoverageAcrossTilesAndUndo() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        for (config in listOf(StampBrush(flow = .2f), StampBrush(grain = StampGrain.PAPER, grainDepth = .6f),
            StampBrush(shape = StampShape.SQUARE, rotationDegrees = 37f, scatter = .1f, sizeJitter = .1f))) {
            val preset = BrushPreset.Blank.copy(stamp = config, spacing = .06f)
            val style = StrokeStyle(preset, 80f, .7f, RgbaColor(.1f, .2f, .7f), BlendBehavior.PAINT,
                selection = if (config.flow == .2f) SelectionRegion.rectangle(dev.tipstroke.core.geometry.Rect(128f, 128f, 384f, 320f)) else null)
            val samples = (0..40).map { i -> StrokeSample(0, Point(50f + i * 10, 180f + i * 2),
                .2f + .8f * i / 40, .3f, 0f, i * 8_000_000L, 0, PointerKind.STYLUS) }
            val stroke = CompletedStroke(samples, style)
            val gpu = TileStore(512, 512)
            val cpu = TileStore(512, 512)
            GpuStampPreview(style, 512, 512).use { preview ->
                cpu.beginLiveStroke()
                preview.append(samples.take(1)); cpu.appendLiveStroke(CompletedStroke(samples.take(1), style))
                samples.zipWithNext().forEach { (a, b) ->
                    preview.append(listOf(a, b)); cpu.appendLiveStroke(CompletedStroke(listOf(a, b), style))
                }
                cpu.finishLiveStroke(stroke)
                gpu.commitGpuStamp(stroke, preview)
            }
            val actual = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val expected = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            gpu.draw(Canvas(actual), Paint()); cpu.draw(Canvas(expected), Paint())
            var error = 0L
            var painted = 0
            for (y in 0 until 512) for (x in 0 until 512) {
                val a = Color.alpha(actual.getPixel(x, y)); val b = Color.alpha(expected.getPixel(x, y))
                if (a > 0 || b > 0) { painted++; error += abs(a - b) }
            }
            assertTrue("GPU stamps disappeared", painted > 2000)
            if (style.selection != null) {
                assertEquals(Color.TRANSPARENT, actual.getPixel(100, 190))
                assertEquals(Color.TRANSPARENT, actual.getPixel(420, 250))
            }
            assertTrue("GPU coverage changed: mean alpha error=${error.toDouble() / painted}", error.toDouble() / painted < 8)
            assertTrue(gpu.history.undo()); assertEquals(0, gpu.allocatedTileCount)
            assertTrue(gpu.history.redo())
            val restored = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            gpu.draw(Canvas(restored), Paint()); assertTrue(actual.sameAs(restored))
            actual.recycle(); expected.recycle(); restored.recycle(); gpu.discard(); cpu.discard()
        }
    }

    @Test fun longGestureBoundsDisplayListRetention() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val preset = BrushPreset.Blank.copy(spacing = 2f, pressureToSize = PressureCurve(1f, 1f))
        val style = StrokeStyle(preset, 16f, 1f, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT)
        GpuStampPreview(style, 512, 512).use { preview ->
            repeat(2050) { index ->
                preview.append(listOf(StrokeSample(0, Point(if (index % 2 == 0) 128f else 160f, 256f),
                    1f, 0f, 0f, index * 4_000_000L, 0, PointerKind.STYLUS)))
            }
            assertTrue("Long gestures must switch to the bounded raster path", preview.capacityReached)
        }
    }

}
