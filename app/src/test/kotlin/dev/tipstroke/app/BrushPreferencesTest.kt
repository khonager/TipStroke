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
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("brush-settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = BrushPreferences(context)
        preferences.save(BrushPreset.Ink, BrushTuning(41f, .7f, .8f, false, true, true))
        preferences.saveEraser(BrushTuning(63f, .8f, .95f, true, false, false))

        val loaded = preferences.load(BrushPreset.Ink)
        assertEquals(41f, loaded.sizePx, .001f)
        assertEquals(.7f, loaded.opacity, .001f)
        assertEquals(.8f, loaded.hardness, .001f)
        assertFalse(loaded.pressureSize)
        assertTrue(loaded.pressureOpacity)
        assertTrue(loaded.speedTaper)
        val eraser = preferences.loadEraser()
        assertEquals(63f, eraser.sizePx, .001f)
        assertEquals(.8f, eraser.opacity, .001f)
        assertEquals(.95f, eraser.hardness, .001f)
        assertFalse(eraser.pressureOpacity)
        context.getSharedPreferences("brush-settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }
}
