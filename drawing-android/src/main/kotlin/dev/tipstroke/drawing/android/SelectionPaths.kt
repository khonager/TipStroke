package dev.tipstroke.drawing.android

import android.graphics.Path
import dev.tipstroke.core.geometry.SelectionRegion

internal fun SelectionRegion.toAndroidPath(deltaX: Float = 0f, deltaY: Float = 0f): Path = Path().apply {
    val first = points.first()
    moveTo(first.x + deltaX, first.y + deltaY)
    points.drop(1).forEach { lineTo(it.x + deltaX, it.y + deltaY) }
    close()
}
