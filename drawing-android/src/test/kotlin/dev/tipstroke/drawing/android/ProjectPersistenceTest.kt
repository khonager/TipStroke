package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.LayerId
import dev.tipstroke.core.model.ImageTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectPersistenceTest {
    @Test fun duplicatesDrawingIntoAnIndependentProject() {
        val context = RuntimeEnvironment.getApplication()
        val library = DrawingLibrary(context)
        val id = library.newId()
        val layerId = LayerId("duplicate-source-layer")
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(12, 18, Color.MAGENTA) }
        val snapshot = DrawingSnapshot(
            320, 240, layerId,
            listOf(SavedRasterSnapshot(layerId, "Paint", true, 1f, mapOf(TileCoordinate(0, 0) to tile))),
        )
        ProjectPersistence.save(context.contentResolver, library, id, "Original", snapshot).getOrThrow()

        val duplicate = library.duplicate(id).getOrThrow()
        val loaded = ProjectPersistence.load(library.projectDirectory(duplicate.id)).getOrThrow()

        assertNotEquals(id, duplicate.id)
        assertEquals("Original copy", duplicate.name)
        assertEquals(Color.MAGENTA, (loaded.layers.single() as LoadedRaster).tiles.getValue(TileCoordinate(0, 0)).getPixel(12, 18))
        loaded.layers.filterIsInstance<LoadedRaster>().flatMap { it.tiles.values }.forEach(Bitmap::recycle)
        snapshot.recycle()
        assertTrue(library.delete(id))
        assertTrue(library.delete(duplicate.id))
    }

    @Test fun galleryOrderAndStacksPersistAndDissolveWithoutTouchingProjects() {
        val context = RuntimeEnvironment.getApplication()
        val library = DrawingLibrary(context)
        val ids = List(3) { library.newId() }
        ids.forEachIndexed { index, id ->
            val layer = LayerId("gallery-layer-$index")
            val snapshot = DrawingSnapshot(64, 64, layer, listOf(SavedRasterSnapshot(layer, "Paint", true, 1f, emptyMap())))
            ProjectPersistence.save(context.contentResolver, library, id, "Drawing $index", snapshot).getOrThrow()
            snapshot.recycle()
        }

        assertTrue(library.reorderTopLevel("drawing:${ids[2]}", "drawing:${ids[0]}", placeAfter = false))
        val reordered = library.galleryItems().filterIsInstance<GalleryDrawing>().filter { it.drawing.id in ids }
        assertTrue(reordered.indexOfFirst { it.drawing.id == ids[2] } < reordered.indexOfFirst { it.drawing.id == ids[0] })

        val stackId = requireNotNull(library.stackDrawing(ids[1], "drawing:${ids[0]}"))
        var stack = library.galleryItems().filterIsInstance<GalleryStack>().single { it.id == stackId }
        assertEquals(listOf(ids[0], ids[1]), stack.drawings.map(DrawingSummary::id))
        assertTrue(library.renameStack(stackId, "References"))
        assertTrue(library.reorderInStack(stackId, ids[1], ids[0], placeAfter = false))
        stack = library.galleryItems().filterIsInstance<GalleryStack>().single { it.id == stackId }
        assertEquals("References", stack.name)
        assertEquals(listOf(ids[1], ids[0]), stack.drawings.map(DrawingSummary::id))

        assertTrue(library.moveOutOfStack(stackId, ids[1]))
        assertTrue(library.galleryItems().none { it is GalleryStack && it.id == stackId })
        assertTrue(ids.all(library::exists))
        ids.forEach { assertTrue(library.delete(it)) }
    }

    @Test fun savesListsAndReloadsSparseRasterLayers() {
        val context = RuntimeEnvironment.getApplication()
        val library = DrawingLibrary(context)
        val id = library.newId()
        val layerId = LayerId("paint-test")
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(12, 18, Color.RED) }
        val snapshot = DrawingSnapshot(
            640, 480, layerId,
            listOf(SavedRasterSnapshot(
                layerId, "Paint 1", true, .65f, mapOf(TileCoordinate(0, 0) to tile),
                mapOf(Color.RED to 12_345L),
            )),
        )

        val saved = ProjectPersistence.save(context.contentResolver, library, id, "Persistence test", snapshot).getOrThrow()
        assertTrue(saved.thumbnailFile.isFile)
        assertEquals(id, library.list().first { it.id == id }.id)

        val loaded = ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
        assertEquals(640, loaded.widthPx)
        assertEquals(layerId, loaded.selectedId)
        val raster = loaded.layers.single() as LoadedRaster
        assertEquals(.65f, raster.opacity, .001f)
        assertEquals(12_345L, raster.colorUsage[Color.RED])
        assertEquals(Color.RED, raster.tiles.getValue(TileCoordinate(0, 0)).getPixel(12, 18))

        raster.tiles.values.forEach(Bitmap::recycle)
        snapshot.recycle()
        assertTrue(library.delete(id))
    }

    @Test fun exportsFiniteCanvasPngThroughLayerCompositor() {
        val context = RuntimeEnvironment.getApplication()
        val layerId = LayerId("export-paint")
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val snapshot = DrawingSnapshot(
            320, 240, layerId,
            listOf(SavedRasterSnapshot(layerId, "Paint", true, 1f, mapOf(TileCoordinate(0, 0) to tile))),
        )
        val output = java.io.File.createTempFile("tipstroke-export", ".png")

        ProjectPersistence.export(context.contentResolver, Uri.fromFile(output), snapshot, ExportFormat.PNG, 100, .5f, false).getOrThrow()
        val decoded = BitmapFactory.decodeFile(output.absolutePath)
        assertEquals(160, decoded.width)
        assertEquals(120, decoded.height)
        assertEquals(Color.BLUE, decoded.getPixel(10, 10))

        decoded.recycle(); snapshot.recycle(); output.delete()
    }

    @Test fun infersColorUsageWhenLegacyManifestHasNoPaletteMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val library = DrawingLibrary(context)
        val id = library.newId()
        val layerId = LayerId("legacy-paint")
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val snapshot = DrawingSnapshot(
            256, 256, layerId,
            listOf(SavedRasterSnapshot(layerId, "Paint", true, 1f, mapOf(TileCoordinate(0, 0) to tile))),
        )
        ProjectPersistence.save(context.contentResolver, library, id, "Legacy palette", snapshot).getOrThrow()
        val manifest = java.io.File(library.projectDirectory(id), DrawingLibrary.MANIFEST)
        val json = JSONObject(manifest.readText())
        json.getJSONArray("layers").getJSONObject(0).remove("colorUsage")
        manifest.writeText(json.toString())

        val loaded = ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
        assertTrue((loaded.layers.single() as LoadedRaster).colorUsage.isNotEmpty())

        loaded.layers.filterIsInstance<LoadedRaster>().flatMap { it.tiles.values }.forEach(Bitmap::recycle)
        snapshot.recycle()
        assertTrue(library.delete(id))
    }

    @Test fun imageMasksPersistAndEraseWithoutChangingTheOriginalAsset() {
        val context = RuntimeEnvironment.getApplication()
        val library = DrawingLibrary(context)
        val id = library.newId()
        val layerId = LayerId("masked-image")
        val sourceFile = java.io.File.createTempFile("tipstroke-source", ".png")
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also { source ->
            source.eraseColor(Color.RED)
            sourceFile.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            source.recycle()
        }
        val mask = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(10, 10, Color.WHITE) }
        val snapshot = DrawingSnapshot(
            32, 32, layerId,
            listOf(SavedImageSnapshot(
                layerId, "Original", true, 1f, Uri.fromFile(sourceFile), 32, 32,
                ImageTransform(16f, 16f), mapOf(TileCoordinate(0, 0) to mask),
            )),
        )

        ProjectPersistence.save(context.contentResolver, library, id, "Masked", snapshot).getOrThrow()
        val loaded = ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
        val loadedImage = loaded.layers.single() as LoadedImage
        assertEquals(Color.WHITE, loadedImage.maskTiles.getValue(TileCoordinate(0, 0)).getPixel(10, 10))
        assertEquals(32, loadedImage.originalWidthPx)
        val copiedSource = BitmapFactory.decodeFile(loadedImage.assetFile.absolutePath)
        assertEquals(Color.RED, copiedSource.getPixel(10, 10))

        val output = java.io.File.createTempFile("tipstroke-masked-export", ".png")
        ProjectPersistence.export(context.contentResolver, Uri.fromFile(output), snapshot, ExportFormat.PNG, 100, 1f, false).getOrThrow()
        val exported = BitmapFactory.decodeFile(output.absolutePath)
        assertEquals(Color.WHITE, exported.getPixel(10, 10))
        assertEquals(Color.RED, exported.getPixel(20, 20))

        copiedSource.recycle(); exported.recycle()
        loadedImage.maskTiles.values.forEach(Bitmap::recycle)
        snapshot.recycle(); output.delete(); sourceFile.delete(); library.delete(id)
    }
}
