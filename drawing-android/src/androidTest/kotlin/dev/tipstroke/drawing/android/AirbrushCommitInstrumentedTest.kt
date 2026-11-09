package dev.tipstroke.drawing.android

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AirbrushCommitInstrumentedTest {
    @Test fun sharedBlurRegionsPreserveCoverageAndExactUndo() {
        for (size in listOf(48f, 180f, 718f)) for (hardness in listOf(0f, .35f, 1f)) {
            val samples = List(20) { i -> StrokeSample(0, Point(40f + i * 36, 160f + i * 9),
                .2f + .8f * i / 19, 0f, 0f, i * 8_000_000L, 0, PointerKind.STYLUS) }
            val style = StrokeStyle(BrushPreset.Airbrush.copy(hardness = hardness), size, .7f,
                RgbaColor(.2f, .3f, .8f), BlendBehavior.PAINT,
                selection = SelectionRegion.rectangle(dev.tipstroke.core.geometry.Rect(24f, 24f, 740f, 480f)))
            val stroke = CompletedStroke(samples, style)
            val reference = Bitmap.createBitmap(768, 512, Bitmap.Config.ARGB_8888)
            val actual = Bitmap.createBitmap(768, 512, Bitmap.Config.ARGB_8888)
            val store = TileStore(768, 512)
            try {
                // Reference the old per-tile commit path, including Skia's local
                // clipping/antialiasing, rather than a different full-image draw.
                for (y in 0 until 2) for (x in 0 until 3) {
                    val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(tile).apply {
                            translate(-x * 256f, -y * 256f)
                            StrokeCanvasPainter.draw(this, stroke)
                        }
                        Canvas(reference).drawBitmap(tile, x * 256f, y * 256f, null)
                    } finally { tile.recycle() }
                }
                store.commit(stroke)
                store.draw(Canvas(actual), Paint())
                val expected = IntArray(768 * 512)
                val result = IntArray(expected.size)
                reference.getPixels(expected, 0, 768, 0, 0, 768, 512)
                actual.getPixels(result, 0, 768, 0, 0, 768, 512)
                val maxAlphaError = expected.indices.maxOf { abs(Color.alpha(expected[it]) - Color.alpha(result[it])) }
                assertTrue("Blur coverage changed size=$size hardness=$hardness error=$maxAlphaError", maxAlphaError <= 2)
                assertTrue(result.any { Color.alpha(it) > 0 })
                assertTrue(store.history.undo()); assertEquals(0, store.allocatedTileCount)
                assertTrue(store.history.redo())
                reference.eraseColor(Color.TRANSPARENT)
                store.draw(Canvas(reference), Paint())
                assertTrue("Undo/redo must preserve exact pixels", actual.sameAs(reference))
            } finally { reference.recycle(); actual.recycle(); store.discard() }
        }
    }
}
