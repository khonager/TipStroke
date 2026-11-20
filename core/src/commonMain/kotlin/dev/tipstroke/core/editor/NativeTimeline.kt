package dev.tipstroke.core.editor

import dev.tipstroke.core.model.AnimationFrame
import dev.tipstroke.core.model.AnimationTimeline
import dev.tipstroke.core.model.FrameId
import dev.tipstroke.core.model.PlaybackMode

/** Objective-C-friendly facade over the same exposure/playback rules used by Android. */
class NativeTimeline(exposures: String, fps: Int, mode: String) {
    private val timeline = AnimationTimeline(exposures.split(',').mapIndexed { index, value ->
        AnimationFrame(FrameId(index.toString()), value.toInt())
    }, fps, PlaybackMode.valueOf(mode))
    val totalTicks: Int get() = timeline.totalTicks
    val cycleTicks: Int get() = if (timeline.playbackMode == PlaybackMode.PING_PONG) (totalTicks * 2 - 2).coerceAtLeast(1) else totalTicks
    fun frameAt(tick: Long): Int = timeline.frameAtPlaybackTick(tick)
    fun segments(): List<NativePlaybackSegment> {
        val result = mutableListOf<NativePlaybackSegment>()
        fun append(index: Int, ticks: Int) {
            if (ticks <= 0) return
            val last = result.lastOrNull()
            if (last?.index == index) result[result.lastIndex] = last.copy(ticks = last.ticks + ticks)
            else result += NativePlaybackSegment(index, ticks)
        }
        val frames = timeline.frames
        frames.forEachIndexed { index, frame -> append(index, frame.exposure) }
        if (timeline.playbackMode == PlaybackMode.PING_PONG) {
            // Reverse playback skips the two endpoint ticks already shown on the forward pass.
            for (index in frames.indices.reversed()) {
                var ticks = frames[index].exposure
                if (index == frames.lastIndex) ticks--
                if (index == 0) ticks--
                append(index, ticks)
            }
        }
        return result
    }
}

data class NativePlaybackSegment(val index: Int, val ticks: Int)
