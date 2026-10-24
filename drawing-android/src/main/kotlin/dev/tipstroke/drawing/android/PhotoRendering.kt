package dev.tipstroke.drawing.android

import android.graphics.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

internal fun ImageTransform.documentPoint(u: Float, v: Float, width: Int, height: Int): Point {
    val p = warp?.map(u,v) ?: Point(u,v)
    val x=(p.x-.5f)*width*scale*stretchX; val y=(p.y-.5f)*height*scale*stretchY
    val angle=Math.toRadians(rotationDegrees.toDouble())
    return Point(centerX+(x*cos(angle)-y*sin(angle)).toFloat(),centerY+(x*sin(angle)+y*cos(angle)).toFloat())
}

internal fun ImageTransform.sourcePoint(x: Float,y: Float,width: Int,height: Int): Point? {
    val angle=Math.toRadians(-rotationDegrees.toDouble()); val dx=x-centerX; val dy=y-centerY
    val u=(dx*cos(angle)-dy*sin(angle)).toFloat()/(width*scale*stretchX)+.5f
    val v=(dx*sin(angle)+dy*cos(angle)).toFloat()/(height*scale*stretchY)+.5f
    return warp?.inverse(u,v) ?: if(warp==null) Point(u,v) else null
}

internal fun ImageTransform.photoBounds(width: Int,height: Int): RectF {
    val points=if(warp==null) listOf(documentPoint(0f,0f,width,height),documentPoint(1f,0f,width,height),documentPoint(0f,1f,width,height),documentPoint(1f,1f,width,height))
        else List((PhotoWarp.CELLS+1)*(PhotoWarp.CELLS+1)) { documentPoint((it%(PhotoWarp.CELLS+1)).toFloat()/PhotoWarp.CELLS,(it/(PhotoWarp.CELLS+1)).toFloat()/PhotoWarp.CELLS,width,height) }
    return RectF(points.minOf{it.x},points.minOf{it.y},points.maxOf{it.x},points.maxOf{it.y})
}

internal fun Canvas.drawPhoto(bitmap: Bitmap,width: Int,height: Int,transform: ImageTransform,paint: Paint,
    masks: Map<TileCoordinate,Bitmap> = emptyMap(),
    visitMasks: (((TileCoordinate,Bitmap) -> Unit) -> Unit)? = null) {
    fun drawPart(source: Bitmap,left: Float,top: Float,w: Float,h: Float,partPaint: Paint) {
        val cells=if(transform.warp==null) 1 else PhotoWarp.CELLS
        val vertices=FloatArray((cells+1)*(cells+1)*2)
        for(row in 0..cells) for(col in 0..cells) {
            val p=transform.documentPoint((left+w*col/cells)/width,(top+h*row/cells)/height,width,height)
            val index=(row*(cells+1)+col)*2; vertices[index]=p.x; vertices[index+1]=p.y
        }
        drawBitmapMesh(source,cells,cells,vertices,0,null,0,partPaint)
    }
    if(masks.isEmpty() && visitMasks == null) { drawPart(bitmap,0f,0f,width.toFloat(),height.toFloat(),paint); return }
    val save=saveLayer(transform.photoBounds(width,height),paint)
    val inner=Paint(paint).apply { alpha=255; xfermode=null }
    drawPart(bitmap,0f,0f,width.toFloat(),height.toFloat(),inner)
    inner.xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    val drawMask: (TileCoordinate,Bitmap) -> Unit = { xy,tile ->
        if (transform.warp == null) drawPart(tile,xy.x*256f,xy.y*256f,tile.width.toFloat(),tile.height.toFloat(),inner)
        else drawWarpedMask(tile, xy.x*256f, xy.y*256f, width, height, transform, inner)
    }
    masks.forEach { (xy,tile) -> drawMask(xy,tile) }
    visitMasks?.invoke(drawMask)
    restoreToCount(save)
}

/** Clip each source mesh triangle to the sparse mask tile before mapping it. This keeps
 * masks on exactly the same triangles as the photo, even for non-divisible source sizes. */
private fun Canvas.drawWarpedMask(tile: Bitmap, left: Float, top: Float, width: Int, height: Int,
    transform: ImageTransform, paint: Paint) {
    val right = minOf(left + tile.width, width.toFloat())
    val bottom = minOf(top + tile.height, height.toFloat())
    val stepX = width.toFloat() / PhotoWarp.CELLS
    val stepY = height.toFloat() / PhotoWarp.CELLS
    val maskPaint = Paint(paint).apply {
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
    for (row in (top/stepY).toInt().coerceAtLeast(0)..((bottom/stepY).toInt().coerceAtMost(PhotoWarp.CELLS-1))) {
        for (col in (left/stepX).toInt().coerceAtLeast(0)..((right/stepX).toInt().coerceAtMost(PhotoWarp.CELLS-1))) {
            val a = Point(col*stepX,row*stepY); val b = Point((col+1)*stepX,row*stepY)
            val c = Point(col*stepX,(row+1)*stepY); val d = Point((col+1)*stepX,(row+1)*stepY)
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
                        val mapped = transform.documentPoint(vertices[j*2]/width,vertices[j*2+1]/height,width,height)
                        vertices[j*2] = mapped.x; vertices[j*2+1] = mapped.y
                    }
                    drawVertices(Canvas.VertexMode.TRIANGLES,6,vertices,0,texture,0,null,0,null,0,0,maskPaint)
                }
            }
        }
    }
}

internal fun JSONObject.putPhotoTransform(t: ImageTransform): JSONObject = apply {
    put("stretchX",t.stretchX); put("stretchY",t.stretchY)
    t.warp?.let { mesh -> put("warp",JSONArray().apply { mesh.points.forEach { put(it.x);put(it.y) } }) }
}
internal fun JSONObject.readPhotoTransform(base: ImageTransform): ImageTransform {
    val array=optJSONArray("warp")
    val mesh=array?.let {
        require(it.length()==(PhotoWarp.CELLS+1)*(PhotoWarp.CELLS+1)*2) { "Invalid photo mesh" }
        PhotoWarp(List(it.length()/2) { i -> Point(it.getDouble(i*2).toFloat(),it.getDouble(i*2+1).toFloat()) })
    }
    return base.copy(stretchX=optDouble("stretchX",1.0).toFloat(),stretchY=optDouble("stretchY",1.0).toFloat(),warp=mesh)
}
