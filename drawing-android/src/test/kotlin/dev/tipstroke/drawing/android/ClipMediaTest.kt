package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
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
class ClipMediaTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun asset(id: String, color: Int): MediaSource {
        val file = File(context.cacheDir, "$id.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        return MediaSource(file, "image", 32, 32, 4_000_000)
    }
    private fun sequence(): MediaSource {
        val edit = VideoEdit(listOf(MediaClip("a", "red", "Red", 0, 4_000_000),
            MediaClip("b", "blue", "Blue", 0, 4_000_000, transitionUs = 1_000_000)))
        return MediaSource(File("unused"), "sequence", 32, 32, edit.durationUs, edit = edit,
            assets = mapOf("red" to asset("red", Color.RED), "blue" to asset("blue", Color.BLUE)))
    }
    private fun color(decoder: MediaFrameDecoder, time: Long): Int {
        val bitmap = decoder.frame(time, 32)
        return try { bitmap.getPixel(16, 16) } finally { bitmap.recycle() }
    }

    @Test fun shorterTrackKeepsLaterDrawingsAndBoundedEditHistory() {
        val stack = LayerStack(context.contentResolver, 32, 32) {}
        val runtime = AnimationRuntime(stack, false)
        runtime.replaceMedia(sequence())
        runtime.showMediaTime(6_000_000); runtime.addMediaDrawing()
        val source = runtime.media!!
        val edit = VideoEdit(listOf(source.edit!!.clips.first().slice(0, 1_000_000)))
        repeat(60) { runtime.replaceMedia(source.copy(edit = edit, durationUs = edit.durationUs)) }
        assertTrue(runtime.frames.any { it.sourceTimeUs == 6_000_000L })
        assertTrue(runtime.mediaPositionUs < edit.durationUs)
        repeat(50) { runtime.undoVideoEdit(false) }
        assertFalse(runtime.state(false).canUndoVideoEdit)
        assertTrue(runtime.state(false).canRedoVideoEdit)
    }

    @Test fun malformedAssetReferenceAndOutOfSourceVideoRangeAreRejected() {
        val source = sequence()
        assertThrows(IllegalArgumentException::class.java) { source.copy(assets = emptyMap()) }
        val fakeVideo = MediaSource(File("fake"), "video", 32, 32, 500_000)
        assertThrows(IllegalArgumentException::class.java) { source.copy(assets = source.assets + ("red" to fakeVideo)) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(assets = source.assets + ("../escape" to fakeVideo)) }
    }
}
