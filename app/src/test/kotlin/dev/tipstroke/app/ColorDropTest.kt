package dev.tipstroke.app

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.model.RgbaColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ColorDropTest {
    @Test fun tapOpensPickerAndDragOutsideCircleDropsAtWindowPosition() {
        val controller = Robolectric.buildActivity(ScreenshotActivity::class.java).setup()
        val activity = controller.get()
        var taps = 0
        var drop: Offset? = null
        var origin = Offset.Zero
        activity.setContent {
            CompositionLocalProvider(LocalColorDrop provides { position, _, released -> if (released) drop = position }) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(48.dp).onGloballyPositioned { origin = it.positionInWindow() }
                        .colorDrop(RgbaColor(1f, 0f, 0f)).clickable { taps++ })
                }
            }
        }
        shadowOf(activity.mainLooper).idle()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 800, 1000)
        shadowOf(activity.mainLooper).idle()
        val started = SystemClock.uptimeMillis()
        fun event(action: Int, time: Long, position: Offset) {
            val event = MotionEvent.obtain(started, started + time, action, position.x, position.y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            root.dispatchTouchEvent(event)
            event.recycle()
        }
        val start = origin + Offset(10f, 10f)
        event(MotionEvent.ACTION_DOWN, 0, start)
        event(MotionEvent.ACTION_UP, 16, start)
        assertEquals(1, taps)
        event(MotionEvent.ACTION_DOWN, 32, start)
        event(MotionEvent.ACTION_MOVE, 64, start + Offset(100f, 100f))
        event(MotionEvent.ACTION_MOVE, 80, start + Offset(200f, 200f))
        event(MotionEvent.ACTION_UP, 96, start + Offset(200f, 200f))
        assertEquals(1, taps)
        assertNotNull(drop)
        assertEquals(start.x + 200f, drop!!.x, 1f)
        assertEquals(start.y + 200f, drop!!.y, 1f)
        controller.pause().stop().destroy()
    }
}
