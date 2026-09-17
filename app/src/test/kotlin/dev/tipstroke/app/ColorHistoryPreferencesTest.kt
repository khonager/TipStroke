package dev.tipstroke.app

import dev.tipstroke.core.model.RgbaColor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ColorHistoryPreferencesTest {
    @Test fun recentDrawnColorsAreDeduplicatedAndCappedAtTen() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("color-history", 0).edit().clear().commit()
        val history = ColorHistoryPreferences(context)
        val colors = List(12) { index -> RgbaColor(index / 12f, .2f, .7f) }

        colors.forEach(history::record)
        assertEquals(10, history.load().size)
        assertColor(colors.last(), history.load().first())

        history.record(colors[5])
        assertEquals(10, history.load().size)
        assertColor(colors[5], history.load().first())

        history.clear()
        assertEquals(emptyList<RgbaColor>(), history.load())
    }

    private fun assertColor(expected: RgbaColor, actual: RgbaColor) {
        assertEquals(expected.red, actual.red, .005f)
        assertEquals(expected.green, actual.green, .005f)
        assertEquals(expected.blue, actual.blue, .005f)
        assertEquals(expected.alpha, actual.alpha, .005f)
    }
}
