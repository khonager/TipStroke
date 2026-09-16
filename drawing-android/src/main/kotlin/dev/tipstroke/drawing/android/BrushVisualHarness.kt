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
import dev.tipstroke.core.model.BrushPreset
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
        val brush = Brush.createWithColorIntArgb(
            families.familyFor(preset),
            Color.argb((preset.opacity * 255).toInt().coerceIn(1, 255), 28, 30, 34),
            preset.baseSizePx,
            .05f,
        )
        val inputs = MutableStrokeInputBatch().apply {
            setNoiseSeed(0x51A7)
            repeat(121) { index ->
                val progress = index / 120f
                val pressure = .08f + (endPressure - .08f) * progress
                val x = 60f + progress * (WIDTH - 120f)
                val y = centerY + sin(progress * PI.toFloat() * 4f) * 24f
                add(
                    InputToolType.STYLUS,
                    x,
                    y,
                    index * 8L,
                    StrokeInput.NO_STROKE_UNIT_LENGTH,
                    pressure,
                    tilt,
                    orientation,
                )
            }
        }
        renderer.draw(canvas, Stroke(brush, inputs), Matrix())
    }
}
