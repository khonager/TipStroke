package dev.tipstroke.core.geometry

/** Bounded quadratic fitting and bending; endpoints never drift during adjustment. */
object ElasticLine {
    fun fit(input: List<Point>): List<Point> {
        if (input.size < 2) return input
        val a = input.first(); val b = input.last()
        val sampled = List(minOf(128, input.size)) { input[it * input.lastIndex / (minOf(128, input.size) - 1)] }
        var weight = 0f; var x = 0f; var y = 0f
        sampled.forEachIndexed { i, p ->
            val t = i.toFloat() / sampled.lastIndex; val u = 1f - t; val w = 2f * u * t
            weight += w * w
            x += w * (p.x - u * u * a.x - t * t * b.x)
            y += w * (p.y - u * u * a.y - t * t * b.y)
        }
        val c = if (weight > .0001f) Point(x / weight, y / weight) else Point((a.x + b.x) / 2, (a.y + b.y) / 2)
        return List(129) { i ->
            val t = i / 128f; val u = 1f - t
            Point(u * u * a.x + 2 * u * t * c.x + t * t * b.x,
                u * u * a.y + 2 * u * t * c.y + t * t * b.y)
        }
    }
    fun bend(points: List<Point>, delta: Point): List<Point> = points.mapIndexed { i, p ->
        val t = if (points.size > 1) i.toFloat() / points.lastIndex else 0f
        val w = 4f * t * (1 - t)
        Point(p.x + delta.x * w, p.y + delta.y * w)
    }
}
