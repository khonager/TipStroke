package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.Closeable

/** Worker-only extraction shared by import, edited previews and export. */
internal class VideoFrameDecoder(
    private val source: MediaSource,
    private val createRetriever: () -> MediaMetadataRetriever = { MediaMetadataRetriever() },
    private val diagnostic: (String, Throwable?) -> Unit = LocalDiagnostics::record,
) : Closeable {
    private var retriever: MediaMetadataRetriever? = null
    private var preferUnscaled = false
    private var reportedRecovery = false
    private var closed = false

    fun frame(timeUs: Long, maxEdge: Int): Bitmap {
        check(!closed) { "Video decoder is closed" }
        require(maxEdge > 0)
        val time = timeUs.coerceIn(0, source.durationUs - 1)
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(source.width, source.height))
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val failure by lazy(LazyThreadSafetyMode.NONE) {
            IllegalStateException("Cannot decode video frame at ${time / 1000} ms. " +
                "Try converting the video to H.264 MP4 and importing it again.")
        }
        var metadata = ""
        fun attempt(decode: () -> Bitmap?): Bitmap? = try { decode() }
            catch (error: RuntimeException) { failure.addSuppressed(error); null }

        // Some device codecs cannot produce scaled frames, or stop responding after
        // a seek. Keep timestamp semantics: a sync-frame fallback would shift cuts
        // and paint overlays. Retry on one fresh retriever, never an unbounded loop.
        repeat(2) { pass ->
            val video = try {
                retriever ?: createRetriever().also {
                    retriever = it // Own it even if setDataSource fails.
                    it.setDataSource(source.file.path)
                }
            } catch (error: RuntimeException) {
                failure.addSuppressed(error)
                releaseRetriever()
                return@repeat
            }
            if (!preferUnscaled) {
                attempt { video.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, width, height) }?.let {
                    if (pass > 0) reportRecovery(time, "reopened scaled decoder")
                    return it
                }
            }
            attempt { video.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST) }?.let { full ->
                // Source dimensions are capped by MediaSource. Only one full-size
                // frame exists transiently; do not cache it or upscale small frames.
                val result = fit(full, maxEdge)
                preferUnscaled = true
                reportRecovery(time, "unscaled decoder${if (pass > 0) " after reopen" else ""}")
                return result
            }
            metadata = runCatching {
                "containerMime=${video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)} " +
                    "rotation=${video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)}"
            }.getOrDefault("metadata unavailable")
            releaseRetriever()
            preferUnscaled = false
        }
        diagnostic("video frame decode failed timeUs=$time size=${source.width}x${source.height} " +
            "durationUs=${source.durationUs} $metadata", failure)
        throw failure
    }

    private fun fit(full: Bitmap, maxEdge: Int): Bitmap {
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(full.width, full.height))
        if (scale == 1.0) return full
        var result: Bitmap? = null
        try {
            result = Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1),
                (full.height * scale).toInt().coerceAtLeast(1), true)
            return result
        } finally { if (result !== full) full.recycle() }
    }

    private fun reportRecovery(time: Long, mode: String) {
        if (reportedRecovery) return
        reportedRecovery = true
        diagnostic("video frame decode recovered timeUs=$time size=${source.width}x${source.height} mode=$mode", null)
    }

    private fun releaseRetriever() {
        val old = retriever
        retriever = null
        try { old?.release() }
        catch (error: Exception) { diagnostic("video decoder release failed", error) }
    }

    override fun close() { closed = true; releaseRetriever() }
}
