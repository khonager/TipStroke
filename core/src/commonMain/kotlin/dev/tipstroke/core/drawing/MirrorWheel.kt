package dev.tipstroke.core.drawing

import dev.tipstroke.core.geometry.Point
import kotlin.math.*

/** Two copies reflect horizontally; three or more repeat radially around [center]. */
data class MirrorWheel(val copies: Int, val center: Point) {
    init { require(copies in 2..12) }
    fun point(point: Point, copy: Int): Point {
        if (copy == 0) return point
        if (copies == 2) return Point(2 * center.x - point.x, point.y)
        val angle = 2f * PI.toFloat() * copy / copies
        val x = point.x - center.x; val y = point.y - center.y
        return Point(center.x + x * cos(angle) - y * sin(angle), center.y + x * sin(angle) + y * cos(angle))
    }
    fun orientation(value: Float, copy: Int): Float = if (copy == 0) value
        else if (copies == 2) -value else value + 2f * PI.toFloat() * copy / copies
}

fun CompletedStroke.mirrorCopies(): List<CompletedStroke> {
    val wheel = style.mirrorWheel ?: return listOf(this)
    val plainStyle = style.copy(mirrorWheel = null)
    return List(wheel.copies) { index ->
        CompletedStroke(samples.map { it.copy(position = wheel.point(it.position, index),
            orientationRadians = wheel.orientation(it.orientationRadians, index)) },
            plainStyle.copy(ribbonMirrored = style.ribbonMirrored xor (wheel.copies == 2 && index == 1)))
    }
}
