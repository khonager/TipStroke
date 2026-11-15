package dev.tipstroke.ui

import kotlin.math.ln
import kotlin.math.exp

/** Drawing and hit testing must use the same density-scaled track endpoints. */
internal fun verticalSliderInset(heightPx: Float, density: Float): Float =
    minOf(14f * density, heightPx * .14f)

internal fun verticalSliderFraction(yPx: Float, heightPx: Float, density: Float): Float {
    if (heightPx <= 0f) return 0f
    val inset = verticalSliderInset(heightPx, density)
    return (1f - (yPx - inset) / (heightPx - 2f * inset)).coerceIn(0f, 1f)
}

/** Size uses exponential travel, while numeric +/- controls keep document-unit steps. */
fun sliderFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val safe = value.coerceIn(range)
    return if (range.start >= 1f && range.endInclusive > 180f)
        (ln(safe / range.start) / ln(range.endInclusive / range.start)).coerceIn(0f, 1f)
    else ((safe - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
}
fun sliderValue(fraction: Float, range: ClosedFloatingPointRange<Float>): Float {
    val t = fraction.coerceIn(0f, 1f)
    return if (range.start >= 1f && range.endInclusive > 180f)
        (range.start * exp(t * ln(range.endInclusive / range.start))).coerceIn(range)
    else range.start + t * (range.endInclusive - range.start)
}
