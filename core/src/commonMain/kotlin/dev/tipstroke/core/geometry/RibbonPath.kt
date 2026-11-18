package dev.tipstroke.core.geometry

import kotlin.math.hypot

/** A pen edge and a following edge form one closed filled ribbon. */
object RibbonPath {
    fun outline(points: List<Point>, width: Float, mirrored: Boolean = false): List<Point> {
        if (points.size < 2) return emptyList()
        val first = points.first()
        val direction = points.firstOrNull { hypot(it.x - first.x, it.y - first.y) > .01f } ?: return emptyList()
        val dx = direction.x - first.x; val dy = direction.y - first.y
        val length = hypot(dx, dy); val span = width.coerceAtLeast(1f)
        val side = if (mirrored) -1f else 1f
        var follower = Point(first.x - dy / length * span * side, first.y + dx / length * span * side)
        val trailing = ArrayList<Point>(points.size)
        points.forEach { tip ->
            val x = follower.x - tip.x; val y = follower.y - tip.y
            val distance = hypot(x, y)
            if (distance > span) follower = Point(tip.x + x / distance * span, tip.y + y / distance * span)
            trailing += follower
        }
        return points + trailing.asReversed()
    }
}
