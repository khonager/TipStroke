package dev.tipstroke.app

import dev.tipstroke.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GesturePreferencesTest {
    @Test fun fillDurationDefaultsAndClampsStoredValues() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("gesture-settings", android.content.Context.MODE_PRIVATE)
        storage.edit().clear().commit()
        val preferences = GesturePreferences(context)
        assertEquals(500L, preferences.load().fillAnimationMillis)
        storage.edit().putLong("fill_animation_ms", -100L).commit()
        assertEquals(250L, preferences.load().fillAnimationMillis)
        storage.edit().putLong("fill_animation_ms", 99999L).commit()
        assertEquals(5000L, preferences.load().fillAnimationMillis)
    }

    @Test fun persistsCustomFingerMappingsAndTuning() {
        val preferences = GesturePreferences(RuntimeEnvironment.getApplication())
        val expected = GestureSettings(
            oneFingerDrag = FingerAction.DRAW,
            oneFingerHold = FingerAction.UNDO,
            twoFingerTap = FingerAction.REDO,
            threeFingerTap = FingerAction.DISABLED,
            holdDelayMillis = 650,
            smudgeStrength = .72f,
            fillAnimationMillis = 750L,
            imageSnapping = true,
            imageSnapAngle = 45,
            imageSnapStrength = .8f,
            rotationLocked = true,
            hideNavigationBarWhileDrawing = false,
            showStylusHoverPreview = false,
            stylusPrimaryButton = StylusButtonAction.REDO,
            stylusSecondaryButton = StylusButtonAction.DISABLED,
        )
        preferences.save(expected)
        assertEquals(expected, preferences.load())
    }
}
