package dev.tipstroke.core.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** An affine edit around a fixed document-space pivot; negative scales flip an axis. */
data class SelectionTransform(
    val pivot: Point,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
) {
    init {
        require(pivot.x.isFinite() && pivot.y.isFinite())
        require(scaleX.isFinite() && abs(scaleX) in .01f..8f)
        require(scaleY.isFinite() && abs(scaleY) in .01f..8f)
        require(rotationDegrees.isFinite())
    }
    private val radians = Math.toRadians(rotationDegrees.toDouble())
    val a: Float = (cos(radians) * scaleX).toFloat()
    val b: Float = (-sin(radians) * scaleY).toFloat()
    val c: Float = (sin(radians) * scaleX).toFloat()
    val d: Float = (cos(radians) * scaleY).toFloat()
    val offsetX: Float = pivot.x - a * pivot.x - b * pivot.y
    val offsetY: Float = pivot.y - c * pivot.x - d * pivot.y
    fun map(point: Point) = Point(a * point.x + b * point.y + offsetX, c * point.x + d * point.y + offsetY)
    val isIdentity: Boolean get() = abs(a - 1f) < .00001f && abs(d - 1f) < .00001f && abs(b) < .00001f && abs(c) < .00001f
}

fun SelectionRegion.transformed(transform: SelectionTransform): SelectionRegion = SelectionRegion(
    points.map(transform::map), left?.transformed(transform), right?.transformed(transform), operation,
)
