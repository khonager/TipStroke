package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.LayerId
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
}
