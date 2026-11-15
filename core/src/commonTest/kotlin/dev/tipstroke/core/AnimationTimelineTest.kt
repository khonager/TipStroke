package dev.tipstroke.core

import dev.tipstroke.core.model.AnimationFrame
import dev.tipstroke.core.model.AnimationTimeline
import dev.tipstroke.core.model.FrameId
import dev.tipstroke.core.model.PlaybackMode
import kotlin.test.Test
import kotlin.test.assertEquals

class AnimationTimelineTest {
    private val frames = listOf(
        AnimationFrame(FrameId("a"), 2),
        AnimationFrame(FrameId("b"), 1),
        AnimationFrame(FrameId("c"), 3),
    )

    @Test fun holdsAndPlaybackModesUseTimeSlotsWithoutDuplicatingFrames() {
        val timeline = AnimationTimeline(frames)
        assertEquals(3, timeline.frames.size)
        assertEquals(listOf(0, 0, 1, 2, 2, 2), (0 until 6).map(timeline::frameAtTick))
        assertEquals(0, timeline.frameAtPlaybackTick(6))
        assertEquals(2, timeline.copy(playbackMode = PlaybackMode.ONCE).frameAtPlaybackTick(100))
        assertEquals(listOf(0, 0, 1, 2, 2, 2, 2, 2, 1, 0),
            (0L until 10L).map(timeline.copy(playbackMode = PlaybackMode.PING_PONG)::frameAtPlaybackTick))
    }

    @Test fun recordingProgressSelectsFramesInTheChosenRange() {
        val timeline = AnimationTimeline(frames)
        assertEquals(1, timeline.frameForRecordProgress(0f, 1, 2))
        assertEquals(1, timeline.frameForRecordProgress(.49f, 1, 2))
        assertEquals(2, timeline.frameForRecordProgress(.5f, 1, 2))
        assertEquals(2, timeline.frameForRecordProgress(1f, 1, 2))
    }
}
