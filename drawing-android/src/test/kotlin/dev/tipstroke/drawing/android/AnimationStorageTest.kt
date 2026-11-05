package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import dev.tipstroke.core.geometry.TileCoordinate
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
class AnimationStorageTest {
    private fun tile(color: Int) = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun drain() {
        AnimationStorage.executor.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun editedCelsSpillOneAtATimeAndReloadExactPixels() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "spill-test-${System.nanoTime()}")
        val stack = LayerStack(context.contentResolver, 512, 512) {}
        val animation = AnimationRuntime(stack, false, storage = CelStorage(root))
        repeat(40) { index ->
            stack.selectedRaster()!!.tiles.replaceTiles(mapOf(TileCoordinate(1, 1) to tile(Color.rgb(index, 50, 100))))
            animation.addBlankFrame()
        }
        animation.trimCleanFrames(setOf(40))
        repeat(42) { drain() }
        val cels = animation.rasterCels.values.single().values
        assertEquals(0L, cels.sumOf { it.allocatedTileBytes })
        assertEquals(0L, animation.inactiveTileBytes())
        assertEquals(40, root.listFiles()!!.size)
        val first = cels.first()
        assertFalse(first.isResident)
        var ready = false
        animation.prepareFrames(listOf(0)) { it.getOrThrow(); animation.select(0); ready = true }
        AnimationDecoding.executor.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ready)
        assertEquals(Color.rgb(0, 50, 100), first.colorAt(300, 300))
        root.deleteRecursively()
    }

    @Test fun editWhileCompressionIsQueuedCannotInstallStalePixels() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "race-test-${System.nanoTime()}")
        val stack = LayerStack(context.contentResolver, 256, 256) {}
        val animation = AnimationRuntime(stack, false, storage = CelStorage(root))
        val first = stack.selectedRaster()!!.tiles
        first.replaceTiles(mapOf(TileCoordinate(0, 0) to tile(Color.RED)))
        animation.addBlankFrame()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        AnimationStorage.executor.execute { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            animation.trimCleanFrames(setOf(1))
            animation.select(0)
            first.replaceTiles(mapOf(TileCoordinate(0, 0) to tile(Color.BLUE)))
        } finally { release.countDown() }
        drain()
        assertEquals(Color.BLUE, first.colorAt(10, 10))
        assertNull(first.encodedBacking)
        animation.select(1); animation.trimCleanFrames(setOf(1)); drain()
        val decoded = TileStore.decodeTiles(first.encodedBacking!!)
        assertEquals(Color.BLUE, decoded.values.single().getPixel(10, 10))
        decoded.values.forEach(Bitmap::recycle)
        root.deleteRecursively()
    }

    @Test fun suspendedRecordingRetainsEvenEmptyCelHistoryUntilItFinishes() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "suspended-${System.nanoTime()}")
        val stack = LayerStack(context.contentResolver, 256, 256) {}
        val animation = AnimationRuntime(stack, false, storage = CelStorage(root))
        val original = stack.selectedRaster()!!.tiles
        val stroke = dev.tipstroke.core.drawing.CompletedStroke(listOf(
            dev.tipstroke.core.drawing.StrokeSample(0, dev.tipstroke.core.geometry.Point(5f, 5f), 1f, 0f, 0f, 0L, 0,
                dev.tipstroke.core.drawing.PointerKind.STYLUS)),
            dev.tipstroke.core.drawing.StrokeStyle(dev.tipstroke.core.model.BrushPreset.Ink, 1f, 1f,
                dev.tipstroke.core.model.RgbaColor(0f, 0f, 0f), dev.tipstroke.core.model.BlendBehavior.PAINT,
                pixelTool = dev.tipstroke.core.drawing.PixelTool.PENCIL))
        original.commit(stroke)
        assertTrue(original.history.undo())
        assertEquals(0, original.allocatedTileCount)
        assertTrue(original.history.estimatedBytes > 0)
        animation.addBlankFrame()
        animation.trimmingSuspended = true
        animation.trimCleanFrames(setOf(1)); drain()
        assertTrue(original.history.canRedo())
        animation.trimmingSuspended = false
        animation.trimCleanFrames(setOf(1)); drain()
        assertEquals(0L, original.history.estimatedBytes)
        assertNotNull(original.encodedBacking)
        assertEquals(0, original.encodedBacking!!.size)
        root.deleteRecursively()
    }

    @Test fun diskFailureKeepsOriginalArtworkResidentAndReportsOnce() {
        val context = RuntimeEnvironment.getApplication()
        val blocker = File(context.filesDir, "blocked-${System.nanoTime()}").apply { writeText("file") }
        val stack = LayerStack(context.contentResolver, 256, 256) {}
        var failures = 0
        val animation = AnimationRuntime(stack, false, storage = CelStorage(File(blocker, "tiles")), storageError = { failures++ })
        val original = stack.selectedRaster()!!.tiles
        original.replaceTiles(mapOf(TileCoordinate(0, 0) to tile(Color.RED)))
        animation.addBlankFrame(); animation.trimCleanFrames(setOf(1)); drain()
        assertEquals(1, failures)
        assertTrue(original.isResident)
        assertEquals(Color.RED, original.colorAt(10, 10))
        assertNull(original.encodedBacking)
        animation.trimCleanFrames(setOf(1)); drain()
        assertEquals(1, failures)
        blocker.delete()
    }
}
