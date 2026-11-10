package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaTimelineTest {
    @Test fun scrubbingThousandsOfSourceFramesKeepsOnlyPaintedCels() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 64, 48) {}
        val frames = List(10_000) { AnimationFrame(FrameId("frame-$it"), sourceTimeUs = it * 1_000_000L / 60) }
        val animation = AnimationRuntime(stack, false, frames, 60)
        animation.restore(SavedAnimationSnapshot(frames, 0, 60, PlaybackMode.LOOP, 1, 1, .3f,
            emptySet(), emptyMap(), emptyMap(), media = MediaSource(File("test.mp4"), "video", 64, 48, 200_000_000L)))
        val layer = stack.selectedRaster()!!
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(10, 10, Color.RED) }
        layer.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to tile))
        frames.indices.forEach(animation::select)
        assertEquals(2, animation.rasterCels[layer.id]!!.size) // Painted frame plus the active empty cel.
        assertEquals(Color.RED, animation.celRaster(layer.id, frames.first().id)!!.colorAt(10, 10))
        val snapshot = animation.snapshot()
        try { assertEquals(1, snapshot.rasterCels.values.sumOf { cels -> cels.values.count { it.isNotEmpty() } }) }
        finally { snapshot.rasterCels.values.forEach { cels -> cels.values.forEach { tiles -> tiles.values.forEach(Bitmap::recycle) } } }
    }

    @Test fun duplicateHoldAndReorderKeepSourceTimestampWithArtwork() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 64, 48) {}
        val frames = listOf(AnimationFrame(FrameId("a"), sourceTimeUs = 0), AnimationFrame(FrameId("b"), sourceTimeUs = 200_000))
        val animation = AnimationRuntime(stack, false, frames)
        animation.select(1)
        val original = animation.currentFrame
        animation.duplicateFrame()
        assertNotEquals(original.id, animation.currentFrame.id)
        assertEquals(original.sourceTimeUs, animation.currentFrame.sourceTimeUs)
        animation.setExposure(6)
        animation.moveFrame(2, 0)
        assertEquals(200_000L, animation.frames[0].sourceTimeUs)
        assertEquals(6, animation.frames[0].exposure)
        animation.addBlankFrame()
        assertEquals(200_000L, animation.currentFrame.sourceTimeUs)
        animation.deleteFrame()
        assertEquals(200_000L, animation.frames[0].sourceTimeUs)
    }

    @Test fun eyedropperCompositesPaintOverMedia() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 64, 48) {}
        stack.mediaColorAt = { _, _ -> Color.BLUE }
        assertEquals(RgbaColor(0f, 0f, 1f), stack.colorAt(10f, 10f))
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(10, 10, Color.RED) }
        stack.selectedRaster()!!.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to tile))
        assertEquals(RgbaColor(1f, 0f, 0f), stack.colorAt(10f, 10f))
    }

    @Test fun freeScrubbingCreatesNoCelsAndDrawingFpsDoesNotRetimePaint() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 64, 48) {}
        val first = AnimationFrame(FrameId("first"), sourceTimeUs = 0, durationUs = 83_333)
        val runtime = AnimationRuntime(stack, false, listOf(first), 12)
        runtime.restore(SavedAnimationSnapshot(listOf(first), 0, 12, PlaybackMode.LOOP, 0, 0, 0f,
            emptySet(), emptyMap(), emptyMap(), media = MediaSource(File("test.mp4"), "video", 64, 48, 2_000_000, 60.0)))
        repeat(120) { runtime.showMediaTime(it * 16_666L) }
        assertEquals(1, runtime.frames.size)
        assertFalse(runtime.mediaDrawingActive)
        assertTrue(stack.layers.none { it.activeAtFrame })
        runtime.showMediaTime(510_000)
        runtime.addMediaDrawing()
        assertEquals(2, runtime.frames.size)
        assertEquals(500_000L, runtime.mediaPositionUs)
        runtime.setMediaDurationTicks(6)
        runtime.fps = 24
        assertEquals(500_000L, runtime.currentFrame.durationUs)
        runtime.showMediaTime(999_999)
        assertTrue(runtime.mediaDrawingActive)
        runtime.showMediaTime(1_000_000)
        assertFalse(runtime.mediaDrawingActive)
        assertEquals(1_000_000L, runtime.snapshot().mediaPositionUs)
    }

    @Test fun automaticDrawingCreationPreservesCursorAndReusesTheCel() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 64, 48) {}
        val frame = AnimationFrame(FrameId("first"), sourceTimeUs = 0, durationUs = 83_333)
        val runtime = AnimationRuntime(stack, false, listOf(frame), 12)
        runtime.restore(SavedAnimationSnapshot(listOf(frame), 0, 12, PlaybackMode.LOOP, 0, 0, 0f,
            emptySet(), emptyMap(), emptyMap(), media = MediaSource(File("test.mp4"), "video", 64, 48, 2_000_000)))
        runtime.showMediaTime(560_000)
        runtime.ensureMediaDrawingAtCursor()
        assertTrue(runtime.mediaDrawingActive)
        assertEquals(560_000L, runtime.mediaPositionUs)
        assertEquals(500_000L, runtime.currentFrame.sourceTimeUs)
        runtime.ensureMediaDrawingAtCursor()
        assertEquals(2, runtime.frames.size)
    }

    @Test fun negativeSourceTimestampIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AnimationFrame(FrameId("bad"), sourceTimeUs = -1) }
    }
}
