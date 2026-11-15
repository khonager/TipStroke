package dev.tipstroke.core.model

/** Half-open source-time intervals. A newer empty cel also cuts off an earlier hold. */
data class DrawingSpan(val startUs: Long, val endUs: Long)
object DrawingCoverage {
    fun spans(frames: List<AnimationFrame>, painted: Set<FrameId>, fps: Int, durationUs: Long): List<DrawingSpan> {
        val result = mutableListOf<DrawingSpan>()
        frames.forEachIndexed { index, frame ->
            val start = (frame.sourceTimeUs ?: 0).coerceIn(0, durationUs)
            val end = minOf(start + MediaTimeline.durationUs(frame, fps), frames.getOrNull(index + 1)?.sourceTimeUs ?: durationUs, durationUs)
            if (frame.id in painted && end > start) {
                val last = result.lastOrNull()
                if (last != null && last.endUs >= start) result[result.lastIndex] = last.copy(endUs = maxOf(last.endUs, end))
                else result += DrawingSpan(start, end)
            }
        }
        return result
    }
    /** Skip the current drawing-grid step; wrap once to the first gap. */
    fun nextUndrawn(spans: List<DrawingSpan>, positionUs: Long, fps: Int, durationUs: Long): Long? {
        fun gapAfter(start: Long): Long? {
            var cursor = start
            for (span in spans) {
                if (span.endUs <= cursor) continue
                if (span.startUs > cursor) return cursor.takeIf { it < durationUs }
                cursor = span.endUs
            }
            return cursor.takeIf { it < durationUs }
        }
        val step = ((positionUs * fps / 1_000_000L + 1) * 1_000_000L / fps).coerceAtLeast(positionUs + 1)
        return gapAfter(step) ?: gapAfter(0)
    }
}
