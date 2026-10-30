package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.*
import org.junit.Assert.*
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
class LazyAnimationTest {
    @Test fun unloadedFramesSurviveSaveExportLoadingAndEviction() {
        val context = RuntimeEnvironment.getApplication()
        val library = DrawingLibrary(context)
        val id = library.newId()
        val layerId = LayerId("paint")
        val frames = listOf(AnimationFrame(FrameId("first")), AnimationFrame(FrameId("second")))
        fun tile(color: Int) = mapOf(TileCoordinate(0, 0) to Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })
        val snapshot = DrawingSnapshot(256, 256, layerId,
            listOf(SavedRasterSnapshot(layerId, "Paint", true, 1f, tile(Color.RED))),
            animation = SavedAnimationSnapshot(frames, 0, 12, PlaybackMode.LOOP, 0, 0, .3f,
                emptySet(), mapOf(layerId to mapOf(frames[0].id to tile(Color.RED), frames[1].id to tile(Color.BLUE))), emptyMap()))
        try {
            ProjectPersistence.save(context.contentResolver, library, id, "Lazy", snapshot).getOrThrow()
            val loaded = ProjectPersistence.load(library.projectDirectory(id), lazyAnimation = true).getOrThrow()
            val saved = loaded.animation!!
            assertEquals(1, saved.rasterCels.getValue(layerId).size)
            assertTrue(saved.encodedRasterCels.getValue(layerId).getValue(frames[1].id).isNotEmpty())
            val stack = LayerStack(context.contentResolver, 256, 256) {}
            stack.replaceWith(loaded)
            val animation = AnimationRuntime(stack, true, frames).apply { restore(saved) }
            val second = animation.celRaster(layerId, frames[1].id)!!
            assertFalse(second.isResident)
            val recorder = ProcessRecorder(256, 256)
            recorder.sync(stack, true, animation = animation)
            assertNull(recorder.snapshot().stoppedReason)
            val lazySnapshot = stack.snapshot(animation = animation)
            try {
                ProjectPersistence.save(context.contentResolver, library, id, "Lazy", lazySnapshot).getOrThrow()
                val output = java.io.File.createTempFile("lazy-frame", ".png")
                try {
                    val secondFrame = lazySnapshot.copy(animation = lazySnapshot.animation!!.copy(selectedIndex = 1))
                    ProjectPersistence.export(context.contentResolver, android.net.Uri.fromFile(output), secondFrame,
                        ExportFormat.PNG, 100, 1f, false).getOrThrow()
                    val image = android.graphics.BitmapFactory.decodeFile(output.absolutePath)
                    assertEquals(Color.BLUE, image.getPixel(10, 10)); image.recycle()
                } finally { output.delete() }
            }
            finally { lazySnapshot.recycle() }
            assertFalse(second.isResident)
            val eager = ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
            assertEquals(Color.BLUE, eager.animation!!.rasterCels.getValue(layerId).getValue(frames[1].id).values.first().getPixel(10, 10))
            var ready = false
            animation.prepareFrames(listOf(1)) {
                it.getOrThrow()
                animation.trimCleanFrames(setOf(0))
                assertTrue("A ready callback must be able to consume its frame before eviction", second.isResident)
                ready = true
            }
            AnimationDecoding.executor.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ready)
            animation.select(1)
            assertEquals(Color.BLUE, stack.selectedRaster()!!.tiles.colorAt(10, 10))
            animation.select(0)
            animation.trimCleanFrames(setOf(0))
            assertFalse(second.isResident)
            recorder.detach()
        } finally { snapshot.recycle(); library.delete(id) }
    }
}
