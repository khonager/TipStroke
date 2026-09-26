package dev.tipstroke.app

import dev.tipstroke.core.model.BrushPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushStudioTiltControlsTest {
    @Test fun shadingStartNeverExceedsThePresetBoundary() {
        val upper = pencilShadeStartUpperBound(BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS)

        assertEquals(BrushPreset.MAX_PENCIL_SHADE_START_RADIANS, upper, .0001f)
        assertTrue(upper in BrushPreset.MIN_PENCIL_SHADE_START_RADIANS..
            BrushPreset.MAX_PENCIL_SHADE_START_RADIANS)
    }

    @Test fun angleUpdatesAlwaysProduceAValidTransition() {
        val boundaries = listOf(
            BrushPreset.MIN_PENCIL_SHADE_START_RADIANS to BrushPreset.MIN_PENCIL_FULL_TILT_RADIANS,
            BrushPreset.MIN_PENCIL_SHADE_START_RADIANS to BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS,
            BrushPreset.MAX_PENCIL_SHADE_START_RADIANS to BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS,
        )

        boundaries.forEach { (start, end) ->
            val transition = pencilShadeTransition(start, end)
            assertTrue(transition in BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS..
                BrushPreset.MAX_PENCIL_SHADE_TRANSITION_RADIANS)
            BrushPreset.Pencil.copy(
                pencilShadeStartRadians = start,
                pencilShadeTransitionRadians = transition,
            )
        }
    }
}
