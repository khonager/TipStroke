package dev.tipstroke.drawing.android

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StylusButtonsTest {
    @Test fun normalizesStandardAndLegacyMotionButtons() {
        assertEquals(1, StylusButtons.pressed(MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertEquals(1, StylusButtons.pressed(MotionEvent.BUTTON_SECONDARY))
        assertEquals(2, StylusButtons.pressed(MotionEvent.BUTTON_STYLUS_SECONDARY))
        assertEquals(2, StylusButtons.pressed(MotionEvent.BUTTON_TERTIARY))
        assertEquals(3, StylusButtons.pressed(MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY))
    }

    @Test fun recognizesAndroidAndXiaomiKeyCodes() {
        assertEquals(StylusButton.PRIMARY, StylusButtons.fromKeyCode(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY))
        assertEquals(StylusButton.SECONDARY, StylusButtons.fromKeyCode(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY))
        assertEquals(StylusButton.PRIMARY, StylusButtons.fromKeyCode(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(StylusButton.SECONDARY, StylusButtons.fromKeyCode(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(null, StylusButtons.fromKeyCode(KeyEvent.KEYCODE_A))
    }
}
