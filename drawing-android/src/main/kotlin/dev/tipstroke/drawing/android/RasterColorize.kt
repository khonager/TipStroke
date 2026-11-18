package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import dev.tipstroke.core.effects.*
import dev.tipstroke.core.geometry.TileCoordinate
import kotlin.math.*

internal object RasterColorize {
    /** A bounded analysis grid avoids full-document bitmaps; output is native-sized sparse tiles. */
    fun render(lineArt: TileStore, hints: TileStore, coordinates: Set<TileCoordinate>, mask: ColorizeMask,
        cancelled: () -> Boolean): TileStore {
        val step=((max(lineArt.canvasWidth,lineArt.canvasHeight)+1023)/1024).coerceAtLeast(1)
        val width=(lineArt.canvasWidth+step-1)/step;val height=(lineArt.canvasHeight+step-1)/step
        val ink=ByteArray(width*height);val seeds=IntArray(width*height)
        coordinates.forEach { c ->
            check(!cancelled()) { "Cancelled" }
            val lines=lineArt.readPatch(c.x*256,c.y*256,256,256)
            val colors=hints.readPatch(c.x*256,c.y*256,256,256)
            for(i in lines.indices) {
                val x=c.x*256+i%256;val y=c.y*256+i/256
                if(x>=lineArt.canvasWidth || y>=lineArt.canvasHeight) continue
                val cell=(y/step)*width+x/step;val p=lines[i]
                val darkness=(Color.alpha(p)*(1f-(.2126f*Color.red(p)+.7152f*Color.green(p)+.0722f*Color.blue(p))/255)).roundToInt()
                if(darkness>(ink[cell].toInt() and 255)) ink[cell]=darkness.toByte()
                if(Color.alpha(colors[i])>Color.alpha(seeds[cell])) seeds[cell]=colors[i]
            }
        }
        require(seeds.any { it ushr 24 != 0 }) { "Draw colored hints on the hints layer first." }
        val labels=ColorizeSolver.solve(width,height,ink,seeds,mask,cancelled)
        val output=TileStore(lineArt.canvasWidth,lineArt.canvasHeight)
        try {
            for(ty in 0..(lineArt.canvasHeight-1)/256) for(tx in 0..(lineArt.canvasWidth-1)/256) {
                check(!cancelled()) { "Cancelled" }
                val pixels=IntArray(256*256) { i ->
                    val x=tx*256+i%256;val y=ty*256+i/256
                    if(x>=lineArt.canvasWidth || y>=lineArt.canvasHeight) 0 else labels[(y/step)*width+x/step]
                }
                if(pixels.any { it ushr 24 != 0 }) output.putPreparedTile(TileCoordinate(tx,ty),Bitmap.createBitmap(pixels,256,256,Bitmap.Config.ARGB_8888))
            }
            return output
        } catch(error: Throwable) { output.discard();throw error }
    }
}
