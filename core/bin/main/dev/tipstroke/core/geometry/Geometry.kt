package dev.tipstroke.core.geometry

import kotlin.math.*

data class Point(val x: Float, val y: Float)
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun expanded(by: Float) = Rect(left - by, top - by, right + by, bottom + by)
    fun normalized() = Rect(min(left, right), min(top, bottom), max(left, right), max(top, bottom))
}
data class TileCoordinate(val x: Int, val y: Int)

/** A transient polygonal editing boundary. Rectangle selections are represented by four points. */
enum class SelectionOperation { REPLACE, ADD, SUBTRACT, INTERSECT }

class SelectionRegion(points: List<Point>, val left: SelectionRegion? = null, val right: SelectionRegion? = null, val operation: SelectionOperation = SelectionOperation.REPLACE) {
    val points: List<Point> = points.toList()
    init { require(this.points.size >= 3) }
    val bounds: Rect = Rect(
        points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y },
    )
    fun translated(deltaX: Float, deltaY: Float): SelectionRegion = SelectionRegion(points.map { Point(it.x + deltaX, it.y + deltaY) }, left?.translated(deltaX, deltaY), right?.translated(deltaX, deltaY), operation)
    fun combined(other: SelectionRegion, operation: SelectionOperation): SelectionRegion {
        if (operation == SelectionOperation.REPLACE) return other
        val bounds = Rect(min(bounds.left, other.bounds.left), min(bounds.top, other.bounds.top), max(bounds.right, other.bounds.right), max(bounds.bottom, other.bounds.bottom))
        return SelectionRegion(rectangle(bounds).points, this, other, operation)
    }
    fun contains(point: Point): Boolean {
        if (left != null && right != null) return when (operation) {
            SelectionOperation.ADD -> left.contains(point) || right.contains(point)
            SelectionOperation.SUBTRACT -> left.contains(point) && !right.contains(point)
            SelectionOperation.INTERSECT -> left.contains(point) && right.contains(point)
            SelectionOperation.REPLACE -> right.contains(point)
        }
        var inside = false
        var previous = points.last()
        points.forEach { current ->
            val denominator = (previous.y - current.y).let { if (abs(it) < .0001f) .0001f else it }
            val crosses = (current.y > point.y) != (previous.y > point.y) &&
                point.x < (previous.x - current.x) * (point.y - current.y) / denominator + current.x
            if (crosses) inside = !inside
            previous = current
        }
        return inside
    }

    override fun equals(other: Any?) = other is SelectionRegion && points == other.points && left == other.left && right == other.right && operation == other.operation
    override fun hashCode(): Int = 31 * (31 * (31 * points.hashCode() + (left?.hashCode() ?: 0)) + (right?.hashCode() ?: 0)) + operation.hashCode()
    override fun toString() = "SelectionRegion(points=$points)"

    companion object {
        fun ellipse(rect: Rect): SelectionRegion {
            val r = rect.normalized()
            return SelectionRegion(List(128) { i ->
                val angle = i * 2.0 * PI / 128
                Point(((r.left + r.right) / 2 + cos(angle) * (r.right - r.left) / 2).toFloat(),
                    ((r.top + r.bottom) / 2 + sin(angle) * (r.bottom - r.top) / 2).toFloat())
            })
        }
        fun rectangle(rect: Rect): SelectionRegion {
            val value = rect.normalized()
            return SelectionRegion(listOf(
                Point(value.left, value.top), Point(value.right, value.top),
                Point(value.right, value.bottom), Point(value.left, value.bottom),
            ))
        }
    }
}

object TileGrid {
    const val DEFAULT_TILE_SIZE = 256

    fun intersecting(bounds: Rect, canvasWidth: Int, canvasHeight: Int, tileSize: Int = DEFAULT_TILE_SIZE): Set<TileCoordinate> {
        if (bounds.right < 0 || bounds.bottom < 0 || bounds.left >= canvasWidth || bounds.top >= canvasHeight) return emptySet()
        val minX = floor(bounds.left.coerceAtLeast(0f) / tileSize).toInt()
        val minY = floor(bounds.top.coerceAtLeast(0f) / tileSize).toInt()
        val maxX = floor((bounds.right.coerceAtMost(canvasWidth.toFloat() - 1f)) / tileSize).toInt()
        val maxY = floor((bounds.bottom.coerceAtMost(canvasHeight.toFloat() - 1f)) / tileSize).toInt()
        return buildSet { for (y in minY..maxY) for (x in minX..maxX) add(TileCoordinate(x, y)) }
    }
}

data class CanvasTransform(val panX: Float, val panY: Float, val scale: Float, val rotationDegrees: Float) {
    init { require(scale > 0f) }
    fun documentToScreen(point: Point): Point {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val sx = point.x * scale
        val sy = point.y * scale
        return Point((sx * cos(radians) - sy * sin(radians)).toFloat() + panX,
            (sx * sin(radians) + sy * cos(radians)).toFloat() + panY)
    }
    fun screenToDocument(point: Point): Point {
        val radians = Math.toRadians((-rotationDegrees).toDouble())
        val x = point.x - panX
        val y = point.y - panY
        return Point(((x * cos(radians) - y * sin(radians)) / scale).toFloat(),
            ((x * sin(radians) + y * cos(radians)) / scale).toFloat())
    }
}
