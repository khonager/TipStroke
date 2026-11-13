package dev.tipstroke.drawing.android

import android.graphics.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.SelectionTransform
import dev.tipstroke.core.model.PhotoWarp

/** Clip source mesh triangles to each sparse tile, preserving a single continuous mapping. */
internal fun Canvas.drawSelectionTile(tile: Bitmap, left: Float, top: Float,
    transform: SelectionTransform, paint: Paint) {
    if (transform.warp == null) {
        val saved = save()
        concat(transform.toAndroidMatrix())
        drawBitmap(tile, left, top, paint)
        restoreToCount(saved)
        return
    }
    val bounds = requireNotNull(transform.sourceBounds)
    val right = minOf(left + tile.width, bounds.right)
    val bottom = minOf(top + tile.height, bounds.bottom)
    val stepX = (bounds.right - bounds.left) / PhotoWarp.CELLS
    val stepY = (bounds.bottom - bounds.top) / PhotoWarp.CELLS
    val tilePaint = Paint(paint).apply {
        color = Color.WHITE
        shader = BitmapShader(tile, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    fun clip(points: List<Point>, axisX: Boolean, edge: Float, keepGreater: Boolean): List<Point> {
        if (points.isEmpty()) return points
        fun value(p: Point) = if (axisX) p.x else p.y
        fun inside(p: Point) = if (keepGreater) value(p) >= edge else value(p) <= edge
        val result = mutableListOf<Point>()
        var previous = points.last()
        for (current in points) {
            if (inside(previous) != inside(current)) {
                val t = (edge - value(previous)) / (value(current) - value(previous))
                result += Point(previous.x + (current.x - previous.x)*t, previous.y + (current.y - previous.y)*t)
            }
            if (inside(current)) result += current
            previous = current
        }
        return result
    }
    for (row in ((top-bounds.top)/stepY).toInt().coerceAtLeast(0)..(((bottom-bounds.top)/stepY).toInt().coerceAtMost(PhotoWarp.CELLS-1))) {
        for (col in ((left-bounds.left)/stepX).toInt().coerceAtLeast(0)..(((right-bounds.left)/stepX).toInt().coerceAtMost(PhotoWarp.CELLS-1))) {
            val a = Point(bounds.left+col*stepX,bounds.top+row*stepY); val b = Point(bounds.left+(col+1)*stepX,bounds.top+row*stepY)
            val c = Point(bounds.left+col*stepX,bounds.top+(row+1)*stepY); val d = Point(bounds.left+(col+1)*stepX,bounds.top+(row+1)*stepY)
            for (triangle in listOf(listOf(a,b,c), listOf(d,c,b))) {
                var polygon = clip(triangle,true,left,true)
                polygon = clip(polygon,true,right,false)
                polygon = clip(polygon,false,top,true)
                polygon = clip(polygon,false,bottom,false)
                for (i in 1 until polygon.size-1) {
                    val vertices = floatArrayOf(polygon[0].x,polygon[0].y,polygon[i].x,polygon[i].y,polygon[i+1].x,polygon[i+1].y)
                    val texture = vertices.copyOf()
                    for (j in 0..2) {
                        texture[j*2] -= left; texture[j*2+1] -= top
                        val mapped = transform.map(Point(vertices[j*2],vertices[j*2+1]))
                        vertices[j*2] = mapped.x; vertices[j*2+1] = mapped.y
                    }
                    drawVertices(Canvas.VertexMode.TRIANGLES,6,vertices,0,texture,0,null,0,null,0,0,tilePaint)
                }
            }
        }
    }
}
