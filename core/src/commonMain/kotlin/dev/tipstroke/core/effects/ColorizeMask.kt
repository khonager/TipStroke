package dev.tipstroke.core.effects

import dev.tipstroke.core.model.LayerId
import kotlinx.serialization.json.*

/** Raster output remains authoritative; these stable IDs retain editable generation inputs. */
data class ColorizeMask(val lineArtId: LayerId, val hintsId: LayerId, val gap: Int = 1,
    val threshold: Float = .2f, val transparentColor: Int = 0xffff00ff.toInt()) {
    init { require(lineArtId != hintsId && gap in 0..4 && threshold in .02f..1f) }
}
object ColorizeMaskCodec {
    fun encode(mask: ColorizeMask): String = buildJsonObject {
        put("version",1);put("lineArtId",mask.lineArtId.value);put("hintsId",mask.hintsId.value)
        put("gap",mask.gap);put("threshold",mask.threshold);put("transparentColor",mask.transparentColor)
    }.toString()
    fun decode(text: String): ColorizeMask {
        val json=Json.parseToJsonElement(text).jsonObject
        require(json.getValue("version").jsonPrimitive.int==1) { "Unsupported colorize mask version" }
        return ColorizeMask(LayerId(json.getValue("lineArtId").jsonPrimitive.content),LayerId(json.getValue("hintsId").jsonPrimitive.content),
            json.getValue("gap").jsonPrimitive.int,json.getValue("threshold").jsonPrimitive.float,json.getValue("transparentColor").jsonPrimitive.int)
    }
}

/** Multi-source region propagation with closed ink barriers and explicit transparent seeds. */
object ColorizeSolver {
    fun solve(width: Int, height: Int, ink: ByteArray, hints: IntArray, mask: ColorizeMask,
        cancelled: () -> Boolean = { false }): IntArray {
        require(width in 1..1024 && height in 1..1024 && ink.size==width*height && hints.size==ink.size)
        var blocked=BooleanArray(ink.size) { (ink[it].toInt() and 255)>=mask.threshold*255 }
        val r=mask.gap
        if(r>0) {
            val dilated=BooleanArray(ink.size)
            for(y in 0 until height) { check(!cancelled()) { "Cancelled" }; for(x in 0 until width) {
                loop@ for(dy in -r..r) for(dx in -r..r) {
                    if(x+dx in 0 until width && y+dy in 0 until height && blocked[(y+dy)*width+x+dx]) { dilated[y*width+x]=true;break@loop }
                }
            } }
            blocked=BooleanArray(ink.size) { true }
            for(y in 0 until height) { check(!cancelled()) { "Cancelled" }; for(x in 0 until width) {
                loop@ for(dy in -r..r) for(dx in -r..r) {
                    if(x+dx in 0 until width && y+dy in 0 until height && !dilated[(y+dy)*width+x+dx]) { blocked[y*width+x]=false;break@loop }
                }
            } }
        }
        val labels=IntArray(ink.size); val visited=BooleanArray(ink.size); val queue=IntArray(ink.size)
        var head=0;var tail=0
        for(i in hints.indices) if(hints[i] ushr 24 != 0 && !blocked[i]) {
            visited[i]=true;labels[i]=hints[i] or 0xff000000.toInt();queue[tail++]=i
        }
        while(head<tail) {
            if(head%4096==0) check(!cancelled()) { "Cancelled" }
            val i=queue[head++];val x=i%width;val y=i/width
            fun add(next: Int) { if(!visited[next] && !blocked[next]) { visited[next]=true;labels[next]=labels[i];queue[tail++]=next } }
            if(x>0)add(i-1);if(x+1<width)add(i+1);if(y>0)add(i-width);if(y+1<height)add(i+width)
        }
        val transparent=mask.transparentColor or 0xff000000.toInt()
        for(i in labels.indices) if(labels[i]==transparent) labels[i]=0
        return labels
    }
}
