package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.roundToInt
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
import org.junit.Assert.assertTrue
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

    @Test fun fallingPressureProducesASmoothLineWithoutCircularBands() {
        val store = store()
        val style = style(90f).copy(opacity = 1f, brush = BrushPreset.Ink.copy(hardness = 1f,
            pressureToSize = PressureCurve(1f, 1f), pressureToOpacity = PressureCurve(0f, 1f, 1f)))
        val points = (128..896 step 128).map { x ->
            val pressure = 1f - (x - 128) / 1024f
            sample(x).copy(pressure = pressure, opacityPressure = pressure)
        }
        store.beginLiveStroke()
        store.appendLiveStroke(CompletedStroke(points.take(1), style))
        points.zipWithNext().forEach { (a, b) -> store.appendLiveStroke(CompletedStroke(listOf(a, b), style)) }
        for (x in 140..880) for (y in 230..282) {
            val expected = ((x + .5f - 128f) / 1024f * 255f).roundToInt()
            assertTrue("Pressure band at $x,$y", kotlin.math.abs(expected - Color.alpha(store.colorAt(x, y))) <= 1)
        }
        store.finishLiveStroke()
        val image = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.WHITE)
        store.draw(canvas, Paint())
        TestStorage().openOutputFile("eraser-qa/pressure-gradient.png").use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
        store.discard()
    }

    @Test fun nativeCoverageMatchesKotlinForPressureChangesAndCrossings() {
        assertTrue("The packaged native eraser library must load on the device", NativeEraser.available)
        val random = kotlin.random.Random(48)
        val before = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(256 * 256) { Color.argb(random.nextInt(256), 20, 70, 140) }, 0, 256, 0, 0, 256, 256)
        }
        try {
            for (hardness in listOf(0f, .35f, 1f)) for (size in listOf(32f, 180f, 718f)) {
                val native = before.copy(Bitmap.Config.ARGB_8888, true)
                val kotlin = before.copy(Bitmap.Config.ARGB_8888, true)
                val fast = LiveEraserCoverage(256, useNative = true)
                val reference = LiveEraserCoverage(256, useNative = false)
                val style = style(size).copy(brush = BrushPreset.Ink.copy(hardness = hardness,
                    pressureToSize = PressureCurve(.1f, 1f), pressureToOpacity = PressureCurve(.05f, 1f)))
                val points = List(30) { index -> sample(index).copy(
                    position = Point(random.nextFloat() * 600 - 180, random.nextFloat() * 600 - 180),
                    pressure = random.nextFloat(), opacityPressure = random.nextFloat()) }
                val a = IntArray(256 * 256)
                val b = IntArray(256 * 256)
                try {
                    (listOf(points.take(1)) + points.zipWithNext().map { listOf(it.first, it.second) }).forEach { samples ->
                        val stroke = CompletedStroke(samples, style)
                        fast.apply(native, before, TileCoordinate(0, 0), stroke)
                        reference.apply(kotlin, before, TileCoordinate(0, 0), stroke)
                        native.getPixels(a, 0, 256, 0, 0, 256, 256)
                        kotlin.getPixels(b, 0, 256, 0, 0, 256, 256)
                        assertArrayEquals("Coverage changed at size=$size hardness=$hardness", b, a)
                    }
                } finally { native.recycle(); kotlin.recycle() }
            }
        } finally { before.recycle() }
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
