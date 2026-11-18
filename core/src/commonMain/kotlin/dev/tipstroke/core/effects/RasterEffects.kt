package dev.tipstroke.core.effects

import kotlin.math.*

enum class RasterEffect { GAUSSIAN, MOTION, PERSPECTIVE, BLOOM }
data class RasterEffectSettings(val effect: RasterEffect = RasterEffect.GAUSSIAN, val radius: Int = 8,
    val angle: Float = 0f, val centerX: Float = .5f, val centerY: Float = .5f,
    val threshold: Float = .7f, val intensity: Float = 1f) {
    init { require(radius in 1..32 && angle in -180f..180f && centerX in 0f..1f && centerY in 0f..1f && threshold in 0f..1f && intensity in 0f..3f) }
}

/** Bounded tile+halo processing. Channels stay premultiplied until final encoding. */
object RasterEffects {
    fun apply(pixels: IntArray, width: Int, height: Int, settings: RasterEffectSettings,
        originX: Int = 0, originY: Int = 0, documentWidth: Int = width, documentHeight: Int = height,
        cancelled: () -> Boolean = { false }): IntArray {
        require(pixels.size == width*height)
        val source = FloatArray(pixels.size*4)
        pixels.forEachIndexed { i,c ->
            val a=(c ushr 24)/255f; source[i*4]=a
            for (channel in 1..3) source[i*4+channel]=((c ushr (24-channel*8)) and 255)/255f*a
        }
        val input = if (settings.effect == RasterEffect.BLOOM) source.copyOf().also { bright ->
            for (i in pixels.indices) {
                val a=source[i*4]
                val light=if(a>0) (.2126f*source[i*4+1]+.7152f*source[i*4+2]+.0722f*source[i*4+3])/a else 0f
                val amount=((light-settings.threshold)/(1-settings.threshold).coerceAtLeast(.001f)).coerceIn(0f,1f)
                for(c in 0..3) bright[i*4+c]*=amount
            }
        } else source
        val out=FloatArray(source.size)
        val r=settings.radius
        if (settings.effect == RasterEffect.GAUSSIAN || settings.effect == RasterEffect.BLOOM) {
            val sigma=(r/3f).coerceAtLeast(.5f)
            val kernel=FloatArray(2*r+1) { exp(-((it-r)*(it-r))/(2f*sigma*sigma)) }
            val total=kernel.sum(); for(i in kernel.indices) kernel[i]/=total
            val horizontal=FloatArray(source.size)
            for(y in 0 until height) {
                check(!cancelled()) { "Cancelled" }
                for(x in 0 until width) for(k in -r..r) {
                    if(x+k !in 0 until width) continue
                    val from=(y*width+x+k)*4; val to=(y*width+x)*4; val weight=kernel[k+r]
                    for(c in 0..3) horizontal[to+c]+=input[from+c]*weight
                }
            }
            for(y in 0 until height) {
                check(!cancelled()) { "Cancelled" }
                for(x in 0 until width) for(k in -r..r) {
                    if(y+k !in 0 until height) continue
                    val from=((y+k)*width+x)*4; val to=(y*width+x)*4; val weight=kernel[k+r]
                    for(c in 0..3) out[to+c]+=horizontal[from+c]*weight
                }
            }
        } else {
            val angle=settings.angle*PI.toFloat()/180f
            val diagonal=hypot(documentWidth.toFloat(),documentHeight.toFloat()).coerceAtLeast(1f)
            for(y in 0 until height) {
                check(!cancelled()) { "Cancelled" }
                for(x in 0 until width) {
                    val dx=if(settings.effect==RasterEffect.MOTION) cos(angle) else (originX+x-settings.centerX*documentWidth)/diagonal
                    val dy=if(settings.effect==RasterEffect.MOTION) sin(angle) else (originY+y-settings.centerY*documentHeight)/diagonal
                    for(k in -r..r) {
                        val sx=x+dx*k; val sy=y+dy*k; val ix=floor(sx).toInt(); val iy=floor(sy).toInt()
                        val fx=sx-ix; val fy=sy-iy
                        for(oy in 0..1) for(ox in 0..1) {
                            if(ix+ox !in 0 until width || iy+oy !in 0 until height) continue
                            val weight=(if(ox==0) 1-fx else fx)*(if(oy==0) 1-fy else fy)/(2*r+1)
                            val from=((iy+oy)*width+ix+ox)*4; val to=(y*width+x)*4
                            for(c in 0..3) out[to+c]+=source[from+c]*weight
                        }
                    }
                }
            }
        }
        return IntArray(pixels.size) { i ->
            val o=i*4
            if(settings.effect==RasterEffect.BLOOM) for(c in 0..3) out[o+c]=(source[o+c]+out[o+c]*settings.intensity).coerceAtMost(1f)
            val alpha=out[o].coerceIn(0f,1f)
            if(alpha<.5f/255) 0 else {
                var result=(alpha*255).roundToInt() shl 24
                for(c in 1..3) result=result or ((out[o+c]/alpha*255).roundToInt().coerceIn(0,255) shl (24-c*8))
                result
            }
        }
    }
}
