package dev.tipstroke.app

internal fun sliderFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float =
    dev.tipstroke.ui.sliderFraction(value, range)
internal fun sliderValue(fraction: Float, range: ClosedFloatingPointRange<Float>): Float =
    dev.tipstroke.ui.sliderValue(fraction, range)
