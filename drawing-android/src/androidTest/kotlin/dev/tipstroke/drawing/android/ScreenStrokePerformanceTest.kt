package dev.tipstroke.drawing.android

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.*
import android.view.*
import androidx.ink.authoring.ExperimentalInkLatencyDataApi
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.authoring.latency.LatencyData
import androidx.ink.authoring.latency.LatencyDataCallback
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.services.storage.TestStorage
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Paced native input, not a tight event loop. Synthetic timings exclude digitizer/scanout latency. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalInkLatencyDataApi::class)
class ScreenStrokePerformanceTest {
    @Test fun reportFastScreenCrossings() = report(coverCanvas = false)

    @Test fun reportFastWholeCanvasCoverage() = report(coverCanvas = true)

    private fun report(coverCanvas: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = StringBuilder("${Build.MANUFACTURER} ${Build.MODEL}; API ${Build.VERSION.SDK_INT}\n" +
            (if (coverCanvas) "250 Hz; one 4-second full-pressure stroke; 32 alternating full-width sweeps covering the canvas.\n"
                else "250 Hz scheduled stylus samples; 320 ms screen crossing; 2 strokes/case.\n") +
            "queue = scheduled event to UI dispatch; dispatch = handler cost; Ink = authoring receipt to draw calls.\n" +
            "Frame gaps are UI vsync intervals, not photon latency. Handoff includes Ink debounce and raster commit.\n")
        fun stat(values: List<Double>): String {
            if (values.isEmpty()) return "n/a"
            val sorted = values.sorted()
            return "p50=%.2f p95=%.2f max=%.2f ms".format(sorted[sorted.size / 2], sorted[ceil((sorted.size - 1) * .95).toInt()], sorted.last())
        }
        val easy = BrushPreset.Blank.copy(displayName = "Dense custom", spacing = .02f,
            pressureToSize = PressureCurve(1f, 1f), pressureToOpacity = PressureCurve(1f, 1f),
            stamp = StampBrush(rotationDegrees = 10.057312f, flow = .5834822f))
        val allTips = (listOf(BrushPreset.Ink, easy) + BrushPreset.jetpackInk + listOf(BrushPreset.Airbrush, BrushPreset.Pencil)).map { it to false } + (BrushPreset.Ink to true)
        val onlyTip = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("performanceTip")
        val tips = allTips.map { (tip, erasing) ->
            val spacing = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("performanceSpacing")?.toFloat()
            (if (spacing != null && tip.ink != null) tip.copy(spacing = spacing) else tip) to erasing
        }.filter { onlyTip == null || (if (it.second) "Eraser" else it.first.displayName) == onlyTip }
        // MIUI blocks background activity starts even from instrumentation. A shell launch
        // brings this test-only activity to the foreground without changing device permissions.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/${MediaPlaybackTestActivity::class.java.name}"
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        ActivityScenario.launch<MediaPlaybackTestActivity>(Intent(context, MediaPlaybackTestActivity::class.java)).use { scenario ->
            lateinit var surface: DrawingSurface
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.window.attributes = activity.window.attributes.apply { preferredRefreshRate = 120f }
                surface = DrawingSurface(activity)
                activity.setContentView(surface)
            }
            SystemClock.sleep(1000)
            for (screenSize in if (coverCanvas) listOf(true) else listOf(false, true)) for ((tip, erasing) in tips) {
                val queue = mutableListOf<Double>()
                val dispatch = mutableListOf<Double>()
                val inkDraw = Collections.synchronizedList(mutableListOf<Double>())
                val frameGaps = mutableListOf<Double>()
                val handoffs = mutableListOf<Double>()
                var label = ""
                var latencyLogged = false
                repeat(if (coverCanvas) 1 else 2) {
                    val finished = CountDownLatch(1)
                    var upAt = 0L
                    var running = true
                    var previousFrame = 0L
                    lateinit var frameCallback: Choreographer.FrameCallback
                    scenario.onActivity {
                        val w = 5632
                        val h = 5632
                        surface.configureBlank(w, h)
                        val raster = surface.getChildAt(0) as RasterCanvasView
                        surface.settings.erasing = erasing
                        surface.settings.brush = tip
                        surface.settings.sizePx = if (screenSize) 180f / raster.transform.scale else 180f
                        surface.settings.opacity = tip.opacity
                        surface.settings.color = RgbaColor(.1f, .2f, .7f)
                        if (erasing) {
                            // Paint the center band before measurement. Erasing blank tiles
                            // would conceal the actual pixel-processing cost.
                            raster.layerStack.selectedStore()!!.apply {
                                replaceTiles((0 until 22).flatMap { x -> (if (coverCanvas) 0..21 else 6..15).map { y ->
                                    TileCoordinate(x, y) to android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
                                } }.toMap())
                                history.clear()
                            }
                        }
                        label = "${if (erasing) "Eraser" else tip.displayName} canvas=${w}x$h view=${surface.width}x${surface.height} zoom=${raster.transform.scale} size=${surface.settings.sizePx} refresh=${surface.display.refreshRate}"
                        (surface.getChildAt(1) as InProgressStrokesView).setLatencyDataCallback(object : LatencyDataCallback {
                            override fun onLatencyData(latency: LatencyData) {
                                val data = latency
                                if (!latencyLogged) { android.util.Log.i("TipStrokeInkLatency", data.toString()); latencyLogged = true }
                                val end = maxOf(data.strokesViewFinishesDrawCalls, data.hwuiInProgressStrokesRenderHelperData.finishesDrawCalls,
                                    data.canvasFrontBufferStrokesRenderHelperData.finishesDrawCalls)
                                if (end >= data.strokesViewGetsAction && data.strokesViewGetsAction > 0)
                                    inkDraw += (end - data.strokesViewGetsAction) / 1e6
                            }
                        })
                        surface.historyListener = { undo, _ -> if (undo && upAt > 0) {
                            handoffs += (SystemClock.uptimeMillis() - upAt).toDouble()
                            finished.countDown()
                        } }
                        frameCallback = Choreographer.FrameCallback { time ->
                            if (previousFrame != 0L) frameGaps += (time - previousFrame) / 1e6
                            previousFrame = time
                            if (running) Choreographer.getInstance().postFrameCallback(frameCallback)
                        }
                        Choreographer.getInstance().postFrameCallback(frameCallback)
                        val start = SystemClock.uptimeMillis() + 100
                        val handler = Handler(Looper.getMainLooper())
                        val steps = if (coverCanvas) 1000 else 80
                        for (i in 0..steps) handler.postAtTime({
                            val at = start + i * 4L
                            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; steps -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                            val coords = MotionEvent.PointerCoords().apply {
                                val t = i.toFloat() / steps
                                val position = if (coverCanvas) {
                                    val sweep = minOf(31, (t * 32).toInt())
                                    val across = t * 32 - sweep
                                    floatArrayOf(w * (.04f + .92f * (if (sweep % 2 == 0) across else 1f - across)),
                                        h * (.04f + .92f * t))
                                } else floatArrayOf(w * (.08f + .84f * t), h * (.5f + .12f * sin(t * PI.toFloat() * 2)))
                                raster.transformMatrix.mapPoints(position)
                                x = position[0]; y = position[1]
                                pressure = if (coverCanvas) 1f else .7f; size = 1f
                                setAxisValue(MotionEvent.AXIS_TILT, .3f)
                            }
                            val event = MotionEvent.obtain(start, at, action, 1, arrayOf(properties), arrayOf(coords),
                                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                            if (i == steps) upAt = SystemClock.uptimeMillis()
                            queue += (SystemClock.uptimeMillis() - at).toDouble()
                            val before = System.nanoTime()
                            surface.dispatchTouchEvent(event)
                            dispatch += (System.nanoTime() - before) / 1e6
                            event.recycle()
                        }, start + i * 4L)
                    }
                    assertTrue("$label did not finish", finished.await(if (coverCanvas) 180 else 45, TimeUnit.SECONDS))
                    scenario.onActivity {
                        running = false
                        Choreographer.getInstance().removeFrameCallback(frameCallback)
                        assertTrue((surface.getChildAt(0) as RasterCanvasView).layerStack.selectedStore()!!.allocatedTileCount > 0)
                        val stack = (surface.getChildAt(0) as RasterCanvasView).layerStack
                        if (coverCanvas) {
                            if (erasing) assertTrue("The sweep did not erase the painted canvas",
                                android.graphics.Color.alpha(stack.selectedStore()!!.colorAt(2816, 2816)) < 255)
                            else assertTrue("The stroke did not cover the canvas", stack.selectedStore()!!.allocatedTileCount > 250)
                            val memory = MemoryBudgetAdvisor(context).snapshot(5632, 5632, stack.estimatedDocumentBytes(), force = true)
                            label += " documentMiB=${memory.documentBytes / 1048576} undoMiB=${stack.undoBytes() / 1048576} pressure=${memory.pressure}"
                        }
                        surface.historyListener = null
                    }
                    SystemClock.sleep(150)
                }
                val line = "$label\n queue ${stat(queue)}; dispatch ${stat(dispatch)}; Ink ${stat(inkDraw.toList())}; frames ${stat(frameGaps)}; handoff ${stat(handoffs)}\n"
                report.append(line)
                android.util.Log.i("TipStrokeScreenQA", line)
                TestStorage().openOutputFile(if (coverCanvas) "brush-qa/whole-canvas.txt" else "brush-qa/screen-strokes.txt").bufferedWriter().use { it.write(report.toString()) }
            }
        }
    }
}
