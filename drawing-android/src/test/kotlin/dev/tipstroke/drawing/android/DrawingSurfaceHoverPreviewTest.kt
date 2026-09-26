package dev.tipstroke.drawing.android

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.GestureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawingSurfaceHoverPreviewTest {
    @Test fun stylusHoverShowsTheTunedPencilFootprintAndExitClearsIt() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
            layout(0, 0, 600, 600)
            settings.brush = BrushPreset.Pencil.copy(
                pencilPointSize = .5f,
                pencilTiltSensitivity = 0f,
                pencilShadeSize = BrushPreset.MAX_PENCIL_SHADE_SIZE,
                pencilShadeOpacity = 1f,
                pencilGrain = 1f,
            )
            settings.sizePx = 80f
        }
        val raster = surface.getChildAt(0) as RasterCanvasView
        val now = SystemClock.uptimeMillis()

        assertTrue(surface.handleStylusHoverEvent(stylusHoverEvent(now, MotionEvent.ACTION_HOVER_MOVE, .4f)))
        val preview = raster.stylusHoverPreview
        assertNotNull(preview)
        assertTrue(preview!!.width > 0f)
        assertTrue(preview.height > 0f)
        assertEquals(.25f, preview.rotationRadians, .001f)

        surface.handleStylusHoverEvent(stylusHoverEvent(now + 16L, MotionEvent.ACTION_HOVER_EXIT, .4f))
        assertNull(raster.stylusHoverPreview)
    }

    @Test fun stylusHoverPreviewCanBeDisabled() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
            layout(0, 0, 600, 600)
            settings.gestures = GestureSettings(showStylusHoverPreview = false)
        }
        val raster = surface.getChildAt(0) as RasterCanvasView

        surface.handleStylusHoverEvent(stylusHoverEvent(SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE, 0f))

        assertNull(raster.stylusHoverPreview)
    }

    private fun stylusHoverEvent(eventTime: Long, action: Int, tilt: Float): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = 300f
            y = 300f
            pressure = 0f
            size = 1f
            setAxisValue(MotionEvent.AXIS_TILT, tilt)
            setAxisValue(MotionEvent.AXIS_ORIENTATION, .25f)
        }
        return MotionEvent.obtain(
            eventTime,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )
    }
}
