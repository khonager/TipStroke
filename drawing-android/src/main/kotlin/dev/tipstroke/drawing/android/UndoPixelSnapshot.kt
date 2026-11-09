package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import dev.tipstroke.core.geometry.TileCoordinate
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Lossless, process-local undo pixels. Large edits don't retain two full raster copies. */
internal class UndoPixelSnapshot(pixels: Map<TileCoordinate, Bitmap?>) {
    private class Packed(val bytes: ByteArray, val width: Int, val height: Int, val byteCount: Int)
    private val tiles: Map<TileCoordinate, Any?>
    val coordinates: Set<TileCoordinate> get() = tiles.keys
    val estimatedBytes: Long

    init {
        val compress = pixels.values.sumOf { it?.allocationByteCount?.toLong() ?: 0L } > 8L * 1024 * 1024
        val deflater = if (compress) Deflater(Deflater.BEST_SPEED) else null
        try {
            tiles = pixels.mapValues { (_, bitmap) ->
                if (bitmap == null || deflater == null) bitmap else {
                    val raw = ByteBuffer.allocate(bitmap.byteCount)
                    bitmap.copyPixelsToBuffer(raw)
                    deflater.reset(); deflater.setInput(raw.array()); deflater.finish()
                    val output = ByteArrayOutputStream()
                    val block = ByteArray(8192)
                    while (!deflater.finished()) output.write(block, 0, deflater.deflate(block))
                    val bytes = output.toByteArray()
                    // Keep incompressible tiles directly; compression must not grow history.
                    if (bytes.size < bitmap.allocationByteCount / 2) Packed(bytes, bitmap.width, bitmap.height, bitmap.byteCount) else bitmap
                }
            }
        } finally { deflater?.end() }
        estimatedBytes = tiles.size * 16L + tiles.values.sumOf { value -> when (value) {
            is Bitmap -> value.allocationByteCount.toLong()
            is Packed -> value.bytes.size.toLong()
            else -> 0L
        } }
    }

    fun withDecoded(use: (Map<TileCoordinate, Bitmap?>) -> Unit) {
        val inflater = Inflater()
        try {
            val decoded = tiles.mapValues { (_, value) -> when (value) {
                is Bitmap -> value
                is Packed -> {
                    val raw = ByteArray(value.byteCount)
                    inflater.reset(); inflater.setInput(value.bytes)
                    var offset = 0
                    while (!inflater.finished() && offset < raw.size) {
                        val count = inflater.inflate(raw, offset, raw.size - offset)
                        check(count > 0) { "Incomplete undo pixels" }
                        offset += count
                    }
                    check(offset == raw.size && inflater.finished()) { "Invalid undo pixels" }
                    Bitmap.createBitmap(value.width, value.height, Bitmap.Config.ARGB_8888).apply {
                        check(byteCount == value.byteCount) { "Undo pixel stride changed" }
                        copyPixelsFromBuffer(ByteBuffer.wrap(raw))
                    }
                }
                else -> null
            } }
            // A process recorder may retain decoded immutable snapshots after this call.
            // Let their Bitmap references own the lifetime; never recycle under its worker.
            use(decoded)
        } finally { inflater.end() }
    }
}
