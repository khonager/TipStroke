package dev.tipstroke.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorPickerMathTest {
    @Test fun concentricDiscMappingPreservesFullHsvSquareRange() {
        val corners = listOf(
            -1f to -1f,
            1f to -1f,
            -1f to 1f,
            1f to 1f,
            0f to 0f,
            -.35f to .72f,
        )

        corners.forEach { (x, y) ->
            val disc = squareToDisc(x, y)
            val restored = discToSquare(disc.x, disc.y)
            assertEquals(x, restored.x, .001f)
            assertEquals(y, restored.y, .001f)
        }
    }

    @Test fun commonHexFormsParseAndFormatAsRgb() {
        val color = parseHexColor("#1a80FF")!!

        assertEquals(0x1A / 255f, color.red, .0001f)
        assertEquals(0x80 / 255f, color.green, .0001f)
        assertEquals(1f, color.blue, .0001f)
        assertEquals("#1A80FF", colorToHex(color))
    }

    @Test fun incompleteOrInvalidHexDoesNotChangeTheColor() {
        assertNull(parseHexColor("#123"))
        assertNull(parseHexColor("#GG1122"))
        assertNull(parseHexColor("#00112233"))
    }
}
