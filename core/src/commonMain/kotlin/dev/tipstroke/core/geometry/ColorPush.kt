package dev.tipstroke.core.geometry

import kotlin.math.*

/** Inverse local warp with zero displacement and slope at the circular boundary. */
object ColorPush {
    fun source(point: Point, center: Point, delta: Point, radius: Float, strength: Float): Point {
        val r = radius.coerceAtLeast(1f)
        val distance = hypot(point.x - center.x, point.y - center.y) / r
        if (distance >= 1f) return point
        val falloff = (1f - distance * distance).let { it * it }
        val length = hypot(delta.x, delta.y)
        val limit = if (length > r * .5f) r * .5f / length else 1f
        val amount = strength.coerceIn(0f, 1f) * falloff * limit
        return Point(point.x - delta.x * amount, point.y - delta.y * amount)
    }
}
