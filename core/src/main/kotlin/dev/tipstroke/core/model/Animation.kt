package dev.tipstroke.core.model

import java.util.UUID
import kotlin.math.floor

@JvmInline value class FrameId(val value: String) {
    companion object { fun new() = FrameId(UUID.randomUUID().toString()) }
}

/** A frame is a time slot. Artwork belongs to layers and is addressed by frame ID. */
data class AnimationFrame(val id: FrameId, val exposure: Int = 1) {
    init { require(exposure in 1..600) }
}

enum class PlaybackMode { LOOP, PING_PONG, ONCE }
enum class RecordingTiming { FIT_RANGE, REAL_TIME }

data class AnimationTimeline(
    val frames: List<AnimationFrame>,
    val fps: Int = 12,
    val playbackMode: PlaybackMode = PlaybackMode.LOOP,
) {
    init {
        require(frames.isNotEmpty())
        require(frames.map { it.id }.distinct().size == frames.size)
        require(fps in 1..60)
    }

    val totalTicks: Int get() = frames.sumOf { it.exposure }

    fun frameAtTick(tick: Int): Int {
        var remaining = tick.coerceIn(0, totalTicks - 1)
        frames.forEachIndexed { index, frame ->
            if (remaining < frame.exposure) return index
            remaining -= frame.exposure
        }
        return frames.lastIndex
    }

    fun frameAtPlaybackTick(tick: Long): Int {
        val lastTick = totalTicks - 1
        val position = when (playbackMode) {
            PlaybackMode.LOOP -> tick.mod(totalTicks.toLong()).toInt()
            PlaybackMode.ONCE -> tick.coerceIn(0, lastTick.toLong()).toInt()
            PlaybackMode.PING_PONG -> {
                if (lastTick == 0) 0 else {
                    val phase = tick.mod((lastTick * 2L))
                    if (phase <= lastTick) phase.toInt() else (lastTick * 2L - phase).toInt()
                }
            }
        }
        return frameAtTick(position)
    }

    fun frameForRecordProgress(progress: Float, start: Int, endInclusive: Int): Int {
        require(start in frames.indices && endInclusive in frames.indices && start <= endInclusive)
        val count = endInclusive - start + 1
        return start + floor(progress.coerceIn(0f, 1f) * count).toInt().coerceAtMost(count - 1)
    }
}
