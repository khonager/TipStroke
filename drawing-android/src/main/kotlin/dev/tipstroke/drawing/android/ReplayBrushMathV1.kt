package dev.tipstroke.drawing.android

import dev.tipstroke.core.model.*

// Frozen v1 brush math. New brush behavior must not alter this file.
internal fun BrushPreset.replayFullTiltV1(): Float =
    1.2f -
        (1.2f - .08f) * pencilTiltSensitivity

internal fun replayTiltSensitivityV1(tiltRadians: Float): Float =
    ((1.2f - tiltRadians) /
        (1.2f - .08f))
        .coerceIn(0f, 1f)

internal fun BrushPreset.replayShadeEndV1(): Float =
    (pencilShadeStartRadians + pencilShadeTransitionRadians)
        .coerceAtMost(1.2f)

internal fun BrushPreset.replayTiltResponseV1(tiltRadians: Float): Float = when (pencilTiltMode) {
    PencilTiltMode.GRADUAL -> {
        val normalized = (
            (tiltRadians - .02f) /
                (replayFullTiltV1() - .02f)
            ).coerceIn(0f, 1f)
        1f - (1f - normalized) * (1f - normalized) * (1f - normalized)
    }
    PencilTiltMode.SHADING_SWITCH -> {
        val effectiveTransition = replayShadeEndV1() - pencilShadeStartRadians
        val normalized = (
            (tiltRadians - pencilShadeStartRadians) / effectiveTransition
            ).coerceIn(0f, 1f)
        normalized * normalized * (3f - 2f * normalized)
    }
}

internal fun PressureCurve.replayMapV1(pressure: Float): Float = start + (end - start) * Math.pow(pressure.coerceIn(0f, 1f).toDouble(), exponent.toDouble()).toFloat()
internal fun dev.tipstroke.core.drawing.CompletedStroke.replayBoundsV1(): dev.tipstroke.core.geometry.Rect {
    val radius = style.sizePx * when(style.brush.engine) {
        BrushEngine.PENCIL -> maxOf(1.75f, style.brush.pencilPointSize * (1f + 9f * style.brush.pencilShadeSize) * .1f)
        BrushEngine.AIRBRUSH -> 1.35f
        BrushEngine.INK -> .55f
    }
    return dev.tipstroke.core.geometry.Rect(samples.minOf { it.position.x }, samples.minOf { it.position.y }, samples.maxOf { it.position.x }, samples.maxOf { it.position.y }).expanded(radius)
}
