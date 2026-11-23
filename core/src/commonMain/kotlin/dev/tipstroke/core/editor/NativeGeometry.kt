package dev.tipstroke.core.editor

import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.QuickShape

/** Bounded native-host adapter to the very same recognizer Android uses. */
class NativeShapeTracker {
    private val points = ArrayList<Point>()
    fun append(x: Float, y: Float) {
        if (points.size == 1024) {
            val reduced = points.filterIndexed { index, _ -> index % 2 == 0 }
            points.clear(); points.addAll(reduced)
        }
        points += Point(x, y)
    }
    fun elastic(): NativeShape = NativeShape("CURVE", dev.tipstroke.core.geometry.ElasticLine.fit(points))
    fun fit(): NativeShape? = QuickShape.recognize(points)?.let { NativeShape(it.kind.name, it.points) }
}
class NativeShape(val kind: String, val points: List<Point>) {
    fun bend(x: Float, y: Float): NativeShape = NativeShape(kind, dev.tipstroke.core.geometry.ElasticLine.bend(points, Point(x, y)))
}

class NativeColorPush(x: Float, y: Float, dx: Float, dy: Float, private val radius: Float, private val strength: Float) {
    private val center = Point(x, y)
    private val delta = Point(dx, dy)
    fun source(x: Float, y: Float): Point = dev.tipstroke.core.geometry.ColorPush.source(Point(x, y), center, delta, radius, strength)
}

class NativePerspectiveRuler(mode: Int, x1: Float, y1: Float, x2: Float, y2: Float, threshold: Float) {
    private val ruler = dev.tipstroke.core.geometry.MagneticRuler(
        dev.tipstroke.core.geometry.PerspectiveRuler(dev.tipstroke.core.geometry.PerspectiveMode.entries[mode.coerceIn(0, 2)], Point(x1, y1), Point(x2, y2)), threshold)
    fun project(x: Float, y: Float): Point = ruler.project(Point(x, y))
}
