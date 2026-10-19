package dev.tipstroke.drawing.android

import android.graphics.*
import android.net.Uri
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.*
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProcessRecordingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun stack() = LayerStack(context.contentResolver, 512, 512) {}
    private fun flush() = ProjectPersistence.executor.submit {}.get(20, TimeUnit.SECONDS)
    private fun stroke(brush: BrushPreset = BrushPreset.Airbrush, blend: BlendBehavior = BlendBehavior.PAINT) = CompletedStroke(
        listOf(
            StrokeSample(1,Point(230f,120f),.3f,.8f,.4f,0,0,PointerKind.STYLUS),
            StrokeSample(1,Point(270f,145f),.8f,.85f,.5f,16_000_000,0,PointerKind.STYLUS),
            StrokeSample(1,Point(300f,170f),.5f,.9f,.6f,32_000_000,0,PointerKind.STYLUS),
        ), StrokeStyle(brush,30f,.7f,RgbaColor(.8f,.2f,.1f),blend),
    )
    private fun expected(stack: LayerStack): Bitmap {
        val result=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(result); canvas.drawColor(Color.WHITE)
        stack.layers.filterIsInstance<RasterLayerRuntime>().filter { it.visible }.forEach {
            it.tiles.draw(canvas,Paint().apply { alpha=(it.opacity*255).toInt() })
        }
        return result
    }
    private fun assertPixels(expected: Bitmap, actual: Bitmap) {
        assertEquals(expected.width,actual.width); assertEquals(expected.height,actual.height)
        val a=IntArray(expected.width*expected.height); val b=IntArray(a.size)
        expected.getPixels(a,0,expected.width,0,0,expected.width,expected.height)
        actual.getPixels(b,0,actual.width,0,0,actual.width,actual.height)
        assertArrayEquals(a,b)
    }
    @Test fun completeBrushSettingsAndSampleFieldsRoundTrip() {
        val original=ReplayPaint(stroke(BrushPreset.Pencil.copy(pencilGrain=.37f,pencilTiltMode=PencilTiltMode.GRADUAL)),true,true,false,true)
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { ReplayCodec.writePaint(it,original) }
        val restored=ReplayCodec.readPaint(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(original,restored)
        val bad=bytes.toByteArray(); bad[3]=99
        assertThrows(IllegalArgumentException::class.java) { ReplayCodec.readPaint(DataInputStream(ByteArrayInputStream(bad))) }
    }
    @Test fun airbrushAndPixelToolsReplayExactlyAcrossTiles() {
        for (pixel in listOf(null,PixelTool.PENCIL,PixelTool.LINE,PixelTool.DITHER)) {
            val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
            val paint=stroke().let { it.copy(style=it.style.copy(pixelTool=pixel)) }
            stack.selectedRaster()!!.tiles.commit(paint); flush()
            ProcessReplay(recorder.snapshot()).use { replay ->
                val image=replay.frame(recorder.snapshot().actionCount-1)
                val expected=expected(stack); assertPixels(expected,image); expected.recycle(); image.recycle()
            }
            recorder.detach()
        }
    }
    @Test fun incrementalPencilAndEraserReplayExactly() {
        val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
        val store=stack.selectedRaster()!!.tiles
        val pencil=stroke(BrushPreset.Pencil)
        val overlay=TileStore(512,512).apply { captureReplayPreview=true }
        overlay.appendPencilPreview(pencil.copy(samples=pencil.samples.take(1)),capStart=true)
        overlay.appendPencilPreview(pencil.copy(samples=pencil.samples.take(2)))
        overlay.appendPencilPreview(pencil,skipFirstSegment=true)
        overlay.appendPencilPreview(pencil.copy(samples=pencil.samples.takeLast(2)),capEnd=true)
        store.commitOverlay(overlay,pencil); overlay.discard()
        val eraser=stroke(BrushPreset.Airbrush,BlendBehavior.ERASE)
        store.beginLiveStroke()
        store.appendLiveStroke(eraser.copy(samples=eraser.samples.take(2)))
        store.appendLiveStroke(eraser.copy(samples=eraser.samples.takeLast(2)))
        store.finishLiveStroke(eraser); flush()
        ProcessReplay(recorder.snapshot()).use { replay ->
            val actual=replay.frame(recorder.snapshot().actionCount-1); val expected=expected(stack)
            assertPixels(expected,actual); expected.recycle(); actual.recycle()
        }
    }
    @Test fun undoBranchSaveReopenContinueAndSeek() {
        val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
        val store=stack.selectedRaster()!!.tiles
        store.commit(stroke()); store.history.undo(); store.history.redo(); store.history.undo()
        store.commit(stroke().let { it.copy(style=it.style.copy(color=RgbaColor(0f,0f,1f))) })
        flush()
        val library=DrawingLibrary(context); val id=library.newId()
        val snapshot=stack.snapshot().copy(processRecording=recorder.snapshot())
        try {
            ProjectPersistence.save(context.contentResolver,library,id,"Replay",snapshot).getOrThrow()
            val loaded=ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
            assertNull(loaded.recordingError)
            val resumedStack=stack(); resumedStack.replaceWith(loaded)
            val resumed=ProcessRecorder(512,512,loaded.processRecording); resumed.sync(resumedStack,false,true)
            resumedStack.selectedRaster()!!.tiles.commit(stroke()); flush()
            val data=resumed.snapshot()
            assertEquals(recorder.snapshot().actionCount+1,data.actionCount)
            ProcessReplay(data).use { replay ->
                val end=replay.frame(data.actionCount-1); val expected=expected(resumedStack); assertPixels(expected,end)
                replay.frame(0).also { assertEquals(Color.WHITE,it.getPixel(250,130)); it.recycle() }
                replay.frame(data.actionCount-1).also { assertPixels(end,it); it.recycle() }
                end.recycle(); expected.recycle()
            }
        } finally { snapshot.recycle(); library.delete(id) }
    }
    @Test fun layerVisibilityAndDeletionAreRecorded() {
        val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
        stack.selectedRaster()!!.tiles.commit(stroke())
        stack.addRaster(); recorder.sync(stack,false)
        stack.selectedRaster()!!.tiles.commit(stroke())
        stack.toggleVisible(stack.selectedId); recorder.sync(stack,false)
        stack.deleteSelected(); recorder.sync(stack,false); flush()
        ProcessReplay(recorder.snapshot()).use { replay ->
            val actual=replay.frame(recorder.snapshot().actionCount-1); val expected=expected(stack)
            assertPixels(expected,actual); expected.recycle(); actual.recycle()
        }
    }
    @Test fun unsupportedOperationsStopInsteadOfSkippingHistory() {
        val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
        recorder.sync(stack,true); flush()
        val count=recorder.snapshot().actionCount
        stack.selectedRaster()!!.tiles.commit(stroke()); flush()
        assertNotNull(recorder.stoppedReason); assertEquals(count,recorder.snapshot().actionCount)
    }
    @Test fun corruptRecordingDoesNotPreventOpeningOrSavingArtwork() {
        val stack=stack(); val library=DrawingLibrary(context); val id=library.newId(); val snapshot=stack.snapshot()
        try {
            ProjectPersistence.save(context.contentResolver,library,id,"Corrupt recording",snapshot).getOrThrow()
            val file=File(library.projectDirectory(id),"process.tsr"); file.writeText("future format")
            val loaded=ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
            assertNotNull(loaded.recordingError); assertNull(loaded.processRecording)
            ProjectPersistence.save(context.contentResolver,library,id,"Still valid artwork",snapshot).getOrThrow()
            assertEquals("future format",file.readText())
        } finally { snapshot.recycle(); library.delete(id) }
    }
    @Test fun commandRecordingStaysSmallAndCanExportGif() {
        val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
        repeat(100) { stack.selectedRaster()!!.tiles.commit(stroke().let { it.copy(style=it.style.copy(pixelTool=PixelTool.PENCIL)) }) }
        flush(); val data=recorder.snapshot()
        assertTrue("100 strokes should stay below 100 KiB",data.bytes<100*1024)
        assertNull(data.stoppedReason)
        val file=File.createTempFile("process-replay", ".gif")
        try {
            ProcessReplay(data).use { replay ->
                val frames=List(data.actionCount) { AnimationFrame(FrameId("$it")) }
                AnimationExporter.export(context.contentResolver,Uri.fromFile(file),
                    SavedAnimationSnapshot(frames,0,12,PlaybackMode.ONCE,0,0,0f,emptySet(),emptyMap(),emptyMap()),AnimationExportFormat.GIF) { replay.frame(it,128) }
            }
            assertEquals("GIF89a",file.inputStream().use { String(it.readNBytes(6)) })
            assertTrue(file.length()>100)
        } finally { file.delete() }
    }
    @Test fun frozenV1RecordingKeepsItsOriginalPixels() {
        val file=File.createTempFile("recording-v1", ".tsr")
        try {
            javaClass.getResourceAsStream("/recording/v1.tsr")!!.use { source -> file.outputStream().use { source.copyTo(it) } }
            val data=ProcessRecording.read(file)!!
            val expected=javaClass.getResourceAsStream("/recording/v1.png")!!.use(BitmapFactory::decodeStream)
            ProcessReplay(data).use { replay ->
                val actual=replay.frame(data.actionCount-1)
                assertPixels(expected,actual); actual.recycle()
            }
            expected.recycle()
        } finally { file.delete() }
    }
    @Test fun nativeInkDependencyChangesRequireAnExplicitCompatibilityReview() {
        val build=File("build.gradle.kts").readText()
        assertTrue("Do not upgrade native Ink without verifying v1 recordings and providing a migration or compatibility strategy",
            build.contains("val inkVersion = \"1.1.0-alpha08\""))
    }
    @Test fun storageCapPreservesAContiguousPrefix() {
        val stack=stack()
        val saved=ProcessRecording(512,512,List(32) { ByteArray(1024*1024) },null)
        val recorder=ProcessRecorder(512,512,saved); recorder.sync(stack,false,true)
        stack.selectedRaster()!!.tiles.commit(stroke()); stack.selectedRaster()!!.tiles.commit(stroke())
        flush()
        assertEquals(32,recorder.snapshot().actionCount)
        assertEquals(ProcessRecording.MAX_BYTES,recorder.snapshot().bytes)
        assertNotNull(recorder.stoppedReason)
    }

}
