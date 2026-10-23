package dev.tipstroke.drawing.android

import android.graphics.Path
import dev.tipstroke.core.geometry.SelectionRegion

internal fun SelectionRegion.toAndroidPath(deltaX: Float = 0f, deltaY: Float = 0f): Path = Path().apply {
    val first = points.first()
    val left = left
    val right = right
    if (left != null && right != null) {
        set(left.toAndroidPath(deltaX, deltaY))
        op(right.toAndroidPath(deltaX, deltaY), when (operation) {
            dev.tipstroke.core.geometry.SelectionOperation.ADD -> Path.Op.UNION
            dev.tipstroke.core.geometry.SelectionOperation.SUBTRACT -> Path.Op.DIFFERENCE
            dev.tipstroke.core.geometry.SelectionOperation.INTERSECT -> Path.Op.INTERSECT
            dev.tipstroke.core.geometry.SelectionOperation.REPLACE -> Path.Op.UNION
        })
        return@apply
    }
    moveTo(first.x + deltaX, first.y + deltaY)
    points.drop(1).forEach { lineTo(it.x + deltaX, it.y + deltaY) }
    close()
}
