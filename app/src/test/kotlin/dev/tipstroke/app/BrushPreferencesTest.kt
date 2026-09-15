package dev.tipstroke.app

import dev.tipstroke.core.model.BrushPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BrushPreferencesTest {
    @Test fun tuningPersistsPerBrushAndSeparatelyForEraser() {
        val preferences = BrushPreferences(RuntimeEnvironment.getApplication())
        preferences.save(BrushPreset.Ink, BrushTuning(.8f, false, true, true))
        preferences.saveEraserHardness(.95f)

        val loaded = preferences.load(BrushPreset.Ink)
        assertEquals(.8f, loaded.hardness, .001f)
        assertFalse(loaded.pressureSize)
        assertTrue(loaded.pressureOpacity)
        assertTrue(loaded.speedTaper)
        assertEquals(.95f, preferences.loadEraserHardness(), .001f)
    }
}
