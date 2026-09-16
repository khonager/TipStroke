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
    @Test fun persistsCustomFingerMappingsAndTuning() {
        val preferences = GesturePreferences(RuntimeEnvironment.getApplication())
        val expected = GestureSettings(
            oneFingerDrag = FingerAction.SMUDGE,
            oneFingerHold = FingerAction.UNDO,
            twoFingerTap = FingerAction.REDO,
            threeFingerTap = FingerAction.DISABLED,
            holdDelayMillis = 650,
            smudgeStrength = .72f,
            rotationLocked = true,
            stylusPrimaryButton = StylusButtonAction.REDO,
            stylusSecondaryButton = StylusButtonAction.DISABLED,
        )
        preferences.save(expected)
        assertEquals(expected, preferences.load())
    }
}
