package dev.tipstroke.drawing.android

import android.view.InputDevice
import android.view.MotionEvent
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmudgeToolTest {
    @Test fun selectedSmudgeWorksForFingerAndStylusAndUndoesAsOneGesture() {
        for (tool in listOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS)) {
            val surface = DrawingSurface(RuntimeEnvironment.getApplication())
            surface.layout(0, 0, 512, 512)
            surface.configureBlank(512, 512)
            val view = surface.getChildAt(0) as RasterCanvasView
            view.updateTransform(256f, 256f, 1f, 0f)
            val store = view.layerStack.selectedRaster()!!.tiles
            val samples = listOf(80f, 120f).mapIndexed { index, x ->
                StrokeSample(1, Point(x, 80f), 1f, 0f, 0f, index * 16_000_000L, 0, PointerKind.STYLUS)
            }
            store.commit(CompletedStroke(samples, StrokeStyle(BrushPreset.Airbrush, 30f, 1f, RgbaColor(1f, 0f, 0f), BlendBehavior.PAINT)))
            fun pixels() = IntArray(256 * 128) { store.colorAt(it % 256, it / 256) }
            val before = pixels()
            surface.settings.apply {
                smudging = true
                sizePx = 40f
                emulateMouseWithTouch = false
                gestures = GestureSettings(oneFingerDrag = FingerAction.DRAW, smudgeStrength = .8f)
            }
            fun event(action: Int, x: Float, time: Long) {
                val properties = MotionEvent.PointerProperties().apply { id = 1; toolType = tool }
                val coordinates = MotionEvent.PointerCoords().apply { this.x = x; y = 80f; pressure = 1f }
                MotionEvent.obtain(0, time, action, 1, arrayOf(properties), arrayOf(coordinates),
                    0, 0, 1f, 1f, 0, 0,
                    if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN, 0).also {
                    surface.dispatchTouchEvent(it); it.recycle()
                }
            }
            event(MotionEvent.ACTION_DOWN, 100f, 0)
            event(MotionEvent.ACTION_MOVE, 140f, 16)
            event(MotionEvent.ACTION_UP, 160f, 32)
            val after = pixels()
            assertFalse("Selected smudge must change paint for tool $tool", before.contentEquals(after))
            surface.undo()
            assertArrayEquals(before, pixels())
            surface.redo()
            assertArrayEquals(after, pixels())
            // A cancelled gesture must release the pointer and remain undoable.
            event(MotionEvent.ACTION_DOWN, 120f, 40)
            event(MotionEvent.ACTION_MOVE, 150f, 56)
            event(MotionEvent.ACTION_CANCEL, 150f, 64)
            surface.undo()
            assertArrayEquals(after, pixels())
        }
    }
}
