package dev.tipstroke.app

import dev.tipstroke.core.model.RgbaColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ColorShelfTest {
    @Test fun savedColorsStayExactAndIndependentOfQuickSlotSettings() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("color-shelf", 0).edit().clear().commit()
        val shelf = ColorShelf(context)
        val yellow = RgbaColor(.91f, .82f, .11f)
        shelf.save(yellow); shelf.save(yellow)
        shelf.changeSlots(12); shelf.toggleSource()
        val restored = ColorShelf(context)
        assertEquals(listOf(yellow), restored.saved)
        assertEquals(12, restored.slots)
        assertTrue(restored.recent)
        restored.remove(0)
        assertTrue(ColorShelf(context).saved.isEmpty())
    }
}
