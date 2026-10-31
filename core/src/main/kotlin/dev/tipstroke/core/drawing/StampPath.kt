package dev.tipstroke.core.drawing

import dev.tipstroke.core.geometry.Point
import kotlin.math.*

data class BrushStamp(val x: Float, val y: Float, val size: Float, val angle: Float, val alpha: Float, val hue: Float, val saturation: Float)

/** Stateful arc-length sampling: event batching and tile boundaries never restart the pattern. */
class StampPath(private val style: StrokeStyle) {
    private val brush = requireNotNull(style.brush.stamp)
    private var previous: StrokeSample? = null
    private var untilNext = 0f
    private var distance = 0f
    private var index = 0
    private val step = max(1f, style.sizePx * style.brush.spacing.coerceIn(.02f, 2f))

    fun append(samples: List<StrokeSample>): List<BrushStamp> = buildList {
        for (sample in samples) {
            val start = previous
            if (start == null) {
                emit(sample, 0f, 0f, this)
                untilNext = step
                previous = sample
                continue
            }
            val dx = sample.position.x - start.position.x
            val dy = sample.position.y - start.position.y
            val length = hypot(dx, dy)
            if (!length.isFinite() || length < .0001f) { previous = sample; continue }
            val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            val speed = length / ((sample.elapsedNanos - start.elapsedNanos).coerceAtLeast(1_000_000L) / 1_000_000_000f)
            val startDistance = distance
            var travelled = untilNext
            // Bound corrupted input jumps; ordinary strokes retain one-pixel minimum spacing.
            val increment = max(step, length / 8192f)
            while (travelled <= length) {
                val t = travelled / length
                val interpolated = sample.copy(position = Point(start.position.x + dx * t, start.position.y + dy * t),
                    pressure = start.pressure + (sample.pressure - start.pressure) * t,
                    opacityPressure = start.opacityPressure + (sample.opacityPressure - start.opacityPressure) * t,
                    tiltRadians = start.tiltRadians + (sample.tiltRadians - start.tiltRadians) * t)
                distance = startDistance + travelled
                emit(interpolated, angle, speed, this)
                travelled += increment
            }
            // Carry the remaining arc length across MotionEvent batches.
            distance = startDistance + length
            untilNext = travelled - length
            previous = sample
        }
    }

    private fun emit(sample: StrokeSample, direction: Float, speed: Float, out: MutableList<BrushStamp>) {
        val taper = if (brush.startTaperPx > 0f) (distance / brush.startTaperPx).coerceIn(.02f, 1f) else 1f
        val fade = if (brush.fadeOutPx > 0f) (1f - distance / brush.fadeOutPx).coerceIn(0f, 1f) else 1f
        val speedScale = 1f - style.brush.speedTaper * (speed / 2500f).coerceIn(0f, .85f)
        repeat(brush.count) {
            val seed = index++
            val size = style.sizePx * style.brush.pressureToSize.map(sample.pressure) * taper * speedScale *
                (1f + brush.tiltSize * (sample.tiltRadians / 1.2f).coerceIn(0f, 1f)) * (1f - brush.sizeJitter * noise(seed, 1))
            val radius = style.sizePx * brush.scatter * sqrt(noise(seed, 2))
            val theta = noise(seed, 3) * 2f * PI.toFloat()
            out += BrushStamp(sample.position.x + cos(theta) * radius, sample.position.y + sin(theta) * radius,
                size.coerceAtLeast(.1f), brush.rotationDegrees + (if (brush.followDirection) direction else 0f) +
                    (noise(seed, 4) * 2f - 1f) * brush.rotationJitter,
                (style.opacity * style.color.alpha * style.brush.pressureToOpacity.map(sample.opacityPressure) * brush.flow *
                    (1f - brush.opacityJitter * noise(seed, 5)) * fade).coerceIn(0f, 1f),
                (noise(seed, 6) * 2f - 1f) * brush.hueJitter * 180f,
                (noise(seed, 7) * 2f - 1f) * brush.saturationJitter)
        }
    }

    private fun noise(seed: Int, channel: Int): Float {
        var n = seed * 374761393 + channel * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)) ushr 8) / 16777215f
    }
}
