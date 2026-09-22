package dev.tipstroke.app

import dev.tipstroke.core.model.BrushPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrushPreferencesTest {
    @Test fun tuningPersistsPerBrushAndSeparatelyForEraser() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("brush-settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = BrushPreferences(context)
        preferences.save(
            BrushPreset.Pencil,
            BrushTuning(41f, .7f, .8f, false, true, true, .8f, .74f, 1.4f, .46f, .35f),
        )
        preferences.saveEraser(BrushTuning(63f, .8f, .95f, true, false, false))

        val loaded = preferences.load(BrushPreset.Pencil)
        assertEquals(41f, loaded.sizePx, .001f)
        assertEquals(.7f, loaded.opacity, .001f)
        assertEquals(.8f, loaded.hardness, .001f)
        assertFalse(loaded.pressureSize)
        assertTrue(loaded.pressureOpacity)
        assertTrue(loaded.speedTaper)
        assertEquals(.8f, loaded.pencilPointSize, .001f)
        assertEquals(.74f, loaded.pencilTiltSensitivity, .001f)
        assertEquals(1.4f, loaded.pencilShadeSize, .001f)
        assertEquals(.46f, loaded.pencilShadeOpacity, .001f)
        assertEquals(.35f, loaded.pencilGrain, .001f)
        val eraser = preferences.loadEraser()
        assertEquals(63f, eraser.sizePx, .001f)
        assertEquals(.8f, eraser.opacity, .001f)
        assertEquals(.95f, eraser.hardness, .001f)
        assertFalse(eraser.pressureOpacity)
        context.getSharedPreferences("brush-settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }
}
