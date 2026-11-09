package dev.tipstroke.drawing.android

import android.graphics.*
import android.hardware.HardwareBuffer
import androidx.annotation.RequiresApi
import dev.tipstroke.core.geometry.TileCoordinate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A bounded atlas of sparse tiles: one GPU submission/readback per batch, not per tile. */
@RequiresApi(34)
internal class GpuTileRasterizer(private val edge: Int = 1024) : AutoCloseable {
    private val colorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    private val buffer = HardwareBuffer.create(edge, edge, HardwareBuffer.RGBA_8888, 1,
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
    private val node = RenderNode("Stroke tile atlas").apply { setPosition(0, 0, edge, edge) }
    private val hardware = HardwareBufferRenderer(buffer).apply { setContentRoot(node) }

    fun rasterize(coordinates: Collection<TileCoordinate>, tileSize: Int = 256,
        draw: (Canvas) -> Unit, accept: (TileCoordinate, Bitmap, Rect) -> Unit) {
        require(tileSize in 1..edge)
        val columns = edge / tileSize
        val spatial = edge > 1024
        val batches = if (spatial) coordinates.groupBy { TileCoordinate(it.x / columns, it.y / columns) }.values
            else coordinates.chunked(columns * columns)
        for (batch in batches) {
            val originX = batch.first().x / columns * columns * tileSize
            val originY = batch.first().y / columns * columns * tileSize
            val recording = node.beginRecording()
            recording.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (spatial) {
                recording.translate(-originX.toFloat(), -originY.toFloat())
                draw(recording)
            } else batch.forEachIndexed { index, tile ->
                val x = index % columns * tileSize; val y = index / columns * tileSize
                val saved = recording.save()
                recording.clipRect(x, y, x + tileSize, y + tileSize)
                recording.translate((x - tile.x * tileSize).toFloat(), (y - tile.y * tileSize).toFloat())
                draw(recording)
                recording.restoreToCount(saved)
            }
            node.endRecording()
            val completion = CompletableFuture<HardwareBufferRenderer.RenderResult>()
            hardware.obtainRenderRequest().setColorSpace(colorSpace).draw({ it.run() }) { completion.complete(it) }
            val result = completion.get(5, TimeUnit.SECONDS)
            result.fence.use { fence ->
                check(result.status == HardwareBufferRenderer.RenderResult.SUCCESS && fence.await(Duration.ofSeconds(5))) {
                    "Stroke tile rendering failed."
                }
            }
            val gpu = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, colorSpace))
            val pixels = try { requireNotNull(gpu.copy(Bitmap.Config.ARGB_8888, false)) } finally { gpu.recycle() }
            try {
                batch.forEachIndexed { index, tile ->
                    val x = if (spatial) tile.x * tileSize - originX else index % columns * tileSize
                    val y = if (spatial) tile.y * tileSize - originY else index / columns * tileSize
                    accept(tile, pixels, Rect(x, y, x + tileSize, y + tileSize))
                }
            } finally { pixels.recycle() }
        }
    }
    override fun close() { hardware.close(); node.discardDisplayList(); buffer.close() }
}
