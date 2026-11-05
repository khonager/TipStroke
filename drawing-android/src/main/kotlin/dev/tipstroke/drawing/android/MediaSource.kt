package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** One immutable, project-local encoded asset, never a directory of decoded video frames. */
internal data class MediaSource(
    val file: File,
    val kind: String,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val frameRate: Double = 30.0,
    val canvasScale: Float = 1f,
) {
    init {
        require(canvasScale.isFinite() && canvasScale > 0f && canvasScale <= 1f)
        require(frameRate.isFinite() && frameRate in 1.0..120.0)
        require(kind == "video" || kind == "gif") { "Unsupported media source" }
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) { "Media dimensions are too large" }
        require(durationUs in 1..86_400_000_000L) { "Invalid media duration" }
    }

    fun json() = JSONObject().put("kind", kind).put("width", width).put("height", height).put("durationUs", durationUs).put("frameRate", frameRate).put("canvasScale", canvasScale)

    companion object {
        const val ASSET_PATH = "assets/media/timeline.source"
        /** Read container timing, including fractional rates and microsecond duration. Worker only. */
        fun videoTiming(file: File, durationUs: Long, fallbackFps: Double = 30.0): Pair<Long, Double> {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.path)
                val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else durationUs
                val rate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                    runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                        .getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() } else fallbackFps
                val average = if (duration > 0) fallbackFps * durationUs / duration else fallbackFps
                val cadence = if (kotlin.math.abs(average - rate) < .1) average else rate
                duration.coerceAtLeast(1) to cadence.takeIf { it.isFinite() && it in 1.0..120.0 }.let { it ?: fallbackFps }
            } catch (_: Exception) { durationUs to fallbackFps }
            finally { extractor.release() }
        }
        fun read(directory: File, json: JSONObject): MediaSource {
            val file = File(directory, ASSET_PATH)
            // Early media builds stored this one level higher. Normalize on the project worker
            // so runtime references remain valid after the next atomic project replacement.
            val legacy = File(directory, "assets/timeline.source")
            if (!file.isFile && legacy.isFile) {
                file.parentFile?.mkdirs()
                try { java.nio.file.Files.createLink(file.toPath(), legacy.toPath()) }
                catch (_: Exception) {
                    val staging = File(file.parentFile, "timeline.migrating")
                    try { legacy.copyTo(staging, overwrite = true); check(staging.renameTo(file)) { "Cannot migrate timeline media" } }
                    finally { staging.delete() }
                }
            }
            require(file.isFile) { "Missing timeline media" }
            val kind = json.getString("kind")
            val duration = json.getLong("durationUs")
            val fps = if (json.has("frameRate")) json.getDouble("frameRate")
                else if (kind == "gif") 50.0 else videoTiming(file, duration).second
            return MediaSource(file, kind, json.getInt("width"), json.getInt("height"), duration, fps, json.optDouble("canvasScale", 1.0).toFloat())
        }
    }
}

/** Confined to a worker. Android Movie supplies timestamp seeking and GIF disposal compositing. */
@Suppress("DEPRECATION")
internal class MediaFrameDecoder(private val source: MediaSource) : Closeable {
    private var video: MediaMetadataRetriever? = null
    private var gif: Movie? = null
    init {
        if (source.kind == "video") {
            val retriever = MediaMetadataRetriever()
            try { retriever.setDataSource(source.file.path); video = retriever }
            catch (error: Throwable) { retriever.release(); throw error }
        } else {
            require(source.file.length() <= 32L * 1024 * 1024) { "GIF exceeds the 32 MiB import limit" }
            gif = requireNotNull(Movie.decodeFile(source.file.path)) { "Cannot decode GIF" }
        }
    }

    fun frame(timeUs: Long, maxEdge: Int): Bitmap {
        val time = timeUs.coerceIn(0, source.durationUs - 1)
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(source.width, source.height))
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        video?.let {
            return requireNotNull(it.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, width, height)) {
                "Cannot decode video frame at ${time / 1000} ms"
            }
        }
        val movie = requireNotNull(gif)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.scale(width.toFloat() / movie.width(), height.toFloat() / movie.height())
            // Movie includes the prior frame at its exact end time. Sample inside the
            // requested millisecond so [start, end) matches timeline/export semantics.
            movie.setTime(((time / 1000) + 1).coerceAtMost((source.durationUs / 1000 - 1).coerceAtLeast(0)).toInt())
            movie.draw(canvas, 0f, 0f)
        }
    }

    override fun close() { video?.release(); video = null; gif = null }
}

/** One in-flight seek and one replacement request; fast scrubbing cannot build a decode backlog. */
internal class MediaPreview(private val source: MediaSource) {
    var bitmap: Bitmap? = null
        private set
    var timeUs: Long? = null
        private set
    private var decoder: MediaFrameDecoder? = null // Accessed only on AnimationDecoding.executor.
    private var busy = false
    private var generation = 0
    private var pending: Pair<Long, (Result<Unit>) -> Unit>? = null

    fun prepare(time: Long, complete: (Result<Unit>) -> Unit) {
        if (timeUs == time) { complete(Result.success(Unit)); return }
        pending = time to complete
        if (!busy) decodeNext()
    }

    private fun decodeNext() {
        val request = pending ?: return
        pending = null
        busy = true
        val token = generation
        AnimationDecoding.executor.execute {
            val result = runCatching { (decoder ?: MediaFrameDecoder(source).also { decoder = it }).frame(request.first, 1280) }
            ProjectPersistence.mainHandler.post {
                busy = false
                if (token != generation) { result.getOrNull()?.recycle(); decodeNext(); return@post }
                if (pending != null) result.getOrNull()?.recycle()
                else {
                    result.onSuccess { bitmap?.recycle(); bitmap = it; timeUs = request.first }
                    request.second(result.map { Unit })
                }
                decodeNext()
            }
        }
    }

    fun cancelPending() { generation++; pending = null }

    fun clear() {
        generation++; pending = null; bitmap?.recycle(); bitmap = null; timeUs = null
        AnimationDecoding.executor.execute { decoder?.close(); decoder = null }
    }
}
