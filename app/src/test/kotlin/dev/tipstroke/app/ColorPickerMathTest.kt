package dev.tipstroke.app

import org.junit.Assert.assertEquals
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
}
