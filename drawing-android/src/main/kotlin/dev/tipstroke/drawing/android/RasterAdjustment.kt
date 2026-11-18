package dev.tipstroke.drawing.android

import android.graphics.*
import dev.tipstroke.core.effects.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.geometry.TileGrid

internal class RasterAdjustmentPreview(val layer: RasterLayerRuntime, val revision: Long,
    val output: TileStore, val dirty: Set<TileCoordinate>,
    val hiddenLayers: Set<dev.tipstroke.core.model.LayerId> = emptySet(),
    val valid: () -> Boolean = { true }, val committed: () -> Unit = {}) {
    fun draw(canvas: Canvas, paint: Paint) { layer.tiles.drawExcept(canvas,paint,dirty); output.draw(canvas,paint) }
    fun discard() = output.discard()
}

internal object RasterAdjustment {
    fun render(source: TileStore, coordinates: Set<TileCoordinate>, settings: RasterEffectSettings,
        selection: SelectionRegion?, cancelled: () -> Boolean): Pair<TileStore,Set<TileCoordinate>> {
        val halo=settings.radius+2
        val dirty=coordinates.flatMapTo(mutableSetOf()) { c ->
            TileGrid.intersecting(dev.tipstroke.core.geometry.Rect(c.x*256f,c.y*256f,(c.x+1)*256f,(c.y+1)*256f).expanded(halo.toFloat()),
                source.canvasWidth,source.canvasHeight)
        }.filterTo(mutableSetOf()) { c -> selection==null || c in TileGrid.intersecting(selection.bounds,source.canvasWidth,source.canvasHeight) }
        val output=TileStore(source.canvasWidth,source.canvasHeight)
        try {
            dirty.forEach { c ->
                check(!cancelled()) { "Cancelled" }
                val width=256+halo*2; val left=c.x*256-halo; val top=c.y*256-halo
                val pixels=source.readPatch(left,top,width,width)
                val filtered=RasterEffects.apply(pixels,width,width,settings,left,top,source.canvasWidth,source.canvasHeight,cancelled)
                val tilePixels=IntArray(256*256) { i ->
                    val x=c.x*256+i%256; val y=c.y*256+i/256; val p=(i/256+halo)*width+i%256+halo
                    if(x>=source.canvasWidth || y>=source.canvasHeight) 0
                    else if(selection!=null && !selection.contains(Point(x+.5f,y+.5f))) pixels[p] else filtered[p]
                }
                if(tilePixels.any { it ushr 24 != 0 }) {
                    val bitmap=Bitmap.createBitmap(tilePixels,256,256,Bitmap.Config.ARGB_8888)
                    output.putPreparedTile(c,bitmap)
                }
            }
            return output to dirty
        } catch(error: Throwable) { output.discard(); throw error }
    }
}
