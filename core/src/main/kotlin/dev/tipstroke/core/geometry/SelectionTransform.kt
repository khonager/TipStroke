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
    val translation: Point = Point(0f, 0f),
    val warp: dev.tipstroke.core.model.PhotoWarp? = null,
    val sourceBounds: Rect? = null,
) {
    init {
        require(pivot.x.isFinite() && pivot.y.isFinite())
        require(scaleX.isFinite() && abs(scaleX) > 0f && abs(scaleX) <= 8f)
        require(scaleY.isFinite() && abs(scaleY) > 0f && abs(scaleY) <= 8f)
        require(rotationDegrees.isFinite())
        require(translation.x.isFinite() && translation.y.isFinite())
        require(warp == null || (sourceBounds != null && sourceBounds.right > sourceBounds.left && sourceBounds.bottom > sourceBounds.top))
    }
    private val radians = Math.toRadians(rotationDegrees.toDouble())
    val a: Float = (cos(radians) * scaleX).toFloat()
    val b: Float = (-sin(radians) * scaleY).toFloat()
    val c: Float = (sin(radians) * scaleX).toFloat()
    val d: Float = (cos(radians) * scaleY).toFloat()
    val offsetX: Float = translation.x + pivot.x - a * pivot.x - b * pivot.y
    val offsetY: Float = translation.y + pivot.y - c * pivot.x - d * pivot.y
    fun map(point: Point): Point {
        val bounds = sourceBounds
        val p = if (warp != null && bounds != null) {
            val w = bounds.right - bounds.left; val h = bounds.bottom - bounds.top
            val normalized = warp.map((point.x - bounds.left) / w, (point.y - bounds.top) / h)
            Point(bounds.left + normalized.x * w, bounds.top + normalized.y * h)
        } else point
        return Point(a * p.x + b * p.y + offsetX, c * p.x + d * p.y + offsetY)
    }
    val isIdentity: Boolean get() = warp == null && translation == Point(0f, 0f) && abs(a - 1f) < .00001f && abs(d - 1f) < .00001f && abs(b) < .00001f && abs(c) < .00001f
}

fun SelectionRegion.transformed(transform: SelectionTransform): SelectionRegion {
    val bounds=transform.sourceBounds
    val mapped = if (transform.warp == null || bounds == null) points.map(transform::map) else points.flatMapIndexed { i, p ->
        val next=points[(i+1)%points.size]
        val w=bounds.right-bounds.left; val h=bounds.bottom-bounds.top
        val u=(p.x-bounds.left)/w; val v=(p.y-bounds.top)/h
        val du=(next.x-p.x)/w; val dv=(next.y-p.y)/h
        val cuts=mutableSetOf(0f)
        fun cut(start: Float, delta: Float, maximum: Int) {
            if (abs(delta)<1e-8f) return
            for (line in 0..maximum) {
                val t=(line/16f-start)/delta
                if(t>0f && t<1f) cuts+=t
            }
        }
        cut(u,du,16); cut(v,dv,16); cut(u+v,du+dv,32)
        cuts.sorted().map { t -> transform.map(Point(p.x+(next.x-p.x)*t,p.y+(next.y-p.y)*t)) }
    }
    // Keep repeated edits bounded by removing subdivisions on straight segments.
    val simplified=mapped.filterIndexed { index, p ->
        val before=mapped[(index+mapped.size-1)%mapped.size]; val after=mapped[(index+1)%mapped.size]
        abs((p.x-before.x)*(after.y-p.y)-(p.y-before.y)*(after.x-p.x))>1e-5f
    }.takeIf { it.size>=3 } ?: mapped
    return SelectionRegion(simplified,left?.transformed(transform),right?.transformed(transform),operation)
}
