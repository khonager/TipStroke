package dev.tipstroke.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CanvasPresetPreferencesTest {
    @Test fun savesCustomPresetAndDefaultAcrossInstances() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("canvas-presets", 0).edit().clear().commit()
        val preferences = CanvasPresetPreferences(context)
        assertEquals("max-square", preferences.defaultId())
        val preset = preferences.add("Sprites", 32, 48)
        preferences.setDefault(preset.id)
        val reopened = CanvasPresetPreferences(context)
        assertEquals(preset, reopened.saved().single())
        assertEquals(preset.id, reopened.defaultId())
        reopened.remove(preset.id)
        assertTrue(reopened.saved().isEmpty())
        assertEquals("max-square", reopened.defaultId())
    }

    @Test fun maximumSquareFitsOneLayerBudgetAndCanvasLimit() {
        assertEquals(5632, maximumOneLayerSquare(256))
        assertEquals(8192, maximumOneLayerSquare(1024))
        assertTrue(maximumOneLayerSquare(64) > 2048)
    }
}
