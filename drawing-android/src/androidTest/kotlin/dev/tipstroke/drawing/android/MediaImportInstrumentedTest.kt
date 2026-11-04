package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class MediaImportInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun photoUsesOrientedPixelDimensionsAndOpensWithPaintAboveImage() {
        val file = File(context.cacheDir, "import-photo.jpg")
        val library = DrawingLibrary(context)
        var id: String? = null
        try {
            Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.RED)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                bitmap.recycle()
            }
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val imported = MediaImporter.importNow(context, library, Uri.fromFile(file))
            id = imported.id
            assertEquals(40, imported.widthPx)
            assertEquals(80, imported.heightPx)
            file.delete()
            val project = ProjectPersistence.load(library.projectDirectory(imported.id)).getOrThrow()
            assertNull(project.animation)
            assertTrue(project.layers[0] is LoadedImage)
            assertTrue(project.layers[1] is LoadedRaster)
            assertEquals(project.layers[1].id, project.selectedId)
            val image = project.layers[0] as LoadedImage
            BitmapFactory.decodeFile(image.assetFile.path).also { decoded ->
                assertEquals(40, decoded.width); assertEquals(80, decoded.height); decoded.recycle()
            }
        } finally { file.delete(); id?.let(library::delete) }
    }

    @Test fun gifRemainsOneAssetAndExportsSparsePaintAtTheCorrectSourceTime() {
        val library = DrawingLibrary(context)
        val input = File(context.cacheDir, "import-animation.gif")
        val output = File(context.cacheDir, "import-export.zip")
        var id: String? = null
        var transferredId: String? = null
        try {
            makeClip(input, AnimationExportFormat.GIF, fps = 5)
            val imported = MediaImporter.importNow(context, library, Uri.fromFile(input))
            id = imported.id
            input.delete()
            var loaded = ProjectPersistence.load(library.projectDirectory(imported.id), lazyAnimation = true).getOrThrow()
            assertEquals(64, loaded.widthPx)
            val animation = requireNotNull(loaded.animation)
            val media = requireNotNull(animation.media)
            assertEquals("gif", media.kind)
            assertTrue(animation.rasterCels.values.all { it.isEmpty() })
            assertFalse(File(library.projectDirectory(imported.id), "animation/cels").exists())
            MediaFrameDecoder(media).use { decoder ->
                assertColor(decoder.frame(0, 64), Color.RED)
                assertColor(decoder.frame(300_000, 64), Color.BLUE)
            }
            val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(10, 10, Color.GREEN) }
            val painted = animation.copy(rasterCels = mapOf(loaded.selectedId to mapOf(
                animation.frames.first().id to mapOf(TileCoordinate(0, 0) to tile),
            )))
            val snapshot = DrawingSnapshot(64, 48, loaded.selectedId,
                listOf(SavedRasterSnapshot(loaded.selectedId, "Paint 1", true, 1f, emptyMap())), animation = painted)
            try {
                repeat(2) { ProjectPersistence.save(context.contentResolver, library, imported.id, "Painted GIF", snapshot).getOrThrow() }
                ProjectPersistence.exportAnimation(context.contentResolver, Uri.fromFile(output), snapshot,
                    AnimationExportFormat.PNG_SEQUENCE, 1f).getOrThrow()
                // Check every source sample; drawing holds are independent of source samples.
                ZipInputStream(output.inputStream()).use { zip ->
                    var frame = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name.endsWith(".png")) {
                            val bytes = zip.readBytes()
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            val time = frame * 1_000_000L / 50
                            assertEquals(if (time < 1_000_000L / 12) Color.GREEN else if (time < 200_000) Color.RED else Color.BLUE, bitmap.getPixel(10, 10))
                            bitmap.recycle(); frame++
                        }
                    }
                    assertEquals(20, frame)
                }
                val archive = ByteArrayOutputStream()
                ProjectTransfer.exportProjects(library, listOf(imported.id), archive)
                assertEquals(1, ProjectTransfer.importProjects(library, ByteArrayInputStream(archive.toByteArray())))
                transferredId = library.list().first { it.id != imported.id && it.name == "Painted GIF" }.id
                loaded = ProjectPersistence.load(library.projectDirectory(transferredId!!), lazyAnimation = true).getOrThrow()
                assertEquals(animation.frames, loaded.animation!!.frames)
                assertTrue(loaded.animation!!.media!!.file.isFile)
            } finally { snapshot.recycle() }
        } finally { input.delete(); output.delete(); id?.let(library::delete); transferredId?.let(library::delete) }
    }

    @Test fun sixtyFpsVideoImportsWithoutRasterizingItsFrames() {
        val file = File(context.cacheDir, "import-video.mp4")
        val output = File(context.cacheDir, "media-roundtrip.mp4")
        val library = DrawingLibrary(context)
        var id: String? = null
        try {
            makeClip(file, AnimationExportFormat.MP4, fps = 60)
            val imported = MediaImporter.importNow(context, library, Uri.fromFile(file))
            id = imported.id
            file.delete()
            val loaded = ProjectPersistence.load(library.projectDirectory(imported.id)).getOrThrow()
            val animation = loaded.animation!!
            assertEquals(12, animation.fps)
            assertEquals(60.0, animation.media!!.frameRate, 0.01)
            assertEquals(1, animation.frames.size)
            assertTrue(animation.rasterCels.values.all { it.isEmpty() })
            assertEquals(1, loaded.layers.size)
            MediaFrameDecoder(animation.media!!).use { decoder ->
                assertColor(decoder.frame(0, 64), Color.RED, lossy = true)
                assertColor(decoder.frame(20_000, 64), Color.BLUE, lossy = true)
            }
            val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val snapshot = DrawingSnapshot(64, 48, loaded.selectedId,
                listOf(SavedRasterSnapshot(loaded.selectedId, "Paint 1", true, 1f, emptyMap())),
                animation = animation.copy(frames = listOf(animation.frames.first().copy(durationUs = 16_666)),
                    rasterCels = mapOf(loaded.selectedId to mapOf(animation.frames.first().id to mapOf(TileCoordinate(0, 0) to tile)))))
            try { ProjectPersistence.exportAnimation(context.contentResolver, Uri.fromFile(output), snapshot, AnimationExportFormat.MP4, 1f).getOrThrow() }
            finally { snapshot.recycle() }
            MediaFrameDecoder(MediaSource(output, "video", 64, 48, animation.media!!.durationUs)).use { decoder ->
                assertColor(decoder.frame(0, 64), Color.GREEN, lossy = true)
                assertColor(decoder.frame(20_000, 64), Color.BLUE, lossy = true)
            }
        } finally { file.delete(); output.delete(); id?.let(library::delete) }
    }

    @Test fun legacySourceSlotsMigrateWithoutLosingPaintAndKeepTheScrubCursor() {
        val library = DrawingLibrary(context)
        val file = File(context.cacheDir, "legacy.gif")
        val output = File(context.cacheDir, "cursor.png")
        makeClip(file, AnimationExportFormat.GIF, 5)
        val imported = MediaImporter.importNow(context, library, Uri.fromFile(file))
        val loaded = ProjectPersistence.load(library.projectDirectory(imported.id)).getOrThrow()
        val paintedId = loaded.animation!!.frames.first().id
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(10, 10, Color.GREEN) }
        val snapshot = DrawingSnapshot(64, 48, loaded.selectedId,
            listOf(SavedRasterSnapshot(loaded.selectedId, "Paint", true, 1f, emptyMap())),
            animation = loaded.animation!!.copy(rasterCels = mapOf(loaded.selectedId to mapOf(paintedId to mapOf(TileCoordinate(0, 0) to tile)))))
        try {
            ProjectPersistence.save(context.contentResolver, library, imported.id, "Legacy", snapshot).getOrThrow()
            val manifest = File(library.projectDirectory(imported.id), DrawingLibrary.MANIFEST)
            val json = org.json.JSONObject(manifest.readText()).put("schemaVersion", 6)
            val animation = json.getJSONObject("animation")
            animation.remove("mediaPositionUs")
            animation.getJSONObject("media").remove("frameRate")
            animation.put("fps", 20).put("selectedIndex", 2).put("frames", org.json.JSONArray().apply {
                put(org.json.JSONObject().put("id", "empty-before").put("sourceTimeUs", 0).put("exposure", 1))
                put(org.json.JSONObject().put("id", paintedId.value).put("sourceTimeUs", 100_000).put("exposure", 2))
                put(org.json.JSONObject().put("id", "empty-after").put("sourceTimeUs", 300_000).put("exposure", 1))
            })
            manifest.writeText(json.toString())
            val source = File(library.projectDirectory(imported.id), MediaSource.ASSET_PATH)
            assertTrue(source.renameTo(File(library.projectDirectory(imported.id), "assets/timeline.source")))
            val migrated = ProjectPersistence.load(library.projectDirectory(imported.id), lazyAnimation = true).getOrThrow().animation!!
            assertEquals(listOf(paintedId), migrated.frames.map { it.id })
            assertEquals(100_000L, migrated.frames.single().durationUs)
            assertEquals(300_000L, migrated.mediaPositionUs)
            assertEquals(50.0, migrated.media!!.frameRate, 0.001)
            assertTrue(migrated.encodedRasterCels[loaded.selectedId]!!.containsKey(paintedId))
            val updated = snapshot.copy(animation = migrated)
            ProjectPersistence.save(context.contentResolver, library, imported.id, "Migrated", updated).getOrThrow()
            val reopened = ProjectPersistence.load(library.projectDirectory(imported.id), lazyAnimation = true).getOrThrow().animation!!
            assertEquals(migrated.frames, reopened.frames)
            assertEquals(300_000L, reopened.mediaPositionUs)
            ProjectPersistence.export(context.contentResolver, Uri.fromFile(output), updated, ExportFormat.PNG, 100, 1f, false).getOrThrow()
            assertColor(BitmapFactory.decodeFile(output.path), Color.BLUE) // Cursor is in a gap, not the selected paint cel.
        } finally { snapshot.recycle(); file.delete(); output.delete(); library.delete(imported.id) }
    }

    @Test fun rapidSeeksKeepOnlyTheLatestPreviewAndCancellationKeepsTheDisplayedFrame() {
        val file = File(context.cacheDir, "seek.gif")
        makeClip(file, AnimationExportFormat.GIF, fps = 5)
        val preview = MediaPreview(MediaSource(file, "gif", 64, 48, 400_000))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val unblock = CountDownLatch(1)
        try {
            AnimationDecoding.executor.execute { unblock.await(10, TimeUnit.SECONDS) }
            val completed = CountDownLatch(1)
            val callbacks = mutableListOf<Long>()
            instrumentation.runOnMainSync {
                preview.prepare(0) { callbacks += 0 }
                preview.prepare(300_000) { callbacks += 300_000 }
                preview.prepare(20_000) { it.getOrThrow(); callbacks += 20_000; completed.countDown() }
            }
            unblock.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(listOf(20_000L), callbacks)
                assertEquals(20_000L, preview.timeUs)
                assertEquals(Color.RED, preview.bitmap!!.getPixel(10, 10))
            }
            val gate = CountDownLatch(1)
            AnimationDecoding.executor.execute { gate.await(10, TimeUnit.SECONDS) }
            instrumentation.runOnMainSync {
                preview.prepare(300_000) { fail("Cancelled request was delivered") }
                preview.cancelPending()
            }
            gate.countDown()
            AnimationDecoding.executor.submit {}.get(10, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(20_000L, preview.timeUs)
                assertEquals(Color.RED, preview.bitmap!!.getPixel(10, 10))
            }
        } finally {
            unblock.countDown()
            instrumentation.runOnMainSync { preview.clear() }
            file.delete()
        }
    }

    private fun makeClip(file: File, format: AnimationExportFormat, fps: Int) {
        val frames = List(2) { AnimationFrame(FrameId("source-$it")) }
        AnimationExporter.export(context.contentResolver, Uri.fromFile(file),
            SavedAnimationSnapshot(frames, 0, fps, PlaybackMode.ONCE, 0, 0, 0f, emptySet(), emptyMap(), emptyMap()), format) { index ->
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(if (index == 0) Color.RED else Color.BLUE) }
        }
    }

    private fun assertColor(bitmap: Bitmap, expected: Int, lossy: Boolean = false) {
        try {
            val actual = bitmap.getPixel(10, 10)
            if (!lossy) assertEquals(expected, actual)
            else for (shift in listOf(0, 8, 16)) assertTrue("Expected $expected, got $actual",
                kotlin.math.abs(((actual shr shift) and 255) - ((expected shr shift) and 255)) < 35)
        } finally { bitmap.recycle() }
    }
}
