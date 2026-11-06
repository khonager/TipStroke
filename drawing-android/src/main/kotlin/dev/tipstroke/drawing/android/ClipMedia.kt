package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import dev.tipstroke.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID

internal fun VideoEdit.json() = JSONArray().apply { clips.forEach { clip ->
    put(JSONObject().put("id", clip.id).put("assetId", clip.assetId).put("name", clip.name)
        .put("sourceInUs", clip.sourceInUs).put("durationUs", clip.durationUs).put("transitionUs", clip.transitionUs)
        .put("keyframes", JSONArray().apply { clip.keyframes.forEach { key ->
            put(JSONObject().put("timeUs", key.timeUs).put("easing", key.easing.name)
                .put("x", key.pose.x).put("y", key.pose.y).put("scale", key.pose.scale)
                .put("rotation", key.pose.rotation).put("opacity", key.pose.opacity))
        } }))
} }

internal fun readVideoEdit(array: JSONArray): VideoEdit {
    require(array.length() in 1..1000)
    return VideoEdit((0 until array.length()).map { index ->
        val clip = array.getJSONObject(index)
        val keys = clip.getJSONArray("keyframes")
        require(keys.length() <= 1000)
        MediaClip(clip.getString("id"), clip.getString("assetId"), clip.getString("name"), clip.getLong("sourceInUs"),
            clip.getLong("durationUs"), (0 until keys.length()).map { k ->
                val key = keys.getJSONObject(k)
                ClipKeyframe(key.getLong("timeUs"), ClipPose(key.getDouble("x").toFloat(), key.getDouble("y").toFloat(),
                    key.getDouble("scale").toFloat(), key.getDouble("rotation").toFloat(), key.getDouble("opacity").toFloat()),
                    ClipEasing.valueOf(key.getString("easing")))
            }, clip.getLong("transitionUs"))
    })
}

internal fun decodeClipImage(file: File, maxEdge: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
    val scale = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
}

/** Worker-only compositor. At most two decoders and one cached photo per active clip. */
internal class ClipFrameDecoder(private val source: MediaSource) : Closeable {
    private val decoders = linkedMapOf<String, MediaFrameDecoder>()
    private val photos = linkedMapOf<String, Bitmap>()
    private var photoEdge = 0
    fun frame(timeUs: Long, maxEdge: Int): Bitmap {
        val edit = requireNotNull(source.edit)
        val time = timeUs.coerceIn(0, edit.durationUs - 1)
        val index = edit.indexAt(time)
        val incoming = edit.clips[index]
        val local = time - edit.startUs(index)
        val blending = index > 0 && local < incoming.transitionUs
        val visible = if (blending) listOf(index - 1, index) else listOf(index)
        val ids = visible.map { edit.clips[it].assetId }.toSet()
        decoders.keys.toList().filter { it !in ids }.forEach { decoders.remove(it)?.close() }
        photos.keys.toList().filter { it !in ids || photoEdge != maxEdge }.forEach { photos.remove(it)?.recycle() }
        photoEdge = maxEdge
        val ratio = minOf(1f, maxEdge.toFloat() / maxOf(source.width, source.height))
        val output = Bitmap.createBitmap((source.width * ratio).toInt().coerceAtLeast(1), (source.height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            visible.forEach { i ->
                val clip = edit.clips[i]
                val asset = source.assets.getValue(clip.assetId)
                val clipTime = time - edit.startUs(i)
                val bitmap = if (asset.kind == "image") photos.getOrPut(clip.assetId) { decodeClipImage(asset.file, maxEdge) }
                    else decoders.getOrPut(clip.assetId) { MediaFrameDecoder(asset) }.frame(clip.sourceInUs + clipTime, maxEdge)
                try {
                    val pose = clip.poseAt(clipTime)
                    val mix = if (blending) local.toFloat() / incoming.transitionUs else 1f
                    val alpha = if (!blending) 1f else if (i == index) mix else 1f - mix
                    val fit = minOf(output.width.toFloat() / bitmap.width, output.height.toFloat() / bitmap.height) * pose.scale
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        this.alpha = (255 * pose.opacity * alpha).toInt().coerceIn(0, 255)
                        if (blending && i == index) xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                    }
                    canvas.save()
                    canvas.translate(output.width * (.5f + pose.x), output.height * (.5f + pose.y))
                    canvas.rotate(pose.rotation); canvas.scale(fit, fit)
                    canvas.drawBitmap(bitmap, -bitmap.width / 2f, -bitmap.height / 2f, paint)
                    canvas.restore()
                } finally { if (asset.kind != "image") bitmap.recycle() }
            }
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
    }
    override fun close() { decoders.values.forEach { it.close() }; decoders.clear(); photos.values.forEach { it.recycle() }; photos.clear() }
}

/** Copies into private working storage before publishing. No persisted URI dependency. */
internal fun importClip(context: Context, uri: Uri): Pair<String, MediaSource> {
    val directory = ClipWorkingStorage.directory(context.filesDir)
    val file = File(directory, "${UUID.randomUUID()}.source")
    try {
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                total += count; require(total <= 512L * 1024 * 1024) { "Media exceeds the 512 MiB import limit" }
                output.write(buffer, 0, count)
            }
        } }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.take(120) ?: "Media"
        var dimensions: Pair<Int, Int>? = null
        runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            dimensions = info.size.width to info.size.height; decoder.setTargetSize(1, 1); decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.recycle() }
        val source = if (dimensions != null) {
            val (w, h) = requireNotNull(dimensions)
            require(w in 1..8192 && h in 1..8192 && w.toLong() * h <= 16_777_216) { "Image exceeds 8192 pixels per side or 16 megapixels" }
            val header = file.inputStream().use { input -> ByteArray(6).also { input.read(it) } }.toString(Charsets.US_ASCII)
            if (header.startsWith("GIF8")) {
                require(file.length() <= 32L * 1024 * 1024)
                @Suppress("DEPRECATION") val movie = requireNotNull(Movie.decodeFile(file.path))
                MediaSource(file, "gif", w, h, movie.duration().coerceAtLeast(20) * 1000L, 50.0)
            } else MediaSource(file, "image", w, h, 5_000_000)
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                fun number(key: Int) = requireNotNull(retriever.extractMetadata(key)?.toLongOrNull()) { "Unsupported video" }
                val rotated = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0).mod(180) == 90
                val w = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt(); val h = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                val timing = MediaSource.videoTiming(file, number(MediaMetadataRetriever.METADATA_KEY_DURATION) * 1000)
                MediaSource(file, "video", if (rotated) h else w, if (rotated) w else h, timing.first, timing.second)
            } finally { retriever.release() }
        }
        MediaFrameDecoder(source).use { it.frame(0, 1280).recycle() }
        ClipWorkingStorage.retain(source)
        return name to source
    } catch (error: Throwable) { file.delete(); throw error }
}

/** Worker-only, non-purgeable working files with weak leases held by snapshots/history. */
private object ClipWorkingStorage {
    private val roots = mutableSetOf<String>()
    private val leases = mutableMapOf<File, java.lang.ref.WeakReference<MediaSource>>()
    @Synchronized fun directory(files: File): File {
        val root = File(files, "media-working-assets")
        if (roots.add(root.absolutePath)) {
            root.deleteRecursively()
            check(root.mkdirs()) { "Cannot create media working storage" }
        }
        leases.entries.removeAll { (file, reference) ->
            if (reference.get() == null) { file.delete(); true } else false
        }
        return root
    }
    @Synchronized fun retain(source: MediaSource) { leases[source.file] = java.lang.ref.WeakReference(source) }
}
