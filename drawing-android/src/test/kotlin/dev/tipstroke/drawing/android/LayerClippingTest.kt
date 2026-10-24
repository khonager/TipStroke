package dev.tipstroke.drawing.android

import android.graphics.*
import android.net.Uri
import dev.tipstroke.core.model.LayerId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerClippingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun fixture(): LayerStack = LayerStack(context.contentResolver, 512, 256) {}.apply {
        val base = selectedRaster()!!
        base.visible = false
        for (x in 250..260) base.tiles.putFillPixel(x, 20, Color.argb(128, 0, 0, 255))
        addRaster()
        for (x in 240..275) selectedRaster()!!.tiles.putFillPixel(x, 20, Color.RED)
        assertTrue(setClipSource(base.id))
    }
    private fun render(stack: LayerStack): Bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        stack.layers.filterIsInstance<RasterLayerRuntime>().filter { it.visible }.forEach { layer ->
            stack.withClip(canvas, layer) { layer.tiles.draw(canvas, Paint()) }
        }
    }
    @Test fun clippingPreservesPixelsAndSoftCoverageAcrossSparseTiles() {
        val stack = fixture()
        val clipped = render(stack)
        assertEquals(0, Color.alpha(clipped.getPixel(245, 20)))
        assertEquals(128, Color.alpha(clipped.getPixel(255, 20)))
        assertEquals(128, Color.alpha(clipped.getPixel(258, 20)))
        assertEquals(Color.RED, stack.selectedRaster()!!.tiles.colorAt(245, 20))
        assertTrue(stack.selectedStore()!!.history.undo())
        assertEquals(Color.RED, render(stack).getPixel(245, 20))
        assertTrue(stack.selectedStore()!!.history.redo())
        assertEquals(0, Color.alpha(render(stack).getPixel(245, 20)))
        (stack.layers.first() as RasterLayerRuntime).tiles.putFillPixel(245, 20, Color.BLUE)
        assertEquals(Color.RED, render(stack).getPixel(245, 20))
    }
    @Test fun cyclesAreRejectedAndDeletingSourceReleasesClipping() {
        val stack = fixture()
        val child = stack.selectedId
        val source = stack.layers.first().id
        assertFalse(stack.setClipSource(child))
        stack.select(source)
        assertFalse(stack.setClipSource(child))
        assertTrue(stack.deleteSelected())
        assertNull(stack.layers.single().clipSourceId)
        assertEquals(Color.RED, render(stack).getPixel(245, 20))
    }
    @Test fun clippingSurvivesSaveLoadExportAndRecording() {
        val stack = fixture()
        val library = DrawingLibrary(context)
        val id = library.newId()
        val snapshot = stack.snapshot()
        val file = File(context.cacheDir, "clip-export.png")
        try {
            ProjectPersistence.save(context.contentResolver, library, id, "Clipping", snapshot).getOrThrow()
            val loaded = ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
            assertEquals(stack.selected().clipSourceId, loaded.layers.last().clipSourceId)
            val restored = LayerStack(context.contentResolver, 512, 256) {}
            restored.replaceWith(loaded)
            assertEquals(128, Color.alpha(render(restored).getPixel(258, 20)))
            ProjectPersistence.export(context.contentResolver, Uri.fromFile(file), snapshot, ExportFormat.PNG, 100, 1f, true).getOrThrow()
            val exported = BitmapFactory.decodeFile(file.path)
            assertEquals(0, Color.alpha(exported.getPixel(245, 20)))
            assertEquals(128, Color.alpha(exported.getPixel(258, 20)))
            val recorder = ProcessRecorder(512, 256)
            recorder.sync(stack, false)
            val recording = recorder.captureSnapshot().get(5, TimeUnit.SECONDS)
            ProcessReplay(recording).use { replay ->
                val frame = replay.frame(recording.actionCount - 1)
                assertEquals(Color.WHITE, frame.getPixel(245, 20))
                assertEquals(Color.rgb(255, 127, 127), frame.getPixel(258, 20))
            }
            recorder.detach()
        } finally { snapshot.recycle(); file.delete(); library.delete(id) }
    }
}
