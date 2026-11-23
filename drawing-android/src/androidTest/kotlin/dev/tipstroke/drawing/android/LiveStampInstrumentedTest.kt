package dev.tipstroke.drawing.android

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveStampInstrumentedTest {
    @Test fun calligraphyKeepsItsFirstMarksInTheHardwareFrame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MediaPlaybackTestActivity::class.java)
        ActivityScenario.launch<MediaPlaybackTestActivity>(intent).use { scenario ->
            lateinit var surface: DrawingSurface
            scenario.onActivity {
                surface = DrawingSurface(it)
                it.setContentView(surface)
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                surface.configureBlank(512, 512)
                surface.settings.brush = BrushPreset.Calligraphy
                surface.settings.sizePx = 24f
            }

            val down = SystemClock.uptimeMillis()
            fun sample(action: Int, offset: Float) {
                val width = minOf(surface.width, surface.height)
                val point = MotionEvent.PointerCoords().apply {
                    x = surface.width / 2f + width * offset
                    y = surface.height / 2f
                    pressure = 1f
                }
                val pen = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(pen), arrayOf(point),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                try { surface.dispatchTouchEvent(event) } finally { event.recycle() }
            }

            fun startingPatch(): Bitmap {
                val rendered = CountDownLatch(1)
                val copied = CountDownLatch(1)
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                var status = PixelCopy.ERROR_UNKNOWN
                scenario.onActivity {
                    surface.viewTreeObserver.registerFrameCommitCallback { rendered.countDown() }
                    surface.invalidate()
                }
                assertTrue("Window did not render", rendered.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    val origin = IntArray(2)
                    surface.getLocationInWindow(origin)
                    val x = origin[0] + surface.width / 2 - minOf(surface.width, surface.height) / 8
                    val y = origin[1] + surface.height / 2
                    PixelCopy.request(it.window, Rect(x - 8, y - 8, x + 8, y + 8), bitmap, {
                        status = it
                        copied.countDown()
                    }, Handler(Looper.getMainLooper()))
                }
                assertTrue(copied.await(10, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, status)
                return bitmap
            }

            val blank = startingPatch()
            scenario.onActivity {
                sample(MotionEvent.ACTION_DOWN, -.2f)
                sample(MotionEvent.ACTION_MOVE, -.05f)
            }
            val start = startingPatch()
            assertFalse("First marks must appear before lift", blank.sameAs(start))
            scenario.onActivity {
                for (i in 1..100) sample(MotionEvent.ACTION_MOVE, -.05f + .003f * i)
            }
            assertTrue("Later batches hid the starting marks", start.sameAs(startingPatch()))
            scenario.onActivity { sample(MotionEvent.ACTION_UP, .25f) }
            assertTrue("Lift changed the starting marks", start.sameAs(startingPatch()))
        }
    }

    @Test fun stampBatchesPreserveCoverageAcrossTilesAndUndo() {
        for (config in listOf(StampBrush(flow = .2f), StampBrush(grain = StampGrain.PAPER, grainDepth = .6f),
            StampBrush(shape = StampShape.SQUARE, rotationDegrees = 37f, scatter = .1f, sizeJitter = .1f))) {
            val preset = BrushPreset.Blank.copy(stamp = config, spacing = .06f)
            val style = StrokeStyle(preset, 80f, .7f, RgbaColor(.1f, .2f, .7f), BlendBehavior.PAINT,
                selection = if (config.flow == .2f) SelectionRegion.rectangle(dev.tipstroke.core.geometry.Rect(128f, 128f, 384f, 320f)) else null)
            val samples = (0..40).map { i -> StrokeSample(0, Point(50f + i * 10, 180f + i * 2),
                .2f + .8f * i / 40, .3f, 0f, i * 8_000_000L, 0, PointerKind.STYLUS) }
            val stroke = CompletedStroke(samples, style)
            val store = TileStore(512, 512)
            store.beginLiveStroke()
            store.appendLiveStroke(stroke.copy(samples = samples.take(1)))
            samples.zipWithNext().forEach { (a, b) ->
                store.appendLiveStroke(stroke.copy(samples = listOf(a, b)))
            }
            fun frame() = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also {
                store.draw(Canvas(it), Paint())
            }
            val actual = frame()
            assertTrue(Color.alpha(actual.getPixel(250, 220)) > 0)
            store.finishLiveStroke(stroke)
            assertTrue(actual.sameAs(frame()))
            if (style.selection != null) {
                assertEquals(Color.TRANSPARENT, actual.getPixel(100, 190))
                assertEquals(Color.TRANSPARENT, actual.getPixel(420, 250))
            }
            assertTrue(store.history.undo())
            assertEquals(0, store.allocatedTileCount)
            assertTrue(store.history.redo())
            assertTrue(actual.sameAs(frame()))
            store.discard()
        }
    }
}
