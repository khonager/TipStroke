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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectTransferTest {
    @Test fun batchRoundTripPreservesProjectBytesAnimationAndStacksWithoutReplacingOriginals() {
        val context = RuntimeEnvironment.getApplication()
        val library = DrawingLibrary(context)
        library.root.deleteRecursively(); library.root.mkdirs()
        val layer = LayerId("paint")
        val frame = FrameId("frame")
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(2, 3, Color.RED) }
        val snapshot = DrawingSnapshot(256, 256, layer,
            listOf(SavedRasterSnapshot(layer, "Paint", true, .7f, emptyMap())),
            animation = SavedAnimationSnapshot(listOf(AnimationFrame(frame, 3)), 0, 12, PlaybackMode.PING_PONG,
                1, 1, .3f, emptySet(), mapOf(layer to mapOf(frame to mapOf(TileCoordinate(0, 0) to tile))), emptyMap()))
        val ids = List(2) { library.newId() }
        try {
            ids.forEachIndexed { index, id -> ProjectPersistence.save(context.contentResolver, library, id, "Drawing $index", snapshot).getOrThrow() }
            val stack = library.stackDrawing(ids[0], "drawing:${ids[1]}")!!
            library.renameStack(stack, "My animation")
            val bytes = ByteArrayOutputStream().also { ProjectTransfer.exportProjects(library, ids, it) }.toByteArray()
            assertEquals(2, ProjectTransfer.importProjects(library, ByteArrayInputStream(bytes)))
            assertEquals(4, library.list().size)
            assertEquals(2, library.galleryItems().filterIsInstance<GalleryStack>().size)
            val imported = library.list().filter { it.id !in ids }
            imported.forEach { drawing ->
                val original = library.list().first { it.id in ids && it.name == drawing.name }
                val relative = "animation/cels/paint/frame/0_0.png"
                assertArrayEquals(File(library.projectDirectory(original.id), relative).readBytes(), File(library.projectDirectory(drawing.id), relative).readBytes())
                val loaded = ProjectPersistence.load(library.projectDirectory(drawing.id)).getOrThrow()
                assertEquals(3, loaded.animation!!.frames.single().exposure)
                assertEquals(PlaybackMode.PING_PONG, loaded.animation.playbackMode)
                assertEquals(Color.RED, loaded.animation.rasterCels[layer]!![frame]!!.values.single().getPixel(2, 3))
                loaded.animation.rasterCels.values.forEach { cels -> cels.values.forEach { tiles -> tiles.values.forEach(Bitmap::recycle) } }
            }
        } finally { snapshot.recycle(); library.root.deleteRecursively() }
    }

    @Test fun multipleImagesExportAsZipWithDistinctNames() {
        val context = RuntimeEnvironment.getApplication()
        val library = DrawingLibrary(context)
        val layer = LayerId("paint")
        val snapshot = DrawingSnapshot(16, 16, layer,
            listOf(SavedRasterSnapshot(layer, "Paint", true, 1f, emptyMap())), backgroundColor = Color.BLUE)
        val ids = List(2) { library.newId() }
        val destination = File.createTempFile("media-test", ".zip")
        try {
            ids.forEach { ProjectPersistence.save(context.contentResolver, library, it, "Same name", snapshot).getOrThrow() }
            ProjectTransfer.exportMedia(context, library, ids, android.net.Uri.fromFile(destination), ExportFormat.PNG, null)
            java.util.zip.ZipFile(destination).use { zip ->
                assertEquals(2, zip.size())
                val entries = zip.entries().toList()
                assertNotEquals(entries[0].name, entries[1].name)
                entries.forEach { entry ->
                    val bitmap = zip.getInputStream(entry).use { android.graphics.BitmapFactory.decodeStream(it) }!!
                    assertEquals(Color.BLUE, bitmap.getPixel(0, 0))
                    bitmap.recycle()
                }
            }
        } finally { ids.forEach(library::delete); snapshot.recycle(); destination.delete() }
    }

    @Test fun invalidPackagesDoNotInstallAnyDrawingsOrEscapeStaging() {
        val library = DrawingLibrary(RuntimeEnvironment.getApplication())
        library.root.deleteRecursively(); library.root.mkdirs()
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("../escaped.txt")); zip.write("bad".toByteArray()); zip.closeEntry()
        }
        assertTrue(runCatching { ProjectTransfer.importProjects(library, ByteArrayInputStream(bytes.toByteArray())) }.isFailure)
        assertFalse(File(library.root, "escaped.txt").exists())
        assertTrue(library.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun unsupportedPackageVersionIsRejected() {
        val library = DrawingLibrary(RuntimeEnvironment.getApplication())
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("tipstroke.json"))
            zip.write("""{"format":"TipStroke","version":999,"drawings":[]}""".toByteArray())
        }
        assertTrue(runCatching { ProjectTransfer.importProjects(library, ByteArrayInputStream(bytes.toByteArray())) }.isFailure)
    }
}
