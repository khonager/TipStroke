package dev.tipstroke.drawing.android

import android.view.KeyEvent
import android.view.MotionEvent

enum class StylusButton { PRIMARY, SECONDARY }

/** Normalizes modern Android stylus events and legacy Linux/OEM button mappings. */
object StylusButtons {
    private const val PRIMARY_MOTION_MASK = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY
    private const val SECONDARY_MOTION_MASK = MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_TERTIARY

    fun pressed(state: Int): Int =
        (if (state and PRIMARY_MOTION_MASK != 0) 1 else 0) or
            (if (state and SECONDARY_MOTION_MASK != 0) 2 else 0)

    fun fromKeyCode(keyCode: Int): StylusButton? = when (keyCode) {
        KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, KeyEvent.KEYCODE_PAGE_UP -> StylusButton.PRIMARY
        KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, KeyEvent.KEYCODE_PAGE_DOWN -> StylusButton.SECONDARY
        else -> null
    }
}
