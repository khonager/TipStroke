package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.services.storage.TestStorage
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.Rect
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.*
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveEraserInstrumentedTest {
    private fun store() = TileStore(1024, 512).apply {
        replaceTiles((0..3).flatMap { x -> (0..1).map { y ->
            TileCoordinate(x, y) to Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
            }
        } }.toMap())
    }
    private fun sample(x: Int) = StrokeSample(1, Point(x.toFloat(), 256f), 1f, 0f, 0f,
        x * 100_000L, 0, PointerKind.STYLUS)
    private fun style(size: Float) = StrokeStyle(BrushPreset.Ink.copy(hardness = .2f, speedTaper = 1f),
        size, .7f, RgbaColor(1f, 0f, 0f), BlendBehavior.ERASE)

    @Test fun fastSegmentsHaveTheSameCoverageAsOneLongSweep() {
        fun render(step: Int): IntArray {
            val store = store()
            store.beginLiveStroke()
            val points = (128..896 step step).map(::sample)
            store.appendLiveStroke(CompletedStroke(points.take(1), style(96f)))
            points.zipWithNext().forEach { (a, b) -> store.appendLiveStroke(CompletedStroke(listOf(a, b), style(96f))) }
            store.finishLiveStroke()
            val pixels = IntArray(1024 * 160) { i -> store.colorAt(i % 1024, 176 + i / 1024) }
            store.discard()
            return pixels
        }
        assertArrayEquals(render(768), render(32))
    }

    @Test fun reportLiveUpdateCostOnTheDevice() {
        val report = StringBuilder("Per-update rendering time; excludes display and input delivery.\n")
        for (size in listOf(48f, 128f, 256f)) {
            for (clipped in listOf(false, true)) {
                val timings = mutableListOf<Long>()
                repeat(4) { pass ->
                    val store = store()
                    val style = style(size).let { if (clipped) it.copy(
                        selection = SelectionRegion.rectangle(Rect(0f, 0f, 1024f, 512f))) else it }
                    store.beginLiveStroke()
                    val points = (128..896 step 16).map(::sample)
                    points.zipWithNext().forEach { (a, b) ->
                        val segment = CompletedStroke(listOf(a, b), style)
                        val start = System.nanoTime()
                        store.appendLiveStroke(segment)
                        if (pass > 0) timings += System.nanoTime() - start
                    }
                    store.cancelLiveStroke()
                    store.discard()
                }
                timings.sort()
                val line = "size=$size ${if (clipped) "previous mask path with full-canvas selection" else "direct coverage"}: " +
                    "median=${timings[timings.size / 2] / 1_000_000.0}ms " +
                    "p95=${timings[(timings.size * .95).toInt()] / 1_000_000.0}ms\n"
                report.append(line)
                Log.i("TipStrokeEraserQA", line)
            }
        }
        TestStorage().openOutputFile("eraser-qa/update-times.txt").bufferedWriter().use { it.write(report.toString()) }
    }
}
