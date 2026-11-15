package dev.tipstroke.core

import dev.tipstroke.core.model.*
import kotlin.test.*

class MediaTimelineTest {
    private val frames = listOf(
        AnimationFrame(FrameId("a"), sourceTimeUs = 100_000, durationUs = 800_000),
        AnimationFrame(FrameId("b"), sourceTimeUs = 400_000, durationUs = 100_000),
        AnimationFrame(FrameId("c"), sourceTimeUs = 700_000, durationUs = 200_000),
    )
    @Test fun intervalsHaveGapsAndLaterDrawingsReplaceEarlierOnes() {
        val times = listOf(0L, 100_000, 399_999, 400_000, 499_999, 500_000, 700_000, 900_000)
        assertEquals(listOf(-1, 0, 0, 1, 1, -1, 2, -1), times.map { MediaTimeline.frameAt(frames, it, 12) })
    }
    @Test fun changingDrawingFpsPreservesExistingTimings() {
        for (fps in listOf(1, 12, 24, 60)) {
            assertEquals(800_000L, MediaTimeline.durationUs(frames[0], fps))
            assertEquals(1, MediaTimeline.frameAt(frames, 450_000, fps))
        }
        assertEquals(250_000L, MediaTimeline.snap(270_000, 12, 2_000_000))
        assertEquals(266_666L, MediaTimeline.snap(270_000, 60, 2_000_000))
        assertEquals(999_999L, MediaTimeline.snap(999_999, 12, 1_000_000))
    }
    @Test fun bothExportCadencesCoverTheSameDurationWithoutSlowingVideo() {
        val smooth = MediaExportPlan(2_000_000, 60.0)
        val stepped = MediaExportPlan(2_000_000, 12.0)
        assertEquals(120, smooth.frameCount)
        assertEquals(24, stepped.frameCount)
        assertEquals(smooth.durationUs, stepped.durationUs)
        assertEquals(1_000_000L, smooth.timeUs(60))
        assertEquals(1_000_000L, stepped.timeUs(12))
        val fractional = MediaExportPlan(1_001_000, 30_000.0 / 1001)
        assertEquals(30, fractional.frameCount)
        assertEquals(967_633L, fractional.timeUs(29))
        assertTrue(fractional.timeUs(29) < fractional.durationUs)
        assertEquals(1, MediaExportPlan(5_000, 12.0).frameCount)
    }
}
