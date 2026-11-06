package dev.tipstroke.core

import dev.tipstroke.core.model.*
import kotlin.test.*

class VideoEditTest {
    private fun clip(id: String = "a", duration: Long = 4_000_000, transition: Long = 0) = MediaClip(id, "source", id, 1_000_000, duration, transitionUs = transition)

    @Test fun splitRemoveMiddleAndMoveMapBackToExactSourceRanges() {
        val cut = VideoEdit(listOf(clip())).split(0, 1_000_000, "b").split(1, 1_000_000, "c").delete(1)
        assertEquals(3_000_000, cut.durationUs)
        assertEquals(listOf(1_000_000L, 3_000_000L), cut.clips.map { it.sourceInUs })
        assertEquals(1, cut.indexAt(1_000_000))
        val moved = cut.move(1, 0)
        assertEquals(listOf("c", "a"), moved.clips.map { it.id })
        assertEquals(2_000_000, moved.startUs(1))
        assertEquals(cut.durationUs, moved.durationUs)
    }

    @Test fun splitAndTrimPreserveSmoothAndHoldInterpolationWithoutBakingFrames() {
        for (easing in ClipEasing.entries) {
            val original = clip().copy(keyframes = listOf(
                ClipKeyframe(0, ClipPose(x = -.2f, scale = .5f), easing),
                ClipKeyframe(4_000_000, ClipPose(x = .5f, scale = 2f, rotation = 180f, opacity = .4f))))
            val edit = VideoEdit(listOf(original)).split(0, 1_700_000, "b")
            for (time in listOf(0L, 300_000, 1_700_000, 2_500_000, 3_999_999)) {
                val i = edit.indexAt(time)
                assertEquals(original.poseAt(time), edit.clips[i].poseAt(time - edit.startUs(i)))
            }
            val trimmed = original.slice(1_000_000, 3_000_000)
            assertEquals(original.poseAt(1_500_000), trimmed.poseAt(500_000))
            assertEquals(original, trimmed.trimSource(original.sourceInUs, original.sourceInUs + original.durationUs))
        }
    }

    @Test fun crossfadeOverlapsClipsWithoutChangingSourceSpeedOrAllowingTripleOverlap() {
        val edit = VideoEdit(listOf(clip(), clip("b", transition = 1_000_000), clip("c", transition = 2_000_000)))
        assertEquals(listOf(0L, 3_000_000L, 5_000_000L), edit.clips.indices.map(edit::startUs))
        assertEquals(9_000_000, edit.durationUs)
        assertEquals(0, edit.indexAt(2_999_999)); assertEquals(1, edit.indexAt(3_000_000))
        val shortened = edit.replace(1, edit.clips[1].slice(0, 500_000))
        assertEquals(250_000, shortened.clips[1].transitionUs)
        assertEquals(250_000, shortened.clips[2].transitionUs)
    }

    @Test fun keyframesReplaceAtSameTimeAndValidationRejectsInvalidEdits() {
        val original = clip().withKeyframe(ClipKeyframe(1_000_000, ClipPose(scale = 2f)))
        val replaced = original.withKeyframe(ClipKeyframe(1_000_000, ClipPose(scale = 3f)))
        assertEquals(1, replaced.keyframes.size)
        assertEquals(3f, replaced.poseAt(0).scale)
        assertFailsWith<IllegalArgumentException> { ClipPose(scale = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ClipPose(opacity = 2f) }
        assertFailsWith<IllegalArgumentException> { VideoEdit(listOf(clip())).split(0, 0, "x") }
        assertFailsWith<IllegalArgumentException> { VideoEdit(listOf(clip())).delete(0) }
        assertFailsWith<IllegalArgumentException> { VideoEdit(listOf(clip(), clip())) }
        assertFailsWith<IllegalArgumentException> { clip().copy(assetId = "../escape") }
    }
}
