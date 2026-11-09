package dev.tipstroke.app
import org.junit.Assert.*
import org.junit.Test
class SizeSliderScaleTest {
    @Test fun largeSizesRoundTripAndLeaveFineTravelForSmallTips() {
        val range = 1f..65536f
        for (size in listOf(1f, 10f, 180f, 4096f, 65536f)) assertEquals(size, sliderValue(sliderFraction(size, range), range), .1f)
        assertTrue(sliderValue(.5f, range) < 300f)
        assertEquals(.5f, sliderValue(.5f, 0f..1f), .001f)
    }
}
