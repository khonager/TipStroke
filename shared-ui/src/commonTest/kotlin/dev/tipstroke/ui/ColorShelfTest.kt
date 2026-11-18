package dev.tipstroke.ui

import dev.tipstroke.core.model.RgbaColor
import kotlin.test.*

class ColorShelfTest {
    @Test fun savedPaletteUsesSameEncodingAndBoundsOnBothPlatforms() {
        var persisted = ""
        val shelf = ColorShelf(initialSlots = 100) { data, _, _ -> persisted = data }
        val color = RgbaColor(.25f, .5f, .75f)
        shelf.save(color); shelf.save(color)
        assertEquals(listOf(color), shelf.saved)
        assertEquals(shelf.saved, decodeColors(persisted))
        assertEquals(16, shelf.slots)
        shelf.remove(0); assertTrue(decodeColors(persisted).isEmpty())
    }
    @Test fun invalidColorChannelsAreNotSilentlyDropped() {
        assertTrue(decodeColors("NaN,0,0,1;1,0,0,1,bad;1,0,0;2,0,0,1").isEmpty())
    }
}
