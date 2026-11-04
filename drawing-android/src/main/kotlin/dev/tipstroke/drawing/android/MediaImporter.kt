package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import dev.tipstroke.core.model.*
import java.io.File
import java.util.UUID

/** Import is transactional and runs on the same serial worker as saves and project transfer. */
object MediaImporter {
    fun import(context: Context, library: DrawingLibrary, uri: Uri, complete: (Result<DrawingSummary>) -> Unit) {
        ProjectPersistence.executor.execute {
            val result = runCatching { importNow(context, library, uri) }
            ProjectPersistence.mainHandler.post { complete(result) }
        }
    }

    @Suppress("DEPRECATION")
    internal fun importNow(context: Context, library: DrawingLibrary, uri: Uri): DrawingSummary {
        val resolver = context.contentResolver
        val staging = File(context.cacheDir, "media-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(staging, "source")
        try {
            requireNotNull(resolver.openInputStream(uri)) { "Cannot open media" }.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "Media exceeds the 512 MiB import limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }?.substringBeforeLast('.')?.take(120)?.ifBlank { null } ?: "Imported media"
            val header = file.inputStream().use { input -> ByteArray(6).also { input.read(it) } }.toString(Charsets.US_ASCII)
            val isGif = header == "GIF87a" || header == "GIF89a"
            val imageInfo = runCatching {
                var dimensions: Pair<Int, Int>? = null
                // Read oriented dimensions without allocating the original image.
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    dimensions = info.size.width to info.size.height
                    decoder.setTargetSize(1, 1)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.recycle()
                requireNotNull(dimensions)
            }.getOrNull()
            val paintId = LayerId(UUID.randomUUID().toString())
            val paint = SavedRasterSnapshot(paintId, "Paint 1", true, 1f, emptyMap())
            val snapshot: DrawingSnapshot
            if (imageInfo != null && !isGif) {
                val (width, height) = imageInfo
                require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) { "Image exceeds 8192 pixels per side or 16 megapixels" }
                val imageId = LayerId(UUID.randomUUID().toString())
                // Normalize EXIF orientation once; all existing image rendering paths then agree.
                val normalized = File(staging, "image.source")
                val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
                try { normalized.outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { image.recycle() }
                snapshot = DrawingSnapshot(width, height, paintId, listOf(
                    SavedImageSnapshot(imageId, name, true, 1f, Uri.fromFile(normalized), width, height, ImageTransform(width / 2f, height / 2f)), paint,
                ))
            } else {
                var source: MediaSource
                var frameRate: Double
                if (isGif) {
                    require(file.length() <= 32L * 1024 * 1024) { "GIF exceeds the 32 MiB import limit" }
                    requireNotNull(imageInfo) { "Cannot read GIF dimensions" }
                    require(imageInfo.first.toLong() * imageInfo.second <= 16_777_216) { "GIF exceeds 16 megapixels" }
                    val movie = requireNotNull(Movie.decodeFile(file.path)) { "Cannot decode GIF" }
                    source = MediaSource(file, "gif", movie.width(), movie.height(), movie.duration().coerceAtLeast(20) * 1000L)
                    frameRate = 50.0
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.path)
                        fun metadata(key: Int) = retriever.extractMetadata(key)
                        val rotation = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        val width = requireNotNull(metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()) { "Unsupported image or video" }
                        val height = requireNotNull(metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()) { "Unsupported video" }
                        val rotated = rotation.mod(180) == 90
                        source = MediaSource(file, "video", if (rotated) height else width, if (rotated) width else height,
                            requireNotNull(metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()) { "Video has no duration" } * 1000L)
                        frameRate = (metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                            ?.takeIf { it > 0 }?.let { it * 1_000_000.0 / source.durationUs })
                            ?.coerceIn(1.0, 120.0) ?: 30.0
                    } finally { retriever.release() }
                }
                if (source.kind == "video") {
                    val timing = MediaSource.videoTiming(file, source.durationUs, frameRate)
                    source = source.copy(durationUs = timing.first)
                    frameRate = timing.second
                }
                source = source.copy(frameRate = frameRate)
                MediaFrameDecoder(source).use { it.frame(0, 1280).recycle() }
                val frames = listOf(AnimationFrame(FrameId.new(), sourceTimeUs = 0, durationUs = 1_000_000L / 12))
                val animation = SavedAnimationSnapshot(frames, 0, 12, PlaybackMode.LOOP, 1, 1, .3f, emptySet(), emptyMap(), emptyMap(), media = source)
                snapshot = DrawingSnapshot(source.width, source.height, paintId, listOf(paint), animation = animation)
            }
            try { return ProjectPersistence.save(resolver, library, library.newId(), name, snapshot).getOrThrow() }
            finally { snapshot.recycle() }
        } finally { staging.deleteRecursively() }
    }
}
