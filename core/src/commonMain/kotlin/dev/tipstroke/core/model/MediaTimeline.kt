package dev.tipstroke.core.model

import kotlin.math.ceil
import kotlin.math.roundToLong

/** Drawing intervals use source time. Video duration and speed never depend on drawing FPS. */
object MediaTimeline {
    fun durationUs(frame: AnimationFrame, drawingFps: Int): Long =
        frame.durationUs ?: (frame.exposure * 1_000_000L / drawingFps)

    /** Chronological drawings; later starts replace earlier ones, even after the newer one expires. */
    fun frameAt(frames: List<AnimationFrame>, timeUs: Long, drawingFps: Int): Int {
        var low = 0
        var high = frames.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if ((frames[middle].sourceTimeUs ?: 0L) <= timeUs) low = middle + 1 else high = middle
        }
        val index = low - 1
        return index.takeIf { it >= 0 && timeUs - (frames[it].sourceTimeUs ?: 0L) < durationUs(frames[it], drawingFps) } ?: -1
    }

    fun snap(timeUs: Long, fps: Int, durationUs: Long): Long =
        ((timeUs * fps / 1_000_000.0).roundToLong() * 1_000_000L / fps).coerceIn(0, durationUs - 1)
}

enum class MediaExportTiming { SMOOTH_VIDEO, DRAWING_FPS }

/** Both export choices cover the same source duration; only the sampling cadence differs. */
data class MediaExportPlan(val durationUs: Long, val fps: Double) {
    init { require(durationUs > 0 && fps.isFinite() && fps > 0 && fps <= 120) }
    val frameCount: Int = ceil(durationUs * fps / 1_000_000.0).toInt()
    fun timeUs(index: Int): Long = (index * 1_000_000.0 / fps).toLong().coerceIn(0, durationUs - 1)
}
