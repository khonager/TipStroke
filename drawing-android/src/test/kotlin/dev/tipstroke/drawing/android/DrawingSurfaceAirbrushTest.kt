package dev.tipstroke.drawing.android

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.tipstroke.core.drawing.PointerKind
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.BrushPreset
import org.junit.Assert.assertFalse
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
class DrawingSurfaceAirbrushTest {
    @Test fun penUpPressureUsesTheLastDrawingPressure() {
        assertEquals(.72f, stabilizedTerminalPressure(.72f, .01f), .001f)
        assertEquals(.4f, stabilizedTerminalPressure(null, .4f), .001f)
    }

    @Test fun rapidLiftOffStabilizesOpacityButKeepsSizePressure() {
        val samples = listOf(
            pressureSample(.82f, 0L),
            pressureSample(.76f, 25L),
            pressureSample(.43f, 45L),
            pressureSample(.09f, 60L),
            pressureSample(.09f, 61L),
        )

        val stabilized = stabilizeLiftOffOpacity(samples)

        assertEquals(samples.map { it.pressure }, stabilized.map { it.pressure })
        assertEquals(.82f, stabilized.last().opacityPressure, .001f)
    }

    @Test fun sustainedLowPressureEndingIsNotChanged() {
        val samples = listOf(
            pressureSample(.8f, 0L),
            pressureSample(.2f, 250L),
            pressureSample(.12f, 430L),
            pressureSample(.08f, 500L),
        )

        val stabilized = stabilizeLiftOffOpacity(samples)

        assertEquals(samples.map { it.opacityPressure }, stabilized.map { it.opacityPressure })
    }

    @Test fun airbrushUsesTransientUnifiedPreviewThenOneTileCommit() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication()).apply {
            layout(0, 0, 800, 800)
            configureBlank(512, 512)
            settings.brush = BrushPreset.Airbrush.copy(hardness = 0f)
            settings.sizePx = 96f
            settings.opacity = 1f
        }
        val raster = surface.getChildAt(0) as RasterCanvasView
        var canUndo = false
        surface.historyListener = { undo, _ -> canUndo = undo }
        val downTime = SystemClock.uptimeMillis()

        surface.dispatchTouchEvent(stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 260f, 400f))
        surface.dispatchTouchEvent(stylusEvent(downTime, downTime + 8L, MotionEvent.ACTION_MOVE, 400f, 360f))
        assertNotNull(raster.previewStroke)
        assertFalse(canUndo)

        surface.dispatchTouchEvent(stylusEvent(downTime, downTime + 16L, MotionEvent.ACTION_UP, 540f, 400f))
        assertNull(raster.previewStroke)
        assertTrue(canUndo)
    }

    private fun stylusEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        return MotionEvent.obtain(
            downTime,
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

    private fun pressureSample(pressure: Float, elapsedMillis: Long) = StrokeSample(
        pointerId = 0,
        position = Point(elapsedMillis.toFloat(), 0f),
        pressure = pressure,
        tiltRadians = 0f,
        orientationRadians = 0f,
        elapsedNanos = elapsedMillis * 1_000_000L,
        buttonState = 0,
        kind = PointerKind.STYLUS,
    )
}
