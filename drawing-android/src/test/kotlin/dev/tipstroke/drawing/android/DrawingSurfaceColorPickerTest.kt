package dev.tipstroke.drawing.android

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import dev.tipstroke.core.model.FingerAction
import dev.tipstroke.core.model.GestureSettings
import dev.tipstroke.core.model.RgbaColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawingSurfaceColorPickerTest {
    @Test fun holdShowsFollowingLoupeAndCommitsOnlyOnLift() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication())
        surface.layout(0, 0, 600, 600)
        surface.settings.gestures = GestureSettings(
            oneFingerDrag = FingerAction.NAVIGATE,
            oneFingerHold = FingerAction.PICK_COLOR,
            holdDelayMillis = 150L,
        )
        var picked: RgbaColor? = null
        surface.colorPickedListener = { picked = it }
        val downTime = SystemClock.uptimeMillis()

        surface.dispatchTouchEvent(event(downTime, downTime, MotionEvent.ACTION_DOWN, 300f, 300f))
        shadowOf(Looper.getMainLooper()).idleFor(160L, TimeUnit.MILLISECONDS)

        assertNull(picked)
        assertEquals(View.VISIBLE, surface.getChildAt(2).visibility)

        surface.dispatchTouchEvent(event(downTime, downTime + 170L, MotionEvent.ACTION_MOVE, 330f, 315f))
        assertNull(picked)
        assertEquals(View.VISIBLE, surface.getChildAt(2).visibility)

        surface.dispatchTouchEvent(event(downTime, downTime + 180L, MotionEvent.ACTION_UP, 330f, 315f))
        assertTrue(picked != null)
        assertEquals(1f, picked!!.red, .001f)
        assertEquals(View.GONE, surface.getChildAt(2).visibility)
    }

    @Test fun rectangularSelectionCanBeDrawnAndCleared() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication())
        surface.layout(0, 0, 600, 600)
        var active = false
        var selected = false
        surface.selectionListener = { isActive, hasSelection -> active = isActive; selected = hasSelection }
        surface.setSelectionMode(true)
        val downTime = SystemClock.uptimeMillis()

        surface.dispatchTouchEvent(event(downTime, downTime, MotionEvent.ACTION_DOWN, 180f, 180f))
        surface.dispatchTouchEvent(event(downTime, downTime + 16L, MotionEvent.ACTION_MOVE, 420f, 420f))
        surface.dispatchTouchEvent(event(downTime, downTime + 32L, MotionEvent.ACTION_UP, 420f, 420f))

        assertTrue(active)
        assertTrue(selected)
        surface.clearSelection()
        assertTrue(active)
        assertTrue(!selected)
    }

    private fun event(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }
}
