package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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
    const val HEIGHT = 540

    fun render(preset: BrushPreset): Bitmap {
        val families = TipStrokeInkBrushes()
        val renderer = CanvasStrokeRenderer.create(families.textureStore)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        drawStroke(canvas, renderer, families, preset, 105f, .22f, .12f, 0f)
        drawStroke(canvas, renderer, families, preset, 270f, .68f, .58f, (PI / 5).toFloat())
        drawStroke(canvas, renderer, families, preset, 435f, 1f, 1.12f, (PI * .78).toFloat())
        return bitmap
    }

    private fun drawStroke(
        canvas: Canvas,
        renderer: CanvasStrokeRenderer,
        families: TipStrokeInkBrushes,
        preset: BrushPreset,
        centerY: Float,
        endPressure: Float,
        tilt: Float,
        orientation: Float,
    ) {
        val samples = List(121) { index ->
            val progress = index / 120f
            val pressure = .08f + (endPressure - .08f) * progress
            StrokeSample(
                pointerId = 0,
                position = Point(
                    60f + progress * (WIDTH - 120f),
                    centerY + sin(progress * PI.toFloat() * 4f) * 24f,
                ),
                pressure = pressure,
                tiltRadians = tilt,
                orientationRadians = orientation,
                elapsedNanos = index * 8_000_000L,
                buttonState = 0,
                kind = PointerKind.STYLUS,
            )
        }
        if (preset.engine == BrushEngine.AIRBRUSH) {
            StrokeCanvasPainter.draw(
                canvas,
                CompletedStroke(
                    samples,
                    StrokeStyle(
                        preset,
                        preset.baseSizePx,
                        preset.opacity,
                        RgbaColor(28f / 255f, 30f / 255f, 34f / 255f),
                        BlendBehavior.PAINT,
                    ),
                ),
            )
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
