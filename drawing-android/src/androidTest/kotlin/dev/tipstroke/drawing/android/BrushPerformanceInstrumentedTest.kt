package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.services.storage.TestStorage
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.*
import org.junit.Test
import org.junit.runner.RunWith

/** Render-only timings: deliberately no device-independent pass/fail latency threshold. */
@RunWith(AndroidJUnit4::class)
class BrushPerformanceInstrumentedTest {
    @Test fun reportDenseStampsAndZoomedOutAirbrush() {
        val report = StringBuilder("Rendering only; excludes input delivery and display latency.\n")
        fun measure(name: String, work: () -> Unit) {
            repeat(2) { work() }
            val times = List(9) {
                val start = System.nanoTime()
                work()
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()
            val line = "$name: median=${times[times.size / 2]}ms p95=${times.last()}ms\n"
            report.append(line)
            Log.i("TipStrokeBrushQA", line)
        }
        val round = BrushPreset.Blank.copy(hardness = 1f, spacing = .02f,
            pressureToSize = PressureCurve(1f, 1f), pressureToOpacity = PressureCurve(1f, 1f),
            speedTaper = 0f, stamp = StampBrush(grainDepth = 0f, grainScale = 2f,
                rotationDegrees = 10.057312f, flow = .5834822f))
        for (size in listOf(180f, 720f)) {
            val style = StrokeStyle(round, size, 1f, RgbaColor(.1f, .2f, .4f), BlendBehavior.PAINT)
            val samples = listOf(sample(1024f, 0), sample(1280f, 16))
            measure("easy finger size=$size 256px sweep on 8192px canvas") {
                val store = TileStore(8192, 8192)
                store.beginLiveStroke()
                store.appendLiveStroke(CompletedStroke(samples, style))
                store.cancelLiveStroke()
                store.discard()
            }
        }
        val output = Bitmap.createBitmap(820, 820, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output).apply { scale(.1f, .1f) }
        val airbrush = CompletedStroke((0..200).map { i -> sample(1024f + i * 25f, i * 8L) },
            StrokeStyle(BrushPreset.Airbrush, 1800f, .8f, RgbaColor(.1f, .2f, .4f), BlendBehavior.PAINT))
        AirbrushPreview().use { preview ->
            measure("airbrush 201 samples, 1800px tip, zoom=0.1") {
                output.eraseColor(0)
                preview.draw(canvas, airbrush, .1f)
            }
        }
        output.recycle()
        TestStorage().openOutputFile("brush-qa/render-times.txt").bufferedWriter().use { it.write(report.toString()) }
    }

    private fun sample(x: Float, time: Long) = StrokeSample(0, Point(x, 4096f), 1f, 0f, 0f,
        time * 1_000_000L, 0, PointerKind.STYLUS)
}
