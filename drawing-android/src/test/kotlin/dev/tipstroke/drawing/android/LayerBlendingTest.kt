package dev.tipstroke.drawing.android

import android.graphics.*
import android.net.Uri
import dev.tipstroke.core.model.LayerBlendMode
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
class LayerBlendingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun fixture() = LayerStack(context.contentResolver, 512, 256) {}.apply {
        for (x in 250..260) selectedRaster()!!.tiles.putFillPixel(x, 20, Color.rgb(128, 200, 80))
        addRaster()
        for (x in 250..260) selectedRaster()!!.tiles.putFillPixel(x, 20, Color.rgb(200, 100, 160))
    }
    private fun render(stack: LayerStack) = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.WHITE)
        stack.layers.filterIsInstance<RasterLayerRuntime>().forEach { layer ->
            stack.withClip(canvas, layer, blend = true) {
                layer.tiles.draw(canvas, Paint().apply { alpha = (layer.opacity * 255).toInt() })
            }
        }
    }
    @Test fun everyModeMatchesSamplingAcrossTileBoundaryAtPartialOpacity() {
        val stack = fixture()
        stack.setOpacity(.5f)
        LayerBlendMode.entries.forEach { mode ->
            stack.setBlendMode(mode)
            val bitmap = render(stack)
            for (x in listOf(254, 256, 258)) {
                val sampled = stack.colorAt(x.toFloat(), 20f)
                val actual = bitmap.getPixel(x, 20)
                assertEquals(mode.name, Color.red(actual) / 255f, sampled.red, .01f)
                assertEquals(mode.name, Color.green(actual) / 255f, sampled.green, .01f)
                assertEquals(mode.name, Color.blue(actual) / 255f, sampled.blue, .01f)
            }
            bitmap.recycle()
        }
    }
    @Test fun multiplyHasKnownResultAndUndoDoesNotAlterTiles() {
        val stack = fixture()
        val tiles = stack.allocatedTiles()
        stack.setBlendMode(LayerBlendMode.MULTIPLY)
        val bitmap = render(stack)
        val pixel = bitmap.getPixel(256, 20)
        assertEquals(100f, Color.red(pixel).toFloat(), 1f)
        assertEquals(78f, Color.green(pixel).toFloat(), 1f)
        assertEquals(50f, Color.blue(pixel).toFloat(), 1f)
        assertTrue(stack.selectedStore()!!.history.undo())
        assertEquals(LayerBlendMode.NORMAL, stack.selected().blendMode)
        assertTrue(stack.selectedStore()!!.history.redo())
        assertEquals(LayerBlendMode.MULTIPLY, stack.selected().summary().blendMode)
        assertEquals(tiles, stack.allocatedTiles())
        stack.duplicateSelected()
        assertEquals(LayerBlendMode.MULTIPLY, stack.selected().blendMode)
        bitmap.recycle()
    }
    @Test fun modesSurviveSaveLoadExportAndRecording() {
        val stack = fixture()
        stack.setBlendMode(LayerBlendMode.SCREEN)
        val library = DrawingLibrary(context)
        val id = library.newId()
        val snapshot = stack.snapshot()
        val file = File(context.cacheDir, "blend-export.png")
        val expected = render(stack)
        val recorder = ProcessRecorder(512, 256)
        try {
            ProjectPersistence.save(context.contentResolver, library, id, "Blends", snapshot).getOrThrow()
            val loaded = ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
            val restored = LayerStack(context.contentResolver, 512, 256) {}
            restored.replaceWith(loaded)
            assertEquals(LayerBlendMode.SCREEN, restored.selected().blendMode)
            ProjectPersistence.export(context.contentResolver, Uri.fromFile(file), snapshot, ExportFormat.PNG, 100, 1f, true).getOrThrow()
            val exported = BitmapFactory.decodeFile(file.path)
            assertEquals(expected.getPixel(256, 20), exported.getPixel(256, 20))
            exported.recycle()
            recorder.sync(stack, false)
            val recording = recorder.captureSnapshot().get(5, TimeUnit.SECONDS)
            ProcessReplay(recording).use { replay ->
                val frame = replay.frame(recording.actionCount - 1)
                assertEquals(expected.getPixel(256, 20), frame.getPixel(256, 20))
                frame.recycle()
            }
        } finally { recorder.detach(); expected.recycle(); snapshot.recycle(); file.delete(); library.delete(id) }
    }
}
