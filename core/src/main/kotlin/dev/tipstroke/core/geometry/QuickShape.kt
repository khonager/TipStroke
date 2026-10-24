package dev.tipstroke.core.geometry

import kotlin.math.*

/** Geometry only: recognition runs once after a hold, never on the sample hot path. */
object QuickShape {
    enum class Kind { LINE, CIRCLE, ELLIPSE, RECTANGLE, SQUARE, TRIANGLE, ARROW }
    data class Shape(val kind: Kind, val points: List<Point>)

    fun selectionBounds(start: Point, end: Point, constrained: Boolean): Rect {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val side = max(abs(dx), abs(dy))
        return Rect(start.x, start.y,
            start.x + if (constrained) side * if (dx < 0) -1 else 1 else dx,
            start.y + if (constrained) side * if (dy < 0) -1 else 1 else dy).normalized()
    }

    fun recognize(input: List<Point>): Shape? {
        if (input.size < 3) return null
        // Bound recognition cost even for long strokes.
        val points = if (input.size <= 512) input else List(512) { input[it * (input.size - 1) / 511] }
        val left = points.minOf { it.x }; val right = points.maxOf { it.x }
        val top = points.minOf { it.y }; val bottom = points.maxOf { it.y }
        val diagonal = hypot(right - left, bottom - top)
        if (!diagonal.isFinite() || diagonal < 1f) return null
        val length = points.zipWithNext().sumOf { (a, b) -> distance(a, b).toDouble() }.toFloat()
        val closed = distance(points.first(), points.last()) < diagonal * .22f && length > diagonal * 2f
        if (!closed) {
            val ends = listOf(points.first(), points.last())
            if (distance(ends[0], ends[1]) > diagonal * .8f &&
                length < distance(ends[0], ends[1]) * 1.18f &&
                points.maxOf { segmentDistance(it, ends[0], ends[1]) } < diagonal * .07f) {
                return Shape(Kind.LINE, densify(ends))
            }
            val corners = simplify(points, diagonal * .055f)
            // Single continuous arrow: tail → tip → wing → tip → other wing.
            if (corners.size == 5) {
                val (tail, tip, wing, back, other) = corners
                val shaft = distance(tail, tip)
                if (shaft > diagonal * .65f && distance(tip, back) < shaft * .15f) {
                    val ux = (tip.x - tail.x) / shaft; val uy = (tip.y - tail.y) / shaft
                    fun behind(p: Point) = (tip.x - p.x) * ux + (tip.y - p.y) * uy
                    fun side(p: Point) = (p.x - tip.x) * -uy + (p.y - tip.y) * ux
                    val depth = (behind(wing) + behind(other)) / 2f
                    val width = (abs(side(wing)) + abs(side(other))) / 2f
                    if (depth in shaft * .1f..shaft * .55f && width in shaft * .06f..shaft * .4f && side(wing) * side(other) < 0f) {
                        val sign = sign(side(wing))
                        val a = Point(tip.x - ux * depth - uy * width * sign, tip.y - uy * depth + ux * width * sign)
                        val b = Point(tip.x - ux * depth + uy * width * sign, tip.y - uy * depth - ux * width * sign)
                        return Shape(Kind.ARROW, densify(listOf(tail, tip, a, tip, b)))
                    }
                }
            }
            return null
        }
        val loop = points.dropLast(1) + points.first()
        val split = loop.indices.maxBy { distance(loop.first(), loop[it]) }
        val corners = (simplify(loop.take(split + 1), diagonal * .055f).dropLast(1) +
            simplify(loop.drop(split), diagonal * .055f).dropLast(1)).toMutableList()
        // A loop can begin halfway along an edge.
        if (corners.size > 3 && segmentDistance(corners.first(), corners.last(), corners[1]) < diagonal * .055f) corners.removeAt(0)
        if (corners.size == 3 && abs(cross(corners[0], corners[1], corners[2])) > diagonal * diagonal * .15f) {
            return Shape(Kind.TRIANGLE, densify(corners + corners.first()))
        }
        if (corners.size == 4) {
            val a = corners[0]; val b = corners[1]; val c = corners[2]; val d = corners[3]
            val w = (distance(a, b) + distance(c, d)) / 2f
            val h = (distance(b, c) + distance(d, a)) / 2f
            val orthogonal = corners.indices.all { i ->
                val p = corners[i]; val q = corners[(i + 1) % 4]; val r = corners[(i + 2) % 4]
                abs((p.x - q.x) * (r.x - q.x) + (p.y - q.y) * (r.y - q.y)) / (distance(p, q) * distance(r, q)).coerceAtLeast(.001f) < .3f
            }
            if (orthogonal && min(w, h) > diagonal * .15f) {
                val cx = corners.map { it.x }.average().toFloat(); val cy = corners.map { it.y }.average().toFloat()
                val ux = (b.x - a.x) / distance(a, b); val uy = (b.y - a.y) / distance(a, b)
                val direction = sign(cross(a, b, c))
                val square = abs(w - h) / max(w, h) < .15f
                val hw = (if (square) (w + h) / 2f else w) / 2f
                val hh = (if (square) (w + h) / 2f else h) / 2f
                val fitted = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).map { (x, y) ->
                    Point(cx + ux * hw * x - uy * hh * y * direction, cy + uy * hw * x + ux * hh * y * direction)
                }
                return Shape(if (square) Kind.SQUARE else Kind.RECTANGLE, densify(fitted + fitted.first()))
            }
        }
        val rx = (right - left) / 2f; val ry = (bottom - top) / 2f
        if (min(rx, ry) < diagonal * .12f) return null
        val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
        val errors = points.map { abs(hypot((it.x - cx) / rx, (it.y - cy) / ry) - 1f) }
        if (errors.average() > .08 || errors.max() > .25f || length > 2f * PI * max(rx, ry) * 1.3f) return null
        val circle = abs(rx - ry) / max(rx, ry) < .2f
        val xRadius = if (circle) (rx + ry) / 2f else rx
        val yRadius = if (circle) xRadius else ry
        val start = atan2((points.first().y - cy) / ry, (points.first().x - cx) / rx)
        val direction = if (points.zipWithNext().sumOf { (a, b) -> ((a.x - cx) * (b.y - cy) - (a.y - cy) * (b.x - cx)).toDouble() } < 0) -1 else 1
        return Shape(if (circle) Kind.CIRCLE else Kind.ELLIPSE, List(257) { i ->
            val angle = start + direction * 2 * PI * i / 256
            Point(cx + xRadius * cos(angle).toFloat(), cy + yRadius * sin(angle).toFloat())
        })
    }

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    private fun cross(a: Point, b: Point, c: Point) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    private fun segmentDistance(p: Point, a: Point, b: Point): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy).coerceAtLeast(.00001f)).coerceIn(0f, 1f)
        return distance(p, Point(a.x + dx * t, a.y + dy * t))
    }
    private fun simplify(points: List<Point>, tolerance: Float): List<Point> {
        if (points.size <= 2) return points
        val index = (1 until points.lastIndex).maxBy { segmentDistance(points[it], points.first(), points.last()) }
        if (segmentDistance(points[index], points.first(), points.last()) <= tolerance) return listOf(points.first(), points.last())
        return simplify(points.take(index + 1), tolerance).dropLast(1) + simplify(points.drop(index), tolerance)
    }
    private fun densify(vertices: List<Point>): List<Point> = buildList {
        vertices.zipWithNext().forEach { (a, b) ->
            val count = ceil(distance(a, b) / 2f).toInt().coerceIn(1, 128)
            repeat(count) { i -> val t = i.toFloat() / count; add(Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)) }
        }
        add(vertices.last())
    }
}
