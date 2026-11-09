package dev.tipstroke.app

import kotlin.math.ln
import kotlin.math.exp

/** Size uses exponential travel, while numeric +/- controls keep document-unit steps. */
internal fun sliderFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val safe = value.coerceIn(range)
    return if (range.start >= 1f && range.endInclusive > 180f)
        (ln(safe / range.start) / ln(range.endInclusive / range.start)).coerceIn(0f, 1f)
    else ((safe - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
}
internal fun sliderValue(fraction: Float, range: ClosedFloatingPointRange<Float>): Float {
    val t = fraction.coerceIn(0f, 1f)
    return if (range.start >= 1f && range.endInclusive > 180f)
        (range.start * exp(t * ln(range.endInclusive / range.start))).coerceIn(range)
    else range.start + t * (range.endInclusive - range.start)
}
