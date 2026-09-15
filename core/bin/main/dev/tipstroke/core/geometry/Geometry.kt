package dev.tipstroke.core.geometry

import kotlin.math.*

data class Point(val x: Float, val y: Float)
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun expanded(by: Float) = Rect(left - by, top - by, right + by, bottom + by)
    fun normalized() = Rect(min(left, right), min(top, bottom), max(left, right), max(top, bottom))
}
data class TileCoordinate(val x: Int, val y: Int)

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
