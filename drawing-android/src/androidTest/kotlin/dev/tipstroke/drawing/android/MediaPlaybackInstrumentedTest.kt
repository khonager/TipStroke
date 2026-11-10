package dev.tipstroke.drawing.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class MediaPlaybackTestActivity : Activity()

@RunWith(AndroidJUnit4::class)
class MediaPlaybackInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertTrue(message, condition())
    }
    private fun makeVideo(file: File) {
        val frames = List(120) { AnimationFrame(FrameId("source-$it")) }
        AnimationExporter.export(context.contentResolver, Uri.fromFile(file),
            SavedAnimationSnapshot(frames, 0, 60, PlaybackMode.ONCE, 0, 0, 0f, emptySet(), emptyMap(), emptyMap()), AnimationExportFormat.MP4) { index ->
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(if (index < 60) Color.RED else Color.BLUE) }
        }
    }

    @Test fun nativeVideoPlaysAtSourceSpeedAndScrubsWithSparseDrawingOverlays() {
        val library = DrawingLibrary(context)
        val input = File(context.cacheDir, "native-playback.mp4")
        makeVideo(input)
        val imported = MediaImporter.importNow(context, library, Uri.fromFile(input))
        val project = ProjectPersistence.load(library.projectDirectory(imported.id)).getOrThrow()
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            for (y in 5..15) for (x in 5..15) setPixel(x, y, Color.GREEN)
        }
        val frame = project.animation!!.frames.first().copy(sourceTimeUs = 500_000, durationUs = 500_000)
        val snapshot = DrawingSnapshot(64, 48, project.selectedId,
            listOf(SavedRasterSnapshot(project.selectedId, "Paint", true, 1f, emptyMap())),
            animation = project.animation!!.copy(frames = listOf(frame), rasterCels = mapOf(project.selectedId to mapOf(frame.id to mapOf(TileCoordinate(0, 0) to tile)))))
        ProjectPersistence.save(context.contentResolver, library, imported.id, "Native video test", snapshot).getOrThrow()
        snapshot.recycle()
        val state = AtomicReference<AnimationUiState?>()
        val failure = AtomicReference<String?>()
        lateinit var surface: DrawingSurface
        val scenario = ActivityScenario.launch<MediaPlaybackTestActivity>(Intent(context, MediaPlaybackTestActivity::class.java))
        try {
            scenario.onActivity { activity ->
                surface = DrawingSurface(activity)
                surface.animationListener = { state.set(it) }
                surface.animationErrorListener = { failure.set(it) }
                activity.setContentView(surface)
                surface.loadProject(library, imported.id) { it.exceptionOrNull()?.let { error -> failure.set(error.toString()) } }
            }
            waitUntil("Native source prepared: ${failure.get()}") { state.get()?.let { it.hasMedia && !it.loading } == true || failure.get() != null }
            assertNull(failure.get())
            // A newly prepared player has never entered Started: seeking must not pause it.
            scenario.onActivity { surface.seekMedia(100_000) }
            waitUntil("Seek before first playback") { state.get()?.let { !it.loading && it.mediaPositionUs == 100_000L } == true || failure.get() != null }
            assertNull(failure.get())
            scenario.onActivity { surface.toggleAnimationPlayback(); surface.setAnimationFps(6) }
            val started = SystemClock.uptimeMillis()
            waitUntil("Source clock advanced") { (state.get()?.mediaPositionUs ?: 0) >= 1_200_000 || failure.get() != null }
            assertNull(failure.get())
            assertTrue("6 drawing FPS must not slow a 60 FPS video", SystemClock.uptimeMillis() - started < 4_000)
            assertEquals(1, state.get()!!.frames.size)
            scenario.onActivity {
                surface.seekMedia(100_000); surface.seekMedia(1_500_000); surface.seekMedia(600_000)
            }
            waitUntil("Latest scrub wins") { state.get()?.let { !it.loading && !it.playing && it.mediaPositionUs == 600_000L } == true }
            assertTrue(state.get()!!.mediaDrawingActive)
            // Screenshot verifies the actual hardware video and raster overlay share the canvas transform.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(150)
            val positions = FloatArray(4)
            scenario.onActivity {
                val raster = (0 until surface.childCount).map(surface::getChildAt).filterIsInstance<RasterCanvasView>().single()
                positions[0] = 10f; positions[1] = 10f; positions[2] = 40f; positions[3] = 30f
                raster.transformMatrix.mapPoints(positions)
                val location = IntArray(2); raster.getLocationOnScreen(location)
                for (i in 0..3) positions[i] += location[i % 2]
            }
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "media-overlay.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            try {
                assertColor(screenshot.getPixel(positions[0].toInt(), positions[1].toInt()), Color.GREEN)
                assertColor(screenshot.getPixel(positions[2].toInt(), positions[3].toInt()), Color.RED)
            } finally { screenshot.recycle() }
            scenario.onActivity { surface.seekMedia(1_500_000) }
            waitUntil("Gap has no drawing") { state.get()?.let { !it.loading && it.mediaPositionUs == 1_500_000L && !it.mediaDrawingActive } == true }
            scenario.onActivity { surface.addAnimationFrame(); surface.setAnimationExposure(3) }
            waitUntil("New drawing selected") { state.get()?.let { !it.loading && it.frames.size == 2 && it.mediaDrawingActive } == true }
            assertEquals(500_000L, state.get()!!.frames[state.get()!!.selectedIndex].durationUs)
            scenario.onActivity { surface.toggleAnimationPlayback() }
            waitUntil("Playback resumed") { state.get()?.playing == true }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            waitUntil("Backgrounding pauses video") { state.get()?.playing == false }
            val pausedTime = state.get()!!.mediaPositionUs
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            waitUntil("Surface restored at paused cursor") { state.get()?.let { !it.loading && !it.playing && it.mediaPositionUs == pausedTime } == true }
            scenario.onActivity { surface.seekMedia(state.get()!!.mediaDurationUs - 1) }
            waitUntil("Seek to end") { state.get()?.let { !it.loading && it.mediaPositionUs == it.mediaDurationUs - 1 } == true }
            scenario.onActivity { surface.toggleAnimationPlayback() }
            waitUntil("One Play tap restarts from the end") { state.get()?.let { it.playing && it.mediaPositionUs < 500_000 } == true }
        } finally { scenario.close(); input.delete(); library.delete(imported.id) }
    }

    @Test fun bothMp4ExportModesKeepSourceDurationAndUseIndependentCadences() {
        val library = DrawingLibrary(context)
        val input = File(context.cacheDir, "cadence-input.mp4")
        val output = File(context.cacheDir, "cadence-output.mp4")
        makeVideo(input)
        val imported = MediaImporter.importNow(context, library, Uri.fromFile(input))
        val loaded = ProjectPersistence.load(library.projectDirectory(imported.id)).getOrThrow()
        val snapshot = DrawingSnapshot(64, 48, loaded.selectedId,
            listOf(SavedRasterSnapshot(loaded.selectedId, "Paint", true, 1f, emptyMap())), animation = loaded.animation)
        try {
            for (mode in MediaExportTiming.entries) {
                ProjectPersistence.exportAnimation(context.contentResolver, Uri.fromFile(output), snapshot, AnimationExportFormat.MP4, 1f, mode).getOrThrow()
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(output.path)
                    val format = extractor.getTrackFormat(0)
                    assertEquals(2_000_000L, format.getLong(MediaFormat.KEY_DURATION))
                    extractor.selectTrack(0)
                    val samples = mutableListOf<Long>()
                    while (extractor.sampleTime >= 0) { samples += extractor.sampleTime; if (!extractor.advance()) break }
                    assertEquals(if (mode == MediaExportTiming.SMOOTH_VIDEO) 120 else 24, samples.size)
                    assertEquals(1_000_000L, samples[if (mode == MediaExportTiming.SMOOTH_VIDEO) 60 else 12])
                } finally { extractor.release() }
            }
            ProjectTransfer.exportMedia(context, library, listOf(imported.id), Uri.fromFile(output), null, AnimationExportFormat.MP4)
            val gallery = MediaExtractor()
            try {
                gallery.setDataSource(output.path)
                val format = gallery.getTrackFormat(0)
                assertEquals(32, format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(24, format.getInteger(MediaFormat.KEY_HEIGHT))
                assertEquals(2_000_000L, format.getLong(MediaFormat.KEY_DURATION))
            } finally { gallery.release() }
        } finally { snapshot.recycle(); input.delete(); output.delete(); library.delete(imported.id) }
    }
    private fun assertColor(actual: Int, expected: Int) {
        for (shift in listOf(0, 8, 16)) assertTrue("Expected $expected, got $actual",
            kotlin.math.abs(((actual shr shift) and 255) - ((expected shr shift) and 255)) < 40)
    }
}
