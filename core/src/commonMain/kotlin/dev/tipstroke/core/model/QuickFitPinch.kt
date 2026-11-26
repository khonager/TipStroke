package dev.tipstroke.core.model

/** A deliberate, short inward flick. Holding or reversing the pinch keeps the manual zoom. */
class QuickFitPinch {
    private var startSpan = 0f
    private var minimum = 0f
    private var current = 0f
    private var started = 0L
    private var lastContraction = 0L

    fun begin(span: Float, millis: Long) {
        startSpan = span
        minimum = span
        current = span
        started = millis
        lastContraction = millis
    }

    fun update(span: Float, millis: Long) {
        current = span
        if (span < minimum) {
            minimum = span
            lastContraction = millis
        }
    }

    fun finish(millis: Long): Boolean {
        val fits = startSpan > 0 && current > 0 && current <= startSpan * .68f &&
            current <= minimum * 1.06f && millis - started in 1..280 && millis - lastContraction in 0..90
        startSpan = 0f
        return fits
    }
}
