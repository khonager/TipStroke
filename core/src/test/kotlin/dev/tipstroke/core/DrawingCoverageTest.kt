package dev.tipstroke.core
import dev.tipstroke.core.model.*
import kotlin.test.*
class DrawingCoverageTest {
    @Test fun emptyCelCutsOffEarlierHoldAndNextGapWraps() {
        val first = AnimationFrame(FrameId("paint"), sourceTimeUs = 0, durationUs = 900_000)
        val blank = AnimationFrame(FrameId("blank"), sourceTimeUs = 300_000, durationUs = 200_000)
        val last = AnimationFrame(FrameId("last"), sourceTimeUs = 600_000, durationUs = 400_000)
        val spans = DrawingCoverage.spans(listOf(first, blank, last), setOf(first.id, last.id), 10, 1_000_000)
        assertEquals(listOf(DrawingSpan(0, 300_000), DrawingSpan(600_000, 1_000_000)), spans)
        assertEquals(300_000L, DrawingCoverage.nextUndrawn(spans, 0, 10, 1_000_000))
        assertEquals(300_000L, DrawingCoverage.nextUndrawn(spans, 900_000, 10, 1_000_000))
        assertNull(DrawingCoverage.nextUndrawn(listOf(DrawingSpan(0, 1_000_000)), 0, 10, 1_000_000))
    }
}
