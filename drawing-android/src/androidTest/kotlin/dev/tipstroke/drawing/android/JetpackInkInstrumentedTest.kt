package dev.tipstroke.drawing.android

import android.graphics.*
import android.os.Build
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.services.storage.TestStorage
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalInkCustomBrushApi::class)
class JetpackInkInstrumentedTest {
    @Test fun defaultSoftTipHasNoPeriodicBandsAtLightPressure() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val preset = BrushPreset.jetpackInk.single { it.id.value == "jetpack-soft-v1" }
        // Large tips expose individual particles. Include zero pressure because the
        // default size curve still leaves a visible 20%-width mark at contact.
        val failures = mutableListOf<String>()
        for (size in listOf(64f, 192f)) for (pressure in listOf(0f, .1f, .5f, 1f)) {
            for (step in listOf(4, 32)) {
                val stroke = CompletedStroke(
                    (0..(640 / step)).map { i ->
                        StrokeSample(0, Point(64f + i * step, 128f), pressure,
                            0f, 0f, i * 8_000_000L, 0, PointerKind.STYLUS)
                    },
                    StrokeStyle(preset, size, .61f, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT),
                )
                val bitmap = Bitmap.createBitmap(768, 256, Bitmap.Config.ARGB_8888)
                try {
                    JetpackInkRendering.draw(Canvas(bitmap), stroke)
                    val radius = size * preset.pressureToSize.map(pressure) / 2f
                    // Inspect both center and shoulder, away from starting/ending caps.
                    for (offset in listOf(0f, radius * .4f)) {
                        val y = (128f + offset).toInt()
                        val coverage = (256..512).map { x -> Color.alpha(bitmap.getPixel(x, y)) }
                        val range = coverage.max() - coverage.min()
                        assertTrue("Soft tip disappeared: size=$size pressure=$pressure step=$step", coverage.min() > 2)
                        val detail = "size=$size pressure=$pressure step=$step y=$y range=$range min=${coverage.min()} max=${coverage.max()}"
                        android.util.Log.i("TipStrokeSoftQA", detail)
                        // Allow a small residual from translucent particle compositing into
                        // an 8-bit target; this is not a claim of mathematically flat coverage.
                        if (range > 4) failures += detail
                    }
                    TestStorage().openOutputFile("jetpack-ink/soft-$size-$pressure-$step.png").use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
        assertTrue("Soft bands: ${failures.joinToString()}", failures.isEmpty())
    }

    private fun completed(preset: BrushPreset): CompletedStroke = CompletedStroke(
        (0..40).map { i -> StrokeSample(0, Point(80f + i * 9f, 128f), .2f + .8f * i / 40,
            i / 40f, 0f, i * 8_000_000L, 0, PointerKind.STYLUS) },
        StrokeStyle(preset, 64f, .8f, RgbaColor(.1f, .2f, .7f), BlendBehavior.PAINT))

    @Test fun nonCircularInkTipsKeepTheirRoundedGeometry() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val brushes = TipStrokeInkBrushes()
        for (texture in listOf(InkTexture.SOLID, InkTexture.SOFT)) {
            val preset = BrushPreset.BlankInk.copy(ink = InkBrush(texture = texture,
                particles = true, accumulate = true, aspectRatio = .3f, rounding = 1f))
            // A rounded non-square tip is a capsule, not an ellipse cut from a quad.
            assertEquals(1f, brushes.familyFor(preset).coats.single().tip.cornerRounding, 0f)
        }
    }

    @Test fun comparisonTipsCommitTheirActualGpuAppearanceAndUndoAsOneStroke() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val renderer = CanvasStrokeRenderer.create(TipStrokeInkBrushes().textureStore)
        for (preset in BrushPreset.jetpackInk) {
            val completed = completed(preset)
            val stroke = JetpackInkRendering.stroke(completed)
            val store = TileStore(512, 256)
            store.commitInk(completed, stroke, renderer)
            val bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
            store.draw(Canvas(bitmap), Paint())
            val pixels = IntArray(512 * 256)
            bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 256)
            if (preset.ink!!.tiltSize > 0f) {
                fun widthAt(x: Int) = (0 until 256).count { y -> Color.alpha(bitmap.getPixel(x, y)) > 2 }
                assertTrue("Graphite must respond to pressure and tilt", widthAt(400) > 2 * widthAt(100))
            }
            assertTrue("${preset.displayName} disappeared on commit", pixels.count { Color.alpha(it) > 0 } > 1000)
            val preview = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
            StrokeCanvasPainter.draw(Canvas(preview), completed)
            var maximumError = 0
            var totalError = 0L
            var different = 0
            for (y in 0 until 256) for (x in 0 until 512) {
                val a = bitmap.getPixel(x, y); val b = preview.getPixel(x, y)
                val error = kotlin.math.abs(Color.alpha(a) - Color.alpha(b))
                maximumError = maxOf(maximumError, error); totalError += error
                if (a != b) different++
            }
            android.util.Log.i("TipStrokeGpuQA", "${preset.displayName} atlas difference: maxAlpha=$maximumError meanAlpha=${totalError / (512.0 * 256)} different=$different")
            assertTrue("${preset.displayName} atlas altered coverage", maximumError <= 3 && totalError < 512 * 256)

            assertTrue(store.history.undo())
            assertEquals(0, store.allocatedTileCount)
            assertTrue(store.history.redo())
            val restored = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
            store.draw(Canvas(restored), Paint())
            assertTrue(bitmap.sameAs(restored))
            TestStorage().openOutputFile("jetpack-ink/${preset.id.value}.png").use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle(); preview.recycle(); restored.recycle(); store.discard()
        }
    }

    @Test fun comparisonInkRecordsExactRasterPatches() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val stack = LayerStack(context.contentResolver, 512, 256) {}
        val recorder = ProcessRecorder(512, 256)
        recorder.sync(stack, false)
        val completed = completed(BrushPreset.jetpackInk.last())
        stack.selectedRaster()!!.tiles.commitInk(completed, JetpackInkRendering.stroke(completed),
            CanvasStrokeRenderer.create(TipStrokeInkBrushes().textureStore))
        ProjectPersistence.executor.submit {}.get(20, java.util.concurrent.TimeUnit.SECONDS)
        val recording = recorder.snapshot()
        assertEquals(2, recording.actionCount)
        val expected = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        Canvas(expected).apply { drawColor(Color.WHITE); stack.selectedRaster()!!.tiles.draw(this, Paint()) }
        ProcessReplay(recording).use { replay ->
            val actual = replay.frame(recording.actionCount - 1)
            assertTrue("Ink replay must retain the actual rendered pixels", expected.sameAs(actual))
            actual.recycle()
        }
        recorder.detach(); expected.recycle(); stack.selectedRaster()!!.tiles.discard()
    }

    @Test fun nativeWetStrokesCompleteAndCancellationLeavesNoPaint() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = android.content.Intent(context, MediaPlaybackTestActivity::class.java)
        androidx.test.core.app.ActivityScenario.launch<MediaPlaybackTestActivity>(intent).use { scenario ->
            lateinit var surface: DrawingSurface
            scenario.onActivity { activity ->
                surface = DrawingSurface(activity)
                activity.setContentView(surface)
                surface.configureBlank(512, 256)
                surface.settings.gestures = GestureSettings(oneFingerDrag = FingerAction.DRAW)
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            for (preset in BrushPreset.jetpackInk + BrushPreset.Blank) for (toolType in listOf(android.view.MotionEvent.TOOL_TYPE_FINGER, android.view.MotionEvent.TOOL_TYPE_STYLUS)) {
                scenario.onActivity {
                    surface.settings.brush = preset
                    surface.settings.sizePx = 48f
                    surface.settings.opacity = .8f
                    val raster = surface.getChildAt(0) as RasterCanvasView
                    assertEquals(0, raster.layerStack.selectedStore()!!.allocatedTileCount)
                    fun gesture(cancel: Boolean) {
                        val start = android.os.SystemClock.uptimeMillis()
                        for (i in 0..20) {
                            val action = when (i) {
                                0 -> android.view.MotionEvent.ACTION_DOWN
                                20 -> if (cancel) android.view.MotionEvent.ACTION_CANCEL else android.view.MotionEvent.ACTION_UP
                                else -> android.view.MotionEvent.ACTION_MOVE
                            }
                            val properties = android.view.MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType }
                            val coords = android.view.MotionEvent.PointerCoords().apply {
                                x = surface.width * (.25f + .025f * i); y = surface.height * .5f
                                pressure = .2f + .8f * i / 20; size = 1f
                            }
                            val event = android.view.MotionEvent.obtain(start, start + i * 8, action, 1,
                                arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                            surface.dispatchTouchEvent(event); event.recycle()
                        }
                    }
                    gesture(true)
                    assertEquals("Cancelled Ink must not paint", 0, raster.layerStack.selectedStore()!!.allocatedTileCount)
                    gesture(false)
                }
                val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                var finished = false
                while (!finished && android.os.SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { finished = (surface.getChildAt(0) as RasterCanvasView).layerStack.selectedStore()!!.history.canUndo() }
                    if (!finished) android.os.SystemClock.sleep(25)
                }
                assertTrue("${preset.displayName} never completed its native wet stroke", finished)
                scenario.onActivity {
                    val store = (surface.getChildAt(0) as RasterCanvasView).layerStack.selectedStore()!!
                    assertTrue(store.allocatedTileCount > 0)
                    surface.undo()
                    assertEquals(0, store.allocatedTileCount)
                }
            }
        }
    }

    @Test fun nativeFamiliesAcceptCreatorBoundarySettings() {
        val families = TipStrokeInkBrushes()
        for (texture in InkTexture.entries) for (rounding in listOf(0f, 1f)) {
            families.familyFor(BrushPreset.BlankInk.copy(stabilization = 0f,
                ink = InkBrush(texture, .1f, rounding, -180f, particles = texture == InkTexture.SOFT,
                    accumulate = true, flow = .01f, tiltSize = 3f),
                pressureToSize = PressureCurve(1f, 1f), pressureToOpacity = PressureCurve(1f, 1f)))
        }
    }
}
