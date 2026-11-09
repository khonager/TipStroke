package dev.tipstroke.drawing.android

import android.graphics.*
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
        private val atlas = GpuTileRasterizer(256)
        fun draw(canvas: Canvas, stroke: Stroke, renderer: CanvasStrokeRenderer) {
            val clip = canvas.clipBounds
            val tiles = buildList {
                for (y in Math.floorDiv(clip.top, 256)..Math.floorDiv(clip.bottom - 1, 256))
                    for (x in Math.floorDiv(clip.left, 256)..Math.floorDiv(clip.right - 1, 256))
                        add(dev.tipstroke.core.geometry.TileCoordinate(x, y))
            }
            atlas.rasterize(tiles, draw = { renderer.draw(it, stroke, Matrix()) }) { tile, pixels, source ->
                canvas.drawBitmap(pixels, source, Rect(tile.x * 256, tile.y * 256, (tile.x + 1) * 256, (tile.y + 1) * 256), null)
            }
        }
        override fun close() = atlas.close()
    }
}
