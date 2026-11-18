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
        for (tick in 0 until cycleTicks) {
            val index = frameAt(tick.toLong())
            val last = result.lastOrNull()
            if (last?.index == index) result[result.lastIndex] = last.copy(ticks = last.ticks + 1)
            else result += NativePlaybackSegment(index, 1)
        }
        return result
    }
}

data class NativePlaybackSegment(val index: Int, val ticks: Int)
