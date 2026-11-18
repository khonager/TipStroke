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
    fun ribbon(width: Float): NativeShape = NativeShape("RIBBON", dev.tipstroke.core.geometry.RibbonPath.outline(points, width))
    fun elastic(): NativeShape = NativeShape("CURVE", dev.tipstroke.core.geometry.ElasticLine.fit(points))
    fun fit(): NativeShape? = QuickShape.recognize(points)?.let { NativeShape(it.kind.name, it.points) }
}
class NativeShape(val kind: String, val points: List<Point>) {
    fun bend(x: Float, y: Float): NativeShape = NativeShape(kind, dev.tipstroke.core.geometry.ElasticLine.bend(points, Point(x, y)))
}
