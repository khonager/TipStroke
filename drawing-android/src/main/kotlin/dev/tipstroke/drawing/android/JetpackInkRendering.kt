package dev.tipstroke.drawing.android

import android.graphics.*
import android.hardware.HardwareBuffer
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.ink.brush.Brush
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.tipstroke.core.drawing.*
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Native Ink rendering for thumbnails, constrained strokes, and raster completion. */
@OptIn(ExperimentalInkCustomBrushApi::class)
internal object JetpackInkRendering {
    @get:androidx.annotation.ChecksSdkIntAtLeast(api = 34)
    val available: Boolean get() = Build.VERSION.SDK_INT >= 34
    private val families by lazy { TipStrokeInkBrushes() }
    private val renderer by lazy { CanvasStrokeRenderer.create(families.textureStore) }

    fun stroke(completed: CompletedStroke): Stroke {
        val style = completed.style
        val color = style.color
        val brush = Brush.createWithColorIntArgb(families.familyFor(style.brush),
            Color.argb((255 * style.opacity * color.alpha * requireNotNull(style.brush.ink).flow).roundToInt().coerceIn(1, 255),
                (color.red * 255).roundToInt(), (color.green * 255).roundToInt(), (color.blue * 255).roundToInt()),
            style.sizePx.coerceAtLeast(.001f), minOf(.1f, style.sizePx * .1f).coerceAtLeast(.00001f))
        val inputs = MutableStrokeInputBatch().apply {
            setNoiseSeed(0x51A7)
            val firstTime = completed.samples.first().elapsedNanos
            completed.samples.forEach { sample -> add(
                if (sample.kind == PointerKind.STYLUS) InputToolType.STYLUS else InputToolType.TOUCH,
                sample.position.x, sample.position.y, (sample.elapsedNanos - firstTime).coerceAtLeast(0) / 1_000_000L,
                StrokeInput.NO_STROKE_UNIT_LENGTH, sample.pressure.coerceIn(0f, 1f),
                sample.tiltRadians.coerceIn(0f, Math.PI.toFloat() / 2),
                ((sample.orientationRadians % (2 * Math.PI.toFloat())) + 2 * Math.PI.toFloat()) % (2 * Math.PI.toFloat())) }
        }
        return Stroke(brush, inputs)
    }

    fun draw(canvas: Canvas, completed: CompletedStroke) {
        // Host tests cannot load the Android Ink native library. Instrumentation exercises
        // the real families and GPU path; never substitute a different shipped brush.
        if (Build.FINGERPRINT.contains("robolectric", true)) return
        if (!available) return
        val ink = stroke(completed)
        if (canvas.isHardwareAccelerated) renderer.draw(canvas, ink, Matrix())
        else TileRenderer().use { it.draw(canvas, ink, renderer) }
    }

    /** One reusable 256px GPU target. No full-canvas bitmap, texture or pixel readback. */
    @RequiresApi(34)
    class TileRenderer : AutoCloseable {
        private val colorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        private val buffer = HardwareBuffer.create(256, 256, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        private val node = RenderNode("Ink raster tile").apply { setPosition(0, 0, 256, 256) }
        private val hardware = HardwareBufferRenderer(buffer).apply { setContentRoot(node) }

        fun draw(canvas: Canvas, stroke: Stroke, renderer: CanvasStrokeRenderer) {
            val clip = canvas.clipBounds
            for (y in Math.floorDiv(clip.top, 256)..Math.floorDiv(clip.bottom - 1, 256)) {
                for (x in Math.floorDiv(clip.left, 256)..Math.floorDiv(clip.right - 1, 256)) {
                    val left = x * 256f; val top = y * 256f
                    val recording = node.beginRecording()
                    recording.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    recording.translate(-left, -top)
                    renderer.draw(recording, stroke, Matrix())
                    node.endRecording()
                    val completion = CompletableFuture<HardwareBufferRenderer.RenderResult>()
                    hardware.obtainRenderRequest().setColorSpace(colorSpace).draw({ it.run() }) { completion.complete(it) }
                    val result = completion.get(5, TimeUnit.SECONDS)
                    result.fence.use { fence ->
                        check(result.status == HardwareBufferRenderer.RenderResult.SUCCESS && fence.await(Duration.ofSeconds(5))) {
                            "Ink tile rendering failed."
                        }
                    }
                    val gpu = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, colorSpace))
                    val pixels = try { requireNotNull(gpu.copy(Bitmap.Config.ARGB_8888, false)) } finally { gpu.recycle() }
                    try { canvas.drawBitmap(pixels, left, top, null) } finally { pixels.recycle() }
                }
            }
        }

        override fun close() { hardware.close(); node.discardDisplayList(); buffer.close() }
    }
}
