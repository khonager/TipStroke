package dev.tipstroke.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SizeSliderScaleTest {
    @Test fun pointerMatchesRenderedTrackAcrossDisplayScales() {
        for (density in listOf(1f, 1.25f, 1.33f, 1.5f, 2f, 3f)) {
            for (heightDp in listOf(40f, 154f, 205f)) {
                val height = heightDp * density
                val inset = verticalSliderInset(height, density)
                for (fraction in listOf(0f, .25f, .5f, .75f, 1f)) {
                    val renderedY = height - inset - fraction * (height - 2f * inset)
                    assertEquals(fraction, verticalSliderFraction(renderedY, height, density), .0001f)
                }
                assertEquals(1f, verticalSliderFraction(-20f, height, density))
                assertEquals(0f, verticalSliderFraction(height + 20f, height, density))
            }
        }
    }

    @Test fun largeSizesRoundTripAndLeaveFineTravelForSmallTips() {
        val range = 1f..65536f
        for (size in listOf(1f, 10f, 180f, 4096f, 65536f)) {
            assertEquals(size, sliderValue(sliderFraction(size, range), range), .1f)
        }
        assertTrue(sliderValue(.5f, range) < 300f)
        assertEquals(.5f, sliderValue(.5f, 0f..1f), .001f)
    }
}
