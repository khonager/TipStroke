package dev.tipstroke.drawing.android

import android.graphics.Color
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.drawing.PointerKind
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.drawing.StrokeStyle
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.BlendBehavior
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.RecordingTiming
import dev.tipstroke.core.model.RgbaColor
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
class AnimationRuntimeTest {
    @Test fun liveDrawingAddsFramesAndKeepsRecordingLaterStrokes() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 256, 256) {}
        val animation = AnimationRuntime(stack, existingAsBackground = false, initialFps = 10)
        val layerId = stack.addRaster()
        animation.register(stack.selected())
        val style = StrokeStyle(BrushPreset.Ink, 12f, 1f, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT)
        fun stroke(y: Float, duration: Long) = CompletedStroke(listOf(
            StrokeSample(1, Point(20f, y), 1f, 0f, 0f, 0L, 0, PointerKind.STYLUS),
            StrokeSample(1, Point(170f, y), 1f, 0f, 0f, duration, 0, PointerKind.STYLUS),
        ), style)

        animation.addBlankFrame()
        animation.carryRecordedDrawing(layerId, 0, 1)
        animation.recordLiveStroke(layerId, 0, 1_000L, 1_000L, 1, stroke(50f, 150_000_000L))
        animation.addBlankFrame()
        animation.carryRecordedDrawing(layerId, 1, 2)
        animation.recordLiveStroke(layerId, 0, 1_000L, 1_200L, 2, stroke(150f, 40_000_000L))

        assertEquals(3, animation.frames.size)
        assertNotEquals(0, Color.alpha(animation.celRaster(layerId, animation.frames[0].id)!!.colorAt(50, 50)))
        assertEquals(0, Color.alpha(animation.celRaster(layerId, animation.frames[0].id)!!.colorAt(150, 50)))
        assertNotEquals(0, Color.alpha(animation.celRaster(layerId, animation.frames[1].id)!!.colorAt(150, 50)))
        assertNotEquals(0, Color.alpha(animation.celRaster(layerId, animation.frames[2].id)!!.colorAt(150, 50)))
        assertNotEquals(0, Color.alpha(animation.celRaster(layerId, animation.frames[2].id)!!.colorAt(100, 150)))
    }

    @Test fun recordedLineRevealsAcrossFramesWithoutCopyingTheWholeLayer() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 256, 256) {}
        val animation = AnimationRuntime(stack, existingAsBackground = false)
        animation.addBlankFrame()
        animation.addBlankFrame()
        animation.select(0)
        val layerId = stack.addRaster()
        animation.register(stack.selected())
        val style = StrokeStyle(BrushPreset.Ink, 12f, 1f, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT)
        val stroke = CompletedStroke(listOf(
            StrokeSample(1, Point(20f, 100f), 1f, 0f, 0f, 0L, 0, PointerKind.STYLUS),
            StrokeSample(1, Point(100f, 100f), 1f, 0f, 0f, 100_000_000L, 0, PointerKind.STYLUS),
            StrokeSample(1, Point(200f, 100f), 1f, 0f, 0f, 200_000_000L, 0, PointerKind.STYLUS),
        ), style)

        animation.recordStrokeReveal(layerId, 0, 2, RecordingTiming.FIT_RANGE, stroke)

        val first = animation.celRaster(layerId, animation.frames[0].id)!!
        val last = animation.celRaster(layerId, animation.frames[2].id)!!
        assertNotEquals(0, Color.alpha(first.colorAt(50, 100)))
        assertEquals(0, Color.alpha(first.colorAt(150, 100)))
        assertNotEquals(0, Color.alpha(last.colorAt(150, 100)))
        assertTrue(first.allocatedTileCount <= 1)
        assertTrue(last.allocatedTileCount <= 1)
    }
}
