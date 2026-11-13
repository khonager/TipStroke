package dev.tipstroke.core.model

import dev.tipstroke.core.geometry.Point
import kotlin.math.*

enum class PhotoEditTool { MOVE, STRETCH, DISTORT, WARP, PUSH, BULGE, PINCH }

/** Fixed-size normalized mesh; independent of source resolution and Android. */
data class PhotoWarp(val points: List<Point>) {
    init { require(points.size == (CELLS + 1) * (CELLS + 1) && points.all { it.x.isFinite() && it.y.isFinite() }) }

    fun map(u: Float, v: Float): Point {
        val x = u.coerceIn(0f, 1f) * CELLS
        val y = v.coerceIn(0f, 1f) * CELLS
        val col = x.toInt().coerceAtMost(CELLS-1); val row = y.toInt().coerceAtMost(CELLS-1); val a = x - col; val b = y - row
        val p = points[row * (CELLS + 1) + col]
        val q = points[row * (CELLS + 1) + col + 1]
        val r = points[(row + 1) * (CELLS + 1) + col]
        val s = points[(row + 1) * (CELLS + 1) + col + 1]
        return if (a + b <= 1f) Point(p.x + a*(q.x-p.x)+b*(r.x-p.x), p.y+a*(q.y-p.y)+b*(r.y-p.y))
        else Point(s.x+(1-b)*(q.x-s.x)+(1-a)*(r.x-s.x), s.y+(1-b)*(q.y-s.y)+(1-a)*(r.y-s.y))
    }

    fun inverse(x: Float, y: Float): Point? {
        fun triangle(first: Int, second: Int, third: Int): Point? {
            val a = points[first]; val b = points[second]; val c = points[third]
            if (x < minOf(a.x,b.x,c.x) || x > maxOf(a.x,b.x,c.x) ||
                y < minOf(a.y,b.y,c.y) || y > maxOf(a.y,b.y,c.y)) return null
            val determinant = (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
            if (abs(determinant) < 1e-10f) return null
            val u = ((x-a.x)*(c.y-a.y)-(y-a.y)*(c.x-a.x))/determinant
            val v = ((b.x-a.x)*(y-a.y)-(b.y-a.y)*(x-a.x))/determinant
            if (u < -1e-5f || v < -1e-5f || u+v > 1.00001f) return null
            fun column(index: Int) = (index%(CELLS+1)).toFloat()/CELLS
            fun row(index: Int) = (index/(CELLS+1)).toFloat()/CELLS
            return Point(column(first)+u*(column(second)-column(first))+v*(column(third)-column(first)),
                row(first)+u*(row(second)-row(first))+v*(row(third)-row(first)))
        }
        for (row in 0 until CELLS) for (col in 0 until CELLS) {
            val i = row*(CELLS+1)+col
            triangle(i,i+1,i+CELLS+1)?.let { return it }
            triangle(i+CELLS+2,i+CELLS+1,i+1)?.let { return it }
        }
        return null
    }

    fun deform(tool: PhotoEditTool, center: Point, delta: Point, radius: Float, aspect: Float): PhotoWarp {
        require(radius > 0f && aspect > 0f)
        val candidate = PhotoWarp(points.map { p ->
            val dx=(p.x-center.x)*aspect; val dy=p.y-center.y
            val distance=hypot(dx,dy)/radius
            val weight=(1f-distance*distance).coerceAtLeast(0f).pow(2)
            when(tool) {
                PhotoEditTool.PUSH -> Point(p.x+delta.x*weight,p.y+delta.y*weight)
                PhotoEditTool.BULGE, PhotoEditTool.PINCH -> {
                    val factor=1f+(if(tool==PhotoEditTool.BULGE) 1f else -1f)*hypot(delta.x*aspect,delta.y).coerceAtMost(.1f)*4f*weight
                    Point(center.x+(p.x-center.x)*factor,center.y+(p.y-center.y)*factor)
                }
                else -> p
            }
        })
        // Refuse foldovers so inverse mapping, erasing and compositing stay unambiguous.
        return if (candidate.valid()) candidate else this
    }

    /** Move a control point on a coarse lattice while retaining the fixed rendering mesh. */
    fun moveControl(column: Int, row: Int, divisions: Int, delta: Point): PhotoWarp {
        require(divisions in listOf(1, 2, 4, 8) && column in 0..divisions && row in 0..divisions)
        val candidate = PhotoWarp(points.mapIndexed { index, p ->
            val u = (index % (CELLS + 1)).toFloat() / CELLS * divisions
            val v = (index / (CELLS + 1)).toFloat() / CELLS * divisions
            val weight = (1f - abs(u - column)).coerceAtLeast(0f) * (1f - abs(v - row)).coerceAtLeast(0f)
            Point(p.x + delta.x * weight, p.y + delta.y * weight)
        })
        return if (candidate.valid()) candidate else this
    }

    /** Perspective corner edit, retaining any existing local deformation. */
    fun distortCorner(column: Int, row: Int, delta: Point): PhotoWarp {
        require(column in 0..1 && row in 0..1)
        val corners=listOf(map(0f,0f),map(1f,0f),map(1f,1f),map(0f,1f))
        val index=if(row==0) column else 3-column
        val changed=corners.mapIndexed { i,p -> if(i==index) Point(p.x+delta.x,p.y+delta.y) else p }
        fun project(quad: List<Point>, u: Float, v: Float): Point? {
            val (a,b,c,d)=quad
            val dx1=b.x-c.x; val dx2=d.x-c.x; val sx=a.x-b.x+c.x-d.x
            val dy1=b.y-c.y; val dy2=d.y-c.y; val sy=a.y-b.y+c.y-d.y
            val det=dx1*dy2-dx2*dy1
            if(abs(det)<1e-8f) return null
            val g=(sx*dy2-dx2*sy)/det; val h=(dx1*sy-sx*dy1)/det
            val denominator=g*u+h*v+1f
            if(denominator<=1e-6f) return null
            return Point(((b.x-a.x+g*b.x)*u+(d.x-a.x+h*d.x)*v+a.x)/denominator,
                ((b.y-a.y+g*b.y)*u+(d.y-a.y+h*d.y)*v+a.y)/denominator)
        }
        val candidate=PhotoWarp(points.mapIndexed { i,p ->
            val u=(i%(CELLS+1)).toFloat()/CELLS; val v=(i/(CELLS+1)).toFloat()/CELLS
            val before=project(corners,u,v) ?: return this
            val after=project(changed,u,v) ?: return this
            Point(p.x+after.x-before.x,p.y+after.y-before.y)
        })
        return if(candidate.valid()) candidate else this
    }

    private fun valid(): Boolean {
        fun area(a: Point,b: Point,c: Point)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
        for(row in 0 until CELLS) for(col in 0 until CELLS) {
            val i=row*(CELLS+1)+col
            if(area(points[i],points[i+1],points[i+CELLS+1])<=1e-7f ||
                area(points[i+CELLS+2],points[i+CELLS+1],points[i+1])<=1e-7f) return false
        }
        return true
    }

    companion object {
        const val CELLS=16
        fun identity()=PhotoWarp(List((CELLS+1)*(CELLS+1)) { Point((it%(CELLS+1)).toFloat()/CELLS,(it/(CELLS+1)).toFloat()/CELLS) })
    }
}
