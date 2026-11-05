package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
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
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResolutionReductionTest {
    private fun tile(color: Int) = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun quarterResolutionMatchesWholeBitmapFilteringAcrossTileBoundaries() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "resample-${System.nanoTime()}")
        val source = TileStore(1537, 513)
        val tiles = buildMap {
            for (y in 0..2) for (x in 0..6) put(TileCoordinate(x, y), tile(if ((x + y) % 2 == 0) Color.RED else Color.BLUE))
        }
        source.replaceTiles(tiles)
        val encoded = ResolutionReduction.resample(source, 385, 129, .25f, CelStorage(root))
        val result = TileStore(385, 129).apply { replaceTiles(TileStore.decodeTiles(encoded)) }
        val full = Bitmap.createBitmap(1537, 513, Bitmap.Config.ARGB_8888)
        source.draw(Canvas(full), Paint())
        val reference = Bitmap.createBitmap(385, 129, Bitmap.Config.ARGB_8888)
        Canvas(reference).apply { scale(.25f, .25f); drawBitmap(full, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)) }
        for (y in 0 until 128) for (x in 0 until 384) assertEquals("pixel $x,$y", reference.getPixel(x, y), result.colorAt(x, y))
        assertEquals(2, result.allocatedTileCount)
        full.recycle(); reference.recycle(); result.discard(); source.discard(); root.deleteRecursively()
    }

    @Test fun wholeProjectReductionKeepsIdsTimingsImageMasksAndColdCels() {
        val context = RuntimeEnvironment.getApplication()
        val library = DrawingLibrary(context)
        val root = File(context.filesDir, "reduce-${System.nanoTime()}")
        val storage = CelStorage(root)
        val stack = LayerStack(context.contentResolver, 512, 512) {}
        val layer = stack.selectedRaster()!!
        layer.groupId = "group"
        layer.tiles.replaceTiles(mapOf(TileCoordinate(1, 1) to tile(Color.RED)))
        val animation = AnimationRuntime(stack, false)
        animation.addBlankFrame()
        val second = stack.selectedRaster()!!.tiles
        second.deferTiles(mapOf(TileCoordinate(0, 0) to storage.store(tile(Color.BLUE))))
        animation.select(0)
        val imageFile = File(context.filesDir, "resolution-photo.png")
        tile(Color.GREEN).also { bitmap -> imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        val image = ImageLayerRuntime(LayerId("photo"), "Photo", source = OriginalImageSource(context.contentResolver, Uri.fromFile(imageFile)) {},
            transform = ImageTransform(300f, 200f, .8f, 25f))
        image.mask.replaceTiles(mapOf(TileCoordinate(0, 0) to tile(Color.WHITE)))
        stack.layers += image; animation.register(image)
        val frames = animation.frames.toList()
        val operation = ResolutionReduction(stack, animation, .5f, storage)
        try { operation.prepare(); operation.commit() } finally { operation.release() }
        assertEquals(256, stack.canvasWidth)
        assertEquals(layer.id, stack.layers.first().id)
        assertEquals("group", layer.groupId)
        assertEquals(frames, animation.frames)
        assertEquals(Color.RED, layer.tiles.colorAt(150, 150))
        assertEquals(ImageTransform(150f, 100f, .4f, 25f), image.transform)
        assertEquals(image.transform, animation.celImageTransform(image.id, frames[0].id))
        assertEquals(Color.WHITE, image.mask.colorAt(250, 250))
        assertFalse(animation.celRaster(layer.id, frames[1].id)!!.isResident)
        val snapshot = stack.snapshot(animation = animation)
        val id = library.newId()
        try {
            ProjectPersistence.save(context.contentResolver, library, id, "Reduced", snapshot).getOrThrow()
            val reopened = ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
            assertEquals(256, reopened.widthPx)
            assertEquals(Color.BLUE, reopened.animation!!.rasterCels.getValue(layer.id).getValue(frames[1].id).values.single().getPixel(50, 50))
        } finally { snapshot.recycle(); library.delete(id); root.deleteRecursively(); imageFile.delete() }
    }

    @Test fun threeMinuteTimelineReducesWithoutMakingColdFramesResident() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "long-reduce-${System.nanoTime()}")
        val storage = CelStorage(root)
        val stack = LayerStack(context.contentResolver, 512, 512) {}
        val frames = List(2160) { AnimationFrame(FrameId("frame-$it")) }
        val animation = AnimationRuntime(stack, false, frames)
        val encoded = tile(Color.BLUE).let { bitmap -> storage.store(bitmap).also { bitmap.recycle() } }
        // A three-minute 12 FPS timeline with sparse authored drawings/holds.
        for (frame in frames.drop(1)) animation.rasterCels.getValue(stack.selectedId)[frame.id] =
            TileStore(512, 512).apply { deferTiles(mapOf(TileCoordinate(1, 1) to encoded)) }
        val operation = ResolutionReduction(stack, animation, .5f, storage)
        try { operation.prepare(); operation.commit() } finally { operation.release() }
        assertEquals(180, animation.timeline.totalTicks / animation.fps)
        assertEquals(2160, animation.frames.size)
        assertEquals(0L, animation.inactiveTileBytes())
        val last = animation.celRaster(stack.selectedId, frames.last().id)!!
        assertFalse(last.isResident)
        val pixels = TileStore.decodeTiles(last.encodedBacking!!)
        assertEquals(Color.BLUE, pixels.values.single().getPixel(150, 150))
        pixels.values.forEach(Bitmap::recycle)
        root.deleteRecursively()
    }

    @Test fun reducedMediaThumbnailAndColorPickerUseTheSameScale() {
        val context = RuntimeEnvironment.getApplication()
        val stack = LayerStack(context.contentResolver, 512, 256) {}
        val animation = AnimationRuntime(stack, false)
        val saved = animation.snapshot().copy(media = MediaSource(File("unused.mp4"), "video", 512, 256, 180_000_000))
        animation.restore(saved)
        val preview = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            Canvas(this).drawRect(256f, 0f, 512f, 256f, Paint().apply { color = Color.BLUE })
        }
        animation.videoColorPreview = preview
        val root = File(context.filesDir, "media-reduce-${System.nanoTime()}")
        val operation = ResolutionReduction(stack, animation, .5f, CelStorage(root))
        try { operation.prepare(); operation.commit() } finally { operation.release() }
        assertEquals(.5f, animation.media!!.canvasScale)
        assertEquals(Color.RED, stack.mediaColorAt!!(100f, 50f))
        assertEquals(Color.BLUE, stack.mediaColorAt!!(200f, 50f))
        assertEquals(0, stack.mediaColorAt!!(260f, 50f))
        val thumbnail = stack.renderAnimationFrameThumbnail(animation, animation.currentFrame.id, 256, preview)
        assertEquals(Color.RED, thumbnail.getPixel(100, 100))
        assertEquals(Color.BLUE, thumbnail.getPixel(200, 100))
        thumbnail.recycle(); preview.recycle(); root.deleteRecursively()
    }

    @Test fun failedReductionLeavesDimensionsAndPixelsUntouched() {
        val context = RuntimeEnvironment.getApplication()
        val blocker = File(context.filesDir, "reduction-blocker").apply { writeText("file") }
        val stack = LayerStack(context.contentResolver, 512, 512) {}
        stack.selectedRaster()!!.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to tile(Color.RED)))
        val operation = ResolutionReduction(stack, null, .5f, CelStorage(File(blocker, "tiles")))
        try { assertTrue(runCatching { operation.prepare() }.isFailure) } finally { operation.release() }
        assertEquals(512, stack.canvasWidth)
        assertEquals(Color.RED, stack.selectedRaster()!!.tiles.colorAt(100, 100))
        blocker.delete()
    }

    @Test fun surfaceReductionIncreasesLayerAllowanceAndPersistsMediaScale() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(android.app.ActivityManager::class.java)).setMemoryClass(256)
        val surface = DrawingSurface(context).apply { configureBlank(2048, 2048) }
        val initialCapacity = surface.maximumLayers
        var outcome: Result<Unit>? = null
        surface.reduceResolution(.5f) { outcome = it }
        ProjectPersistence.executor.submit {}.get(20, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        outcome!!.getOrThrow()
        assertEquals(1024, surface.canvasWidthPx)
        assertTrue(surface.maximumLayers > initialCapacity)
        val directory = File(context.filesDir, "media-scale-test")
        File(directory, MediaSource.ASSET_PATH).apply { parentFile!!.mkdirs(); writeText("source") }
        val source = MediaSource(File(directory, MediaSource.ASSET_PATH), "video", 2048, 1024, 180_000_000, 30.0, .5f)
        assertEquals(source, MediaSource.read(directory, source.json()))
        assertEquals(1f, MediaSource.read(directory, source.json().apply { remove("canvasScale") }).canvasScale)
        directory.deleteRecursively()
    }
}
