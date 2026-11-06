package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File

@RunWith(AndroidJUnit4::class)
class ClipMediaInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun asset(id: String, color: Int): MediaSource {
        val file = File(context.cacheDir, "$id.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        return MediaSource(file, "image", 32, 32, 4_000_000)
    }
    private fun sequence(): MediaSource {
        val edit = VideoEdit(listOf(MediaClip("a", "red", "Red", 0, 4_000_000),
            MediaClip("b", "blue", "Blue", 0, 4_000_000, transitionUs = 1_000_000)))
        return MediaSource(File("unused"), "sequence", 32, 32, edit.durationUs, edit = edit,
            assets = mapOf("red" to asset("red", Color.RED), "blue" to asset("blue", Color.BLUE)))
    }
    private fun color(decoder: MediaFrameDecoder, time: Long): Int {
        val bitmap = decoder.frame(time, 32)
        return try { bitmap.getPixel(16, 16) } finally { bitmap.recycle() }
    }

    @Test fun compositorSamplesCrossfadeAndKeyframeTransforms() {
        val source = sequence()
        MediaFrameDecoder(source).use { decoder ->
            assertEquals(Color.RED, color(decoder, 0))
            assertEquals(Color.RED, color(decoder, 3_000_000))
            val mixed = color(decoder, 3_500_000)
            assertTrue(Color.red(mixed) in 126..129); assertTrue(Color.blue(mixed) in 126..129)
            assertEquals(Color.BLUE, color(decoder, 4_000_000))
        }
        val first = source.edit!!.clips[0].copy(keyframes = listOf(
            ClipKeyframe(0, ClipPose()), ClipKeyframe(2_000_000, ClipPose(x = 1f))))
        val edit = VideoEdit(listOf(first))
        MediaFrameDecoder(source.copy(edit = edit, durationUs = edit.durationUs)).use { decoder ->
            assertEquals(Color.RED, color(decoder, 0))
            assertEquals(Color.TRANSPARENT, color(decoder, 2_000_000))
        }
    }

    @Test fun repeatedAtomicSaveReopenAndUndoKeepAssetsAndExactMetadata() {
        val library = DrawingLibrary(context)
        val id = library.newId()
        val stack = LayerStack(context.contentResolver, 32, 32) {}
        val runtime = AnimationRuntime(stack, false)
        runtime.replaceMedia(sequence())
        val source = runtime.media!!
        val split = source.edit!!.split(0, 2_000_000, "split")
        runtime.replaceMedia(source.copy(edit = split, durationUs = split.durationUs))
        runtime.undoVideoEdit(false)
        assertEquals(source.edit, runtime.media!!.edit)
        runtime.undoVideoEdit(true)
        assertEquals(split, runtime.media!!.edit)
        fun save() {
            val snapshot = stack.snapshot(animation = runtime)
            try { ProjectPersistence.save(context.contentResolver, library, id, "Clips", snapshot).getOrThrow() }
            finally { snapshot.recycle() }
        }
        try {
            save()
            val restored = ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
            runtime.restore(restored.animation!!)
            source.assets.values.forEach { it.file.delete() }
            save(); save()
            val again = ProjectPersistence.load(library.projectDirectory(id)).getOrThrow().animation!!.media!!
            assertEquals(split, again.edit)
            assertEquals(source.assets.keys, again.assets.keys)
            MediaFrameDecoder(again).use { assertEquals(Color.BLUE, color(it, again.durationUs - 1)) }
        } finally { library.delete(id) }
    }

    @Test fun shorterTrackKeepsLaterDrawingsAndBoundedEditHistory() {
        val stack = LayerStack(context.contentResolver, 32, 32) {}
        val runtime = AnimationRuntime(stack, false)
        runtime.replaceMedia(sequence())
        runtime.showMediaTime(6_000_000); runtime.addMediaDrawing()
        val source = runtime.media!!
        val edit = VideoEdit(listOf(source.edit!!.clips.first().slice(0, 1_000_000)))
        repeat(60) { runtime.replaceMedia(source.copy(edit = edit, durationUs = edit.durationUs)) }
        assertTrue(runtime.frames.any { it.sourceTimeUs == 6_000_000L })
        assertTrue(runtime.mediaPositionUs < edit.durationUs)
        repeat(50) { runtime.undoVideoEdit(false) }
        assertFalse(runtime.state(false).canUndoVideoEdit)
        assertTrue(runtime.state(false).canRedoVideoEdit)
    }

    @Test fun malformedAssetReferenceAndOutOfSourceVideoRangeAreRejected() {
        val source = sequence()
        assertThrows(IllegalArgumentException::class.java) { source.copy(assets = emptyMap()) }
        val fakeVideo = MediaSource(File("fake"), "video", 32, 32, 500_000)
        assertThrows(IllegalArgumentException::class.java) { source.copy(assets = source.assets + ("red" to fakeVideo)) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(assets = source.assets + ("../escape" to fakeVideo)) }
    }
    @Test fun mixedVideoCutsExportAndTransferWithOriginalAssetsAfterConversion() {
        val library = DrawingLibrary(context)
        val before = library.list().map { it.id }.toSet()
        val video = File(context.cacheDir, "edit-original.mp4")
        val output = File(context.cacheDir, "edit-output.mp4")
        val frames = List(20) { AnimationFrame(FrameId("video-$it")) }
        AnimationExporter.export(context.contentResolver, android.net.Uri.fromFile(video),
            SavedAnimationSnapshot(frames, 0, 10, PlaybackMode.ONCE, 0, 0, 0f, emptySet(), emptyMap(), emptyMap()), AnimationExportFormat.MP4) { index ->
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(if (index < 10) Color.RED else Color.BLUE) }
        }
        val imported = MediaImporter.importNow(context, library, android.net.Uri.fromFile(video))
        val stack = LayerStack(context.contentResolver, 64, 48) {}
        val runtime = AnimationRuntime(stack, false)
        runtime.restore(ProjectPersistence.load(library.projectDirectory(imported.id)).getOrThrow().animation!!)
        val original = runtime.editingSource()!!
        val split = original.edit!!.split(0, 1_000_000, "second").move(1, 0)
        val photoInput = asset("green", Color.GREEN).file
        val photo = importClip(context, android.net.Uri.fromFile(photoInput)).second
        val edit = VideoEdit(split.clips + MediaClip("photo", "green", "Green", 0, 1_000_000, transitionUs = 500_000))
        runtime.replaceMedia(original.copy(edit = edit, durationUs = edit.durationUs, assets = original.assets + ("green" to photo)))
        val snapshot = stack.snapshot(animation = runtime)
        try {
            repeat(2) { ProjectPersistence.save(context.contentResolver, library, imported.id, "Edited", snapshot).getOrThrow() }
            video.delete(); photoInput.delete()
            ProjectPersistence.exportAnimation(context.contentResolver, android.net.Uri.fromFile(output), snapshot,
                AnimationExportFormat.MP4, 1f).getOrThrow()
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.path)
                assertTrue(kotlin.math.abs(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000 - edit.durationUs) < 50_000)
                for ((time, channel) in listOf(0L to 0, 1_200_000L to 16, 2_400_000L to 8)) {
                    val bitmap = retriever.getFrameAtTime(time, android.media.MediaMetadataRetriever.OPTION_CLOSEST)!!
                    try { assertTrue("Expected source channel at $time", ((bitmap.getPixel(32, 24) shr channel) and 255) > 200) }
                    finally { bitmap.recycle() }
                }
            } finally { retriever.release() }
            val bytes = java.io.ByteArrayOutputStream().also { ProjectTransfer.exportProjects(library, listOf(imported.id), it) }.toByteArray()
            assertEquals(1, ProjectTransfer.importProjects(library, bytes.inputStream()))
            val transferred = library.list().first { it.id != imported.id && it.id !in before }
            val reopened = ProjectPersistence.load(library.projectDirectory(transferred.id)).getOrThrow().animation!!.media!!
            assertEquals(edit, reopened.edit)
            assertTrue(reopened.assets.values.all { it.file.isFile })
            MediaFrameDecoder(reopened).use { assertEquals(Color.GREEN, color(it, edit.durationUs - 1)) }
        } finally {
            snapshot.recycle(); video.delete(); output.delete(); photoInput.delete()
            library.list().filter { it.id !in before }.forEach { library.delete(it.id) }
        }
    }

    @Test fun editedTrackPlaysSeeksAndSurvivesBackgroundingOnNativeCanvas() {
        val library = DrawingLibrary(context)
        val id = library.newId()
        val stack = LayerStack(context.contentResolver, 32, 32) {}
        val runtime = AnimationRuntime(stack, false)
        runtime.replaceMedia(sequence())
        val snapshot = stack.snapshot(animation = runtime)
        try { ProjectPersistence.save(context.contentResolver, library, id, "Preview", snapshot).getOrThrow() }
        finally { snapshot.recycle() }
        val state = java.util.concurrent.atomic.AtomicReference<AnimationUiState?>()
        val failure = java.util.concurrent.atomic.AtomicReference<String?>()
        lateinit var surface: DrawingSurface
        val scenario = androidx.test.core.app.ActivityScenario.launch<MediaPlaybackTestActivity>(android.content.Intent(context, MediaPlaybackTestActivity::class.java))
        fun waitFor(message: String, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            while (!condition() && failure.get() == null && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(25)
            assertNull(failure.get()); assertTrue(message, condition())
        }
        try {
            scenario.onActivity { activity ->
                surface = DrawingSurface(activity)
                surface.animationListener = { state.set(it) }; surface.animationErrorListener = { failure.set(it) }
                activity.setContentView(surface)
                surface.loadProject(library, id) { it.exceptionOrNull()?.let { error -> failure.set(error.toString()) } }
            }
            waitFor("Ready") { state.get()?.let { it.videoEdit != null && !it.loading } == true }
            scenario.onActivity { surface.toggleAnimationPlayback() }
            waitFor("Edited source clock advances") { (state.get()?.mediaPositionUs ?: 0) > 500_000 }
            scenario.onActivity { surface.seekMedia(100_000); surface.seekMedia(4_500_000); surface.seekMedia(3_500_000) }
            waitFor("Latest seek wins") { state.get()?.let { !it.loading && !it.playing && it.mediaPositionUs == 3_500_000L } == true }
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(150)
            val position = floatArrayOf(16f, 16f)
            scenario.onActivity {
                val raster = (0 until surface.childCount).map(surface::getChildAt).filterIsInstance<RasterCanvasView>().single()
                raster.transformMatrix.mapPoints(position)
                val origin = IntArray(2); raster.getLocationOnScreen(origin)
                position[0] += origin[0]; position[1] += origin[1]
            }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try {
                val pixel = screenshot.getPixel(position[0].toInt(), position[1].toInt())
                assertTrue(Color.red(pixel) in 120..140); assertTrue(Color.blue(pixel) in 120..140)
            } finally { screenshot.recycle() }
            scenario.onActivity { surface.toggleAnimationPlayback() }
            waitFor("Playing") { state.get()?.playing == true }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            waitFor("Background pauses composition") { state.get()?.playing == false }
            val paused = state.get()!!.mediaPositionUs
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            waitFor("Restore matching frame") { state.get()?.let { !it.loading && it.mediaPositionUs == paused } == true }
        } finally { scenario.close(); library.delete(id) }
    }

}
