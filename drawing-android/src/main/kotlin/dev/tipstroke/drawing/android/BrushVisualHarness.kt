package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.ink.brush.Brush
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.drawing.PointerKind
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.drawing.StrokeStyle
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.BlendBehavior
import dev.tipstroke.core.model.BrushEngine
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.RgbaColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic, on-device rendering of the exact production brush families.
 *
 * This deliberately lives beside the production renderer: instrumentation tests and future brush
 * tooling can create reviewable PNGs without maintaining a second approximation of brush behavior.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
internal object BrushVisualHarness {
    const val WIDTH = 1200
    const val HEIGHT = 760

    fun render(preset: BrushPreset, nativePencil: Boolean = true): Bitmap {
        val families = TipStrokeInkBrushes()
        val renderer = CanvasStrokeRenderer.create(families.textureStore)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        // Upright pressure ramp, steady fine line, and a low-angle side contact.
        drawStroke(canvas, renderer, families, preset, 90f, .08f, 1f, .05f, 0f, 18f, nativePencil)
        drawStroke(canvas, renderer, families, preset, 225f, .48f, .48f, .1f, 0f, 12f, nativePencil)
        drawStroke(canvas, renderer, families, preset, 390f, .2f, .8f, 1.25f, (PI / 2).toFloat(), 14f, nativePencil)
        // Repeated side passes expose whether shading layers smoothly instead of looking like
        // a translucent marker ribbon.
        for (row in 0..28) {
            drawStroke(
                canvas, renderer, families, preset,
                520f + row * 4f, .12f, .22f, 1.3f, (PI / 2).toFloat(), 2f, nativePencil,
            )
        }
        return bitmap
    }

    fun renderPencilTiltStress(preset: BrushPreset): Bitmap {
        val width = 1200
        val height = 900
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val style = StrokeStyle(
            preset,
            preset.baseSizePx * 3f,
            1f,
            RgbaColor(28f / 255f, 30f / 255f, 34f / 255f),
            BlendBehavior.PAINT,
        )
        val circle = List(181) { index ->
            val angle = index / 180f * PI.toFloat() * 2f
            StrokeSample(
                0,
                Point(245f + cos(angle) * 145f, 255f + sin(angle) * 145f),
                .62f, 1.28f, .55f, index * 5_000_000L, 0, PointerKind.STYLUS,
            )
        }
        val verticalCurve = List(181) { index ->
            val progress = index / 180f
            StrokeSample(
                0,
                Point(600f + sin(progress * PI.toFloat() * 3f) * 95f, 70f + progress * 740f),
                .7f, 1.3f, 1.05f, index * 5_000_000L, 0, PointerKind.STYLUS,
            )
        }
        val corners = listOf(
            Point(860f, 70f),
            Point(1090f, 235f),
            Point(825f, 420f),
            Point(1090f, 610f),
            Point(850f, 825f),
        )
        val zigzag = corners.zipWithNext().flatMapIndexed { segmentIndex, (start, end) ->
            (0..45).mapNotNull { pointIndex ->
                if (segmentIndex > 0 && pointIndex == 0) return@mapNotNull null
                val progress = pointIndex / 45f
                val sampleIndex = segmentIndex * 45 + pointIndex
                StrokeSample(
                    0,
                    Point(
                        start.x + (end.x - start.x) * progress,
                        start.y + (end.y - start.y) * progress,
                    ),
                    .66f,
                    1.3f,
                    .2f + sampleIndex / 180f * 1.05f,
                    sampleIndex * 5_000_000L,
                    0,
                    PointerKind.STYLUS,
                )
            }
        }
        drawNativePencil(canvas, circle, style, width, height)
        drawNativePencil(canvas, verticalCurve, style, width, height)
        drawNativePencil(canvas, zigzag, style, width, height)
        return bitmap
    }

    private fun drawNativePencil(
        canvas: Canvas,
        samples: List<StrokeSample>,
        style: StrokeStyle,
        width: Int,
        height: Int,
    ) {
        val preview = TileStore(width, height)
        preview.appendPencilPreview(CompletedStroke(listOf(samples.first()), style), capStart = true)
        var context = listOf(samples.first())
        samples.drop(1).chunked(3).forEach { batch ->
            preview.appendPencilPreview(
                CompletedStroke(context + batch, style),
                skipFirstSegment = context.size == 2,
            )
            context = (context + batch).takeLast(2)
        }
        preview.appendPencilPreview(
            CompletedStroke(samples.takeLast(2), style),
            capEnd = true,
            skipFirstSegment = samples.size > 1,
        )
        preview.draw(canvas, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        preview.discard()
    }

    private fun drawStroke(
        canvas: Canvas,
        renderer: CanvasStrokeRenderer,
        families: TipStrokeInkBrushes,
        preset: BrushPreset,
        centerY: Float,
        startPressure: Float,
        endPressure: Float,
        tilt: Float,
        orientation: Float,
        waveHeight: Float,
        nativePencil: Boolean,
    ) {
        val samples = List(121) { index ->
            val progress = index / 120f
            val pressure = startPressure + (endPressure - startPressure) * progress
            StrokeSample(
                pointerId = 0,
                position = Point(
                    60f + progress * (WIDTH - 120f),
                    centerY + sin(progress * PI.toFloat() * 4f) * waveHeight,
                ),
                pressure = pressure,
                tiltRadians = tilt,
                orientationRadians = orientation,
                elapsedNanos = index * 8_000_000L,
                buttonState = 0,
                kind = PointerKind.STYLUS,
            )
        }
        val style = StrokeStyle(
            preset,
            preset.baseSizePx,
            preset.opacity,
            RgbaColor(28f / 255f, 30f / 255f, 34f / 255f),
            BlendBehavior.PAINT,
        )
        if (preset.engine == BrushEngine.PENCIL && nativePencil) {
            drawNativePencil(canvas, samples, style, WIDTH, HEIGHT)
            return
        }
        if (preset.engine == BrushEngine.AIRBRUSH) {
            StrokeCanvasPainter.draw(canvas, CompletedStroke(samples, style))
            return
        }
        val brush = Brush.createWithColorIntArgb(
            families.familyFor(preset),
            Color.argb((preset.opacity * 255).toInt().coerceIn(1, 255), 28, 30, 34),
            preset.baseSizePx,
            .05f,
        )
        val inputs = MutableStrokeInputBatch().apply {
            setNoiseSeed(0x51A7)
            samples.forEach { sample ->
                add(
                    InputToolType.STYLUS,
                    sample.position.x,
                    sample.position.y,
                    sample.elapsedNanos / 1_000_000L,
                    StrokeInput.NO_STROKE_UNIT_LENGTH,
                    sample.pressure,
                    sample.tiltRadians,
                    sample.orientationRadians,
                )
            }
        }
        renderer.draw(canvas, Stroke(brush, inputs), Matrix())
    }
}
