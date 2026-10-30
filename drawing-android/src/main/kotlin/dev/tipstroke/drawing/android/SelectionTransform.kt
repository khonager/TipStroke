package dev.tipstroke.drawing.android

import android.graphics.Matrix
import dev.tipstroke.core.geometry.SelectionTransform

internal fun SelectionTransform.toAndroidMatrix() = Matrix().apply {
    setValues(floatArrayOf(a, b, offsetX, c, d, offsetY, 0f, 0f, 1f))
}
