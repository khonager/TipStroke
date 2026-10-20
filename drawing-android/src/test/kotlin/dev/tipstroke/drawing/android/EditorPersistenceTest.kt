package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val library get() = DrawingLibrary(context)
    private fun flush() {
        ProjectPersistence.executor.submit {}.get(20, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun stack(surface: DrawingSurface) = (surface.getChildAt(0) as RasterCanvasView).layerStack

    @Test fun recreatedNewEditorLoadsPaintAndPositionedImageAfterPendingSaves() {
        val id = library.newId()
        val surface = DrawingSurface(context)
        var opened = false
        surface.openProject(library, id, true, 128, 128, "Keep me") {
            it.getOrThrow(); opened = true
        }
        flush()
        assertTrue(opened)
        assertEquals("Keep me", library.list().single { it.id == id }.name)
        stack(surface).selectedRaster()!!.tiles.commit(CompletedStroke(
            listOf(StrokeSample(1, Point(12f, 18f), 1f, 0f, 0f, 0, 0, PointerKind.STYLUS)),
            StrokeStyle(BrushPreset.Ink, 3f, 1f, RgbaColor(1f, 0f, 0f),
                BlendBehavior.PAINT, pixelTool = PixelTool.PENCIL),
        ))
        val source = File(context.cacheDir, "lifecycle-image.png")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.BLUE)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        surface.addImage(Uri.fromFile(source)).getOrThrow()
        val image = stack(surface).selectedImage()!!
        image.transform = ImageTransform(45f, 66f, 2f, 25f)

        // Simulate an autosave in progress, then edits + lock, then activity recreation.
        val release = CountDownLatch(1)
        ProjectPersistence.executor.execute { check(release.await(20, TimeUnit.SECONDS)) }
        try {
            var saved = 0
            surface.saveProject(library, id, "Keep me") { it.getOrThrow(); saved++ }
            image.transform = image.transform.copy(centerX = 88f)
            surface.setCanvasBackgroundColor(Color.YELLOW)
            surface.saveProject(library, id, "Keep me") { it.getOrThrow(); saved++ }
            val recreated = DrawingSurface(context)
            var restored = false
            // The original bug: the caller still said "new" after autosaving.
            recreated.openProject(library, id, createIfMissing = true) { it.getOrThrow(); restored = true }
            assertFalse(recreated.isEnabled)
            release.countDown()
            flush()
            assertEquals(2, saved)
            assertTrue(restored)
            val layers = stack(recreated).snapshot()
            try {
                assertEquals(128, layers.widthPx)
                assertEquals(Color.YELLOW, layers.backgroundColor)
                val raster = layers.layers.filterIsInstance<SavedRasterSnapshot>().single()
                assertEquals(Color.RED, raster.tiles.getValue(TileCoordinate(0, 0)).getPixel(12, 18))
                assertEquals(image.transform, layers.layers.filterIsInstance<SavedImageSnapshot>().single().transform)
                assertTrue(File(library.projectDirectory(id), "assets/${image.id.value}.source").isFile)
            } finally { layers.recycle() }
        } finally {
            release.countDown()
            flush()
            source.delete()
            library.delete(id)
        }
    }

    @Test fun failedOpenCannotSaveBlankOverCorruptProject() {
        val id = library.newId()
        val directory = library.projectDirectory(id).apply { mkdirs() }
        val manifest = File(directory, "manifest.json").apply { writeText("broken manifest") }
        val surface = DrawingSurface(context)
        var failure: Throwable? = null
        surface.openProject(library, id, true) { failure = it.exceptionOrNull() }
        flush()
        assertNotNull(failure)
        assertFalse(surface.isEnabled)
        var saveFailed = false
        surface.saveProject(library, id, "Overwrite") { saveFailed = it.isFailure }
        flush()
        assertTrue(saveFailed)
        assertEquals("broken manifest", manifest.readText())
        library.delete(id)
    }

    @Test fun restoredMissingDocumentIsNotReplacedWithBlank() {
        val id = library.newId()
        var failed = false
        DrawingSurface(context).openProject(library, id, false) { failed = it.isFailure }
        flush()
        assertTrue(failed)
        assertFalse(library.exists(id))
    }

    @Test fun galleryRecoversCommittedRevisionWhenProcessDiesBetweenRenames() {
        val id = library.newId()
        DrawingSurface(context).openProject(library, id, true, name = "Recovered") { it.getOrThrow() }
        flush()
        val directory = library.projectDirectory(id)
        val backup = File(library.root, ".$id.backup")
        assertTrue(directory.renameTo(backup))
        assertEquals("Recovered", library.list().single { it.id == id }.name)
        assertTrue(library.exists(id))
        assertFalse(backup.exists())
        library.delete(id)
    }
}
