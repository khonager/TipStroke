package dev.tipstroke.ui

import dev.tipstroke.core.model.RgbaColor
import kotlin.test.*

class ColorPickerMathTest {
    @Test fun discRetainsHsvExtremesAndInteriorPoints() {
        for ((x, y) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f, 0f to 0f, -.35f to .72f)) {
            val disc = squareToDisc(x, y)
            val restored = discToSquare(disc.x, disc.y)
            assertEquals(x, restored.x, .001f); assertEquals(y, restored.y, .001f)
        }
    }
    @Test fun hexInputPreservesAndroidRgbRoundingOnEveryPlatform() {
        assertEquals("#1A80FF", colorToHex(parseHexColor(" #1a80FF ")!!))
        assertEquals("#000000", colorToHex(RgbaColor(0f, 0f, 0f)))
        assertEquals("#FFFFFF", colorToHex(RgbaColor(1f, 1f, 1f)))
        assertNull(parseHexColor("#123")); assertNull(parseHexColor("#GG1122"))
        assertNull(parseHexColor("#00112233"))
    }
}
