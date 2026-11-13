package dev.tipstroke.drawing.android

import android.graphics.Matrix
import dev.tipstroke.core.geometry.SelectionTransform

internal fun SelectionTransform.toAndroidMatrix() = Matrix().apply {
    setValues(floatArrayOf(a, b, offsetX, c, d, offsetY, 0f, 0f, 1f))
}

/** Conservative tile footprint including all intersected mesh cells, used for sparse culling. */
internal fun SelectionTransform.tileBounds(coordinate: dev.tipstroke.core.geometry.TileCoordinate, tileSize: Int): android.graphics.RectF {
    val left=coordinate.x*tileSize.toFloat(); val top=coordinate.y*tileSize.toFloat()
    val bounds=sourceBounds
    val points = if (warp == null || bounds == null) listOf(
        dev.tipstroke.core.geometry.Point(left,top), dev.tipstroke.core.geometry.Point(left+tileSize,top),
        dev.tipstroke.core.geometry.Point(left,top+tileSize), dev.tipstroke.core.geometry.Point(left+tileSize,top+tileSize))
    else {
        val w=bounds.right-bounds.left; val h=bounds.bottom-bounds.top
        val firstX=kotlin.math.floor((left-bounds.left)/w*16).toInt().coerceIn(0,16)
        val lastX=kotlin.math.ceil((left+tileSize-bounds.left)/w*16).toInt().coerceIn(0,16)
        val firstY=kotlin.math.floor((top-bounds.top)/h*16).toInt().coerceIn(0,16)
        val lastY=kotlin.math.ceil((top+tileSize-bounds.top)/h*16).toInt().coerceIn(0,16)
        (firstY..lastY).flatMap { row -> (firstX..lastX).map { col ->
            dev.tipstroke.core.geometry.Point(bounds.left+w*col/16f,bounds.top+h*row/16f)
        } }
    }
    val mapped=points.map(::map)
    return android.graphics.RectF(mapped.minOf { it.x },mapped.minOf { it.y },mapped.maxOf { it.x },mapped.maxOf { it.y })
}
