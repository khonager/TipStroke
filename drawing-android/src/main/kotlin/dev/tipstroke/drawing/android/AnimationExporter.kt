package dev.tipstroke.drawing.android

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import dev.tipstroke.core.model.MediaExportPlan

/** Export runs on the project I/O executor and holds only one rendered frame at a time. */
internal object AnimationExporter {
    fun export(
        resolver: ContentResolver,
        uri: Uri,
        animation: SavedAnimationSnapshot,
        format: AnimationExportFormat,
        mediaPlan: MediaExportPlan? = null,
        onProgress: (Float) -> Unit = {},
        renderFrame: (Int) -> Bitmap,
    ) {
        val timeline = dev.tipstroke.core.model.AnimationTimeline(
            animation.frames, animation.fps, animation.playbackMode,
        )
        require(mediaPlan != null || timeline.totalTicks <= 36_000) { "Animation is too long to export" }
        val ticks = if (mediaPlan != null) {
            require(mediaPlan.frameCount <= 36_000) { "Export supports up to 36,000 output frames. Choose drawing FPS or a shorter source." }
            (0 until mediaPlan.frameCount).toList()
        } else buildList {
            animation.frames.forEachIndexed { index, frame -> repeat(frame.exposure) { add(index) } }
            if (animation.playbackMode == dev.tipstroke.core.model.PlaybackMode.PING_PONG) {
                for (tick in timeline.totalTicks - 2 downTo 1) add(timeline.frameAtTick(tick))
            }
        }
        require(ticks.isNotEmpty() && ticks.size <= 36_000) { "Animation is too long to export" }
        val fps = mediaPlan?.fps ?: animation.fps.toDouble()
        val durationUs = mediaPlan?.durationUs ?: (ticks.size * 1_000_000.0 / fps).toLong()
        when (format) {
            AnimationExportFormat.PNG_SEQUENCE -> resolver.openOutputStream(uri, "wt").use { stream ->
                val output = requireNotNull(stream) { "Cannot open export destination" }
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("timing.json"))
                    zip.write(JSONObject()
                        .put("fps", fps)
                        .put("durationUs", durationUs)
                        .put("playbackMode", animation.playbackMode.name)
                        .put("frameCount", ticks.size).toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    ticks.forEachIndexed { index, frameIndex ->
                        val bitmap = renderFrame(frameIndex)
                        try {
                            zip.putNextEntry(ZipEntry("frame_${(index + 1).toString().padStart(5, '0')}.png"))
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)) { "PNG encoder failed" }
                            zip.closeEntry()
                        } finally { bitmap.recycle() }
                        onProgress((index + 1f) / ticks.size)
                    }
                }
            }
            AnimationExportFormat.GIF -> resolver.openOutputStream(uri, "wt").use { stream ->
                val output = requireNotNull(stream) { "Cannot open export destination" }
                val first = renderFrame(ticks.first())
                try {
                    val gif = GifWriter(output, first.width, first.height,
                        animation.playbackMode != dev.tipstroke.core.model.PlaybackMode.ONCE)
                    var start = 0
                    while (start < ticks.size) {
                        var end = start + 1
                        while (end < ticks.size && ticks[end] == ticks[start] && end - start < 655) end++
                        val bitmap = if (start == 0) first else renderFrame(ticks[start])
                        try {
                            val endCs = if (end == ticks.size) (durationUs / 10_000.0).roundToInt() else (end * 100.0 / fps).roundToInt()
                            gif.writeFrame(bitmap, (endCs - (start * 100.0 / fps).roundToInt()).coerceAtLeast(2))
                        }
                        finally { if (bitmap !== first) bitmap.recycle() }
                        start = end
                        onProgress(end.toFloat() / ticks.size)
                    }
                    gif.finish()
                } finally { first.recycle() }
            }
            AnimationExportFormat.MP4 -> resolver.openFileDescriptor(uri, "rwt").use { descriptor ->
                val file = requireNotNull(descriptor) { "Cannot open export destination" }
                var rendered = 0
                writeMp4(file.fileDescriptor, ticks, fps, durationUs) { index ->
                    onProgress((rendered++).toFloat().div(ticks.size).coerceAtMost(1f))
                    renderFrame(index)
                }
            }
        }
    }

    private fun writeMp4(
        file: java.io.FileDescriptor, ticks: List<Int>, fps: Double, durationUs: Long, renderFrame: (Int) -> Bitmap,
    ) {
        val first = renderFrame(ticks.first())
        val width = (first.width + 1) and -2
        val height = (first.height + 1) and -2
        require(width <= 4096 && height <= 4096) { "MP4 export supports up to 4096 pixels per side" }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * fps / 7L).coerceIn(1_000_000.0, 24_000_000.0).toInt())
            setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = try { configuredAvcEncoder(format) } catch (error: Throwable) { first.recycle(); throw error }
        val muxer = try { MediaMuxer(file, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
            catch (error: Throwable) { codec.release(); first.recycle(); throw error }
        var muxerStarted = false
        var track = -1
        try {
            codec.start()
            val info = MediaCodec.BufferInfo()
            fun drain(waitForEnd: Boolean) {
                var idle = 0
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (waitForEnd) 10_000L else 0L)
                    when (index) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!waitForEnd) return
                            check(++idle < 1_000) { "Video encoder timed out" }
                        }
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted)
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        else -> if (index >= 0) {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxerStarted) { "Video track not ready" }
                                val buffer = requireNotNull(codec.getOutputBuffer(index))
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            val finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                            if (finished) return
                        }
                    }
                }
            }
            ticks.forEachIndexed { tick, frameIndex ->
                val bitmap = if (tick == 0) first else renderFrame(frameIndex)
                try {
                    var input = codec.dequeueInputBuffer(10_000L)
                    var attempts = 0
                    while (input < 0) {
                        drain(false)
                        check(++attempts < 1_000) { "Video encoder has no input buffer" }
                        input = codec.dequeueInputBuffer(10_000L)
                    }
                    val image = requireNotNull(codec.getInputImage(input)) { "Video encoder has no writable image" }
                    writeYuv(bitmap, image, width, height)
                    // queueInputBuffer transfers ownership of the input Image back to the codec.
                    codec.queueInputBuffer(input, 0, width * height * 3 / 2, (tick * 1_000_000.0 / fps).toLong(), 0)
                    drain(false)
                } finally { if (bitmap !== first) bitmap.recycle() }
            }
            var eosInput = codec.dequeueInputBuffer(10_000L)
            var eosAttempts = 0
            while (eosInput < 0) {
                drain(false)
                check(++eosAttempts < 1_000) { "Video encoder did not accept end of stream" }
                eosInput = codec.dequeueInputBuffer(10_000L)
            }
            codec.queueInputBuffer(eosInput, 0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            check(muxerStarted) { "Video encoder produced no track" }
            // Explicit end timestamp gives even a single-frame clip a nonzero duration.
            // https://developer.android.com/reference/android/media/MediaMuxer#writeSampleData(int,java.nio.ByteBuffer,android.media.MediaCodec.BufferInfo)
            val end = MediaCodec.BufferInfo().apply {
                set(0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            muxer.writeSampleData(track, java.nio.ByteBuffer.allocate(0), end)
            // Finalization writes the MP4 index. Never report success if it fails.
            muxer.stop()
            muxerStarted = false
        } finally {
            first.recycle()
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun configuredAvcEncoder(format: MediaFormat): MediaCodec {
        val failure = IllegalStateException("No available MP4 encoder supports this size and frame rate. Try a smaller export size or drawing FPS.")
        val encoders = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        for (info in encoders) {
            val supported = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format) }.getOrDefault(false)
            if (!supported) continue
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createByCodecName(info.name)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (error: Exception) {
                failure.addSuppressed(error)
                runCatching { codec?.release() }
            }
        }
        throw failure
    }

    private fun writeYuv(bitmap: Bitmap, image: android.media.Image, width: Int, height: Int) {
        val planes = image.planes
        require(planes.size == 3) { "Video encoder did not provide YUV planes" }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        fun pixel(x: Int, y: Int): Int = if (x < bitmap.width && y < bitmap.height) pixels[y * bitmap.width + x]
            else android.graphics.Color.WHITE
        fun channel(color: Int, shift: Int) = (color shr shift) and 255
        fun put(plane: android.media.Image.Plane, x: Int, y: Int, value: Int) {
            plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, value.coerceIn(0, 255).toByte())
        }
        for (y in 0 until height) for (x in 0 until width) {
            val color = pixel(x, y)
            val r = channel(color, 16); val g = channel(color, 8); val b = channel(color, 0)
            put(planes[0], x, y, ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
        }
        for (y in 0 until height step 2) for (x in 0 until width step 2) {
            var red = 0; var green = 0; var blue = 0
            for (dy in 0..1) for (dx in 0..1) {
                val color = pixel(x + dx, y + dy)
                red += channel(color, 16); green += channel(color, 8); blue += channel(color, 0)
            }
            val r = red / 4; val g = green / 4; val b = blue / 4
            put(planes[1], x / 2, y / 2, ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
            put(planes[2], x / 2, y / 2, ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
        }
    }

    private class GifWriter(private val output: OutputStream, private val width: Int, private val height: Int, loop: Boolean) {
        init {
            output.write("GIF89a".toByteArray(Charsets.US_ASCII))
            word(width); word(height)
            output.write(byteArrayOf(0xF7.toByte(), 0, 0))
            for (index in 0..255) {
                output.write(((index shr 5) and 7) * 255 / 7)
                output.write(((index shr 2) and 7) * 255 / 7)
                output.write((index and 3) * 255 / 3)
            }
            if (loop) output.write(byteArrayOf(0x21, 0xFF.toByte(), 11, *"NETSCAPE2.0".toByteArray(Charsets.US_ASCII), 3, 1, 0, 0, 0))
        }

        fun writeFrame(bitmap: Bitmap, delayCentiseconds: Int) {
            require(bitmap.width == width && bitmap.height == height)
            output.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0))
            word(delayCentiseconds.coerceIn(2, 65_535))
            output.write(byteArrayOf(0, 0))
            output.write(0x2C)
            word(0); word(0); word(width); word(height); output.write(0)
            output.write(8)
            val colors = IntArray(width * height)
            bitmap.getPixels(colors, 0, width, 0, 0, width, height)
            val indices = ByteArray(colors.size) { index ->
                val pixel = colors[index]
                (((pixel shr 16) and 0xE0) or ((pixel shr 11) and 0x1C) or ((pixel shr 6) and 0x03)).toByte()
            }
            val compressed = lzw(indices)
            var offset = 0
            while (offset < compressed.size) {
                val count = minOf(255, compressed.size - offset)
                output.write(count)
                output.write(compressed, offset, count)
                offset += count
            }
            output.write(0)
        }

        fun finish() { output.write(0x3B) }

        private fun word(value: Int) { output.write(value and 255); output.write((value shr 8) and 255) }

        private fun lzw(indices: ByteArray): ByteArray {
            val bytes = ByteArrayOutputStream()
            val dictionary = HashMap<Int, Int>(4096)
            var bitBuffer = 0
            var bitCount = 0
            var codeSize = 9
            var nextCode = 258
            fun emit(code: Int) {
                bitBuffer = bitBuffer or (code shl bitCount)
                bitCount += codeSize
                while (bitCount >= 8) {
                    bytes.write(bitBuffer and 255)
                    bitBuffer = bitBuffer ushr 8
                    bitCount -= 8
                }
            }
            emit(256)
            if (indices.isNotEmpty()) {
                var prefix = indices[0].toInt() and 255
                for (index in 1 until indices.size) {
                    val symbol = indices[index].toInt() and 255
                    val key = (prefix shl 8) or symbol
                    val existing = dictionary[key]
                    if (existing != null) {
                        prefix = existing
                    } else {
                        emit(prefix)
                        if (nextCode < 4096) {
                            dictionary[key] = nextCode++
                            // The decoder creates each entry one emitted code later than the encoder.
                            if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                        } else {
                            emit(256)
                            dictionary.clear(); codeSize = 9; nextCode = 258
                        }
                        prefix = symbol
                    }
                }
                emit(prefix)
                if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            emit(257)
            if (bitCount > 0) bytes.write(bitBuffer and 255)
            return bytes.toByteArray()
        }
    }
}
