package dev.tipstroke.core.geometry

import kotlin.math.*

enum class PerspectiveMode { OFF, ONE_POINT, TWO_POINT }
data class PerspectiveRuler(val mode: PerspectiveMode, val first: Point, val second: Point)

/** Locks one direction after deliberate movement, never switching axes mid-stroke. */
class MagneticRuler(private val ruler: PerspectiveRuler, private val threshold: Float) {
    private var anchor: Point? = null
    private var direction: Point? = null
    fun project(point: Point): Point {
        if (ruler.mode == PerspectiveMode.OFF) return point
        val start = anchor ?: return point.also { anchor = it }
        val dx = point.x-start.x; val dy = point.y-start.y
        if (direction == null) {
            if (hypot(dx,dy) < threshold) return start
            val candidates = mutableListOf(Point(0f,1f), Point(ruler.first.x-start.x,ruler.first.y-start.y))
            candidates += if (ruler.mode == PerspectiveMode.ONE_POINT) Point(1f,0f)
                else Point(ruler.second.x-start.x,ruler.second.y-start.y)
            direction = candidates.filter { hypot(it.x,it.y) > .001f }.map {
                val length=hypot(it.x,it.y); Point(it.x/length,it.y/length)
            }.maxBy { abs(dx*it.x+dy*it.y) }
        }
        val d = requireNotNull(direction); val along = dx*d.x+dy*d.y
        return Point(start.x+d.x*along,start.y+d.y*along)
    }
}
