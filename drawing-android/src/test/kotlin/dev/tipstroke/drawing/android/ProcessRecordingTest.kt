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
        ProcessReplay(recorder.snapshot(), residentTileLimit=2).use { replay ->
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
    @Test fun animationSwitchesAndDuplicatesKeepRecordingAndReplayExactPixels() {
        val stack = stack()
        val recorder = ProcessRecorder(512, 512)
        recorder.sync(stack, false)
        stack.selectedRaster()!!.tiles.commit(stroke(pixelBrush()))
        val animation = AnimationRuntime(stack, false)
        fun sync() = recorder.sync(stack, true, animation = animation)
        fun checkFrame() {
            sync()
            val recording = recorder.snapshot()
            assertNull(recording.stoppedReason)
            ProcessReplay(recording).use { replay ->
                val actual = replay.frame(recording.actionCount - 1)
                val expected = expected(stack)
                try { assertPixels(expected, actual) } finally { actual.recycle(); expected.recycle() }
            }
        }
        checkFrame()
        animation.addBlankFrame(); sync()
        stack.selectedRaster()!!.tiles.commit(stroke(pixelBrush()).let {
            it.copy(samples = it.samples.map { sample -> sample.copy(position = Point(sample.position.x - 100, sample.position.y)) })
        })
        checkFrame()
        animation.select(0); checkFrame()
        animation.duplicateFrame(); checkFrame()
        animation.setBackground(stack.selectedId, true); sync()
        stack.selectedRaster()!!.tiles.commit(stroke(pixelBrush()))
        checkFrame()
        animation.setBackground(stack.selectedId, false); checkFrame()
        animation.select(0); checkFrame()
        val saved = recorder.snapshot()
        recorder.detach()
        val stopped = ProcessRecording(512, 512, saved.actions,
            "Recording stopped: animation and canvas resizing are not supported yet.")
        val resumed = ProcessRecorder(512, 512, stopped)
        resumed.sync(stack, true, restoring = true, animation = animation)
        stack.selectedRaster()!!.tiles.commit(stroke(pixelBrush()))
        val recovered = resumed.snapshot()
        assertNull(recovered.stoppedReason)
        assertTrue(recovered.hasRecoveryGap)
        resumed.detach()
        val reopened = ProcessRecorder(512, 512, recovered)
        reopened.sync(stack, true, restoring = true, animation = animation)
        animation.select(animation.frames.lastIndex)
        reopened.sync(stack, true, animation = animation)
        stack.selectedRaster()!!.tiles.commit(stroke(pixelBrush()))
        val continued = reopened.snapshot()
        ProcessReplay(continued).use { replay ->
            val actual = replay.frame(continued.actionCount - 1)
            val expected = expected(stack)
            try { assertPixels(expected, actual) } finally { actual.recycle(); expected.recycle() }
        }
        reopened.detach()
    }
    private fun pixelBrush() = BrushPreset.Airbrush

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

    @Test fun replayBeyondOldTileLimitKeepsEveryTileAndSeeksBackwards() {
        val tile = Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val png = ByteArrayOutputStream().also { tile.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        tile.recycle()
        fun action(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(java.util.zip.GZIPOutputStream(buffer)).use(write)
        }.toByteArray()
        val metadata = action { out ->
            out.writeByte(0)
            out.writeUTF("""{"background":-1,"opacity":1,"layers":[{"id":"paint","visible":true,"opacity":1}]}""")
        }
        val patch = action { out ->
            out.writeByte(2); out.writeUTF("paint"); out.writeInt(513)
            repeat(513) { i -> out.writeInt(i%32); out.writeInt(i/32); out.writeInt(png.size); out.write(png) }
        }
        val root = java.nio.file.Files.createTempDirectory("replay-test").toFile()
        try {
            ProcessReplay(ProcessRecording(8192,8192,listOf(metadata,patch),null),root,4).use { replay ->
                repeat(2) {
                    replay.frame(1,256).also {
                        assertEquals(Color.RED,it.getPixel(4,4))
                        assertEquals(Color.RED,it.getPixel(4,132))
                        assertEquals(Color.WHITE,it.getPixel(12,132)); it.recycle()
                    }
                    replay.frame(0,256).also { assertEquals(Color.WHITE,it.getPixel(4,4)); it.recycle() }
                }
            }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedActionCannotBeSkippedByRetryingTheSameFrame() {
        val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
        val good=recorder.snapshot()
        ProcessReplay(ProcessRecording(512,512,good.actions + byteArrayOf(1,2,3),null)).use { replay ->
            repeat(2) { assertThrows(IOException::class.java) { replay.frame(1) } }
            replay.frame(0).recycle()
        }
    }

    @Test fun snapshotBarrierAndEncodingDoNotWaitForProjectIo() {
        val blocked=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        ProjectPersistence.executor.execute { blocked.countDown(); release.await(20,TimeUnit.SECONDS) }
        assertTrue(blocked.await(5,TimeUnit.SECONDS))
        try {
            val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
            stack.selectedRaster()!!.tiles.commit(stroke())
            val first=recorder.captureSnapshot()
            stack.selectedRaster()!!.tiles.commit(stroke())
            assertEquals(2,first.get(5,TimeUnit.SECONDS).actionCount)
            assertEquals(3,recorder.captureSnapshot().get(5,TimeUnit.SECONDS).actionCount)
        } finally { release.countDown() }
    }

    @Test fun largeTransparentUndoDoesNotStopRecording() {
        val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
        val patches=(0 until 1024).associate { dev.tipstroke.core.geometry.TileCoordinate(it%32,it/32) to null }
        stack.selectedRaster()!!.tiles.recordingListener!!.invoke(null,patches)
        stack.selectedRaster()!!.tiles.commit(stroke())
        val recording=recorder.snapshot()
        assertNull(recording.stoppedReason)
        assertEquals(3,recording.actionCount)
        ProcessReplay(recording).use { replay ->
            val actual=replay.frame(2); val expected=expected(stack)
            assertPixels(expected,actual); actual.recycle(); expected.recycle()
        }
    }

    @Test fun cancelledSeekCanContinueWithoutSkippingPaint() {
        val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
        repeat(7) { stack.selectedRaster()!!.tiles.commit(stroke()) }
        val recording=recorder.snapshot()
        ProcessReplay(recording).use { replay ->
            var checks=0
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                replay.frame(7) { ++checks > 3 }
            }
            val actual=replay.frame(7); val expected=expected(stack)
            assertPixels(expected,actual); actual.recycle(); expected.recycle()
        }
    }

    private fun pixelImage(): File {
        val file=File.createTempFile("pixel-import", ".png",context.cacheDir)
        Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.RED)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        return file
    }

    @Test fun importedPixelImageCanGrowPastOldLimitsAndRecordingContinues() {
        val file=pixelImage()
        try {
            val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
            stack.addImage(Uri.fromFile(file)).getOrThrow(); recorder.sync(stack,false)
            stack.setImageScale(64f); recorder.sync(stack,false)
            stack.transformSelectedImage(0f,0f,2f); recorder.sync(stack,false)
            assertEquals(128f,stack.selectedImage()!!.transform.scale,0f)
            stack.addRaster(); recorder.sync(stack,false)
            stack.selectedRaster()!!.tiles.commit(stroke())
            val data=recorder.snapshot()
            assertNull(data.stoppedReason)
            assertTrue("Transforms must not store enlarged image frames",data.bytes<16*1024)
            val archive=File.createTempFile("image-replay", ".tsr")
            try {
                data.write(archive)
                ProcessReplay(ProcessRecording.read(archive)!!).use { replay ->
                    val end=replay.frame(data.actionCount-1)
                    assertEquals(Color.RED,end.getPixel(20,20))
                    assertNotEquals(Color.RED,end.getPixel(270,145))
                    replay.frame(0).also { assertEquals(Color.WHITE,it.getPixel(20,20)); it.recycle() }
                    replay.frame(data.actionCount-1).also { assertPixels(end,it); it.recycle() }
                    end.recycle()
                }
            } finally { archive.delete() }
        } finally { file.delete() }
    }

    @Test fun imageTransformMaskUndoVisibilityAndDeletionReplay() {
        val file=pixelImage()
        try {
            val stack=stack(); val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
            val id=stack.addImage(Uri.fromFile(file)).getOrThrow(); recorder.sync(stack,false)
            stack.setImageScale(8f); stack.setOpacity(.5f); recorder.sync(stack,false)
            val image=stack.selectedImage()!!
            val paint=CompletedStroke(listOf(StrokeSample(1,Point(16f,16f),1f,0f,0f,0,0,PointerKind.STYLUS)),
                StrokeStyle(BrushPreset.Ink,4f,1f,RgbaColor(1f,1f,1f),BlendBehavior.PAINT,pixelTool=PixelTool.PENCIL))
            image.mask.commit(paint)
            val masked=recorder.snapshot().actionCount-1
            image.mask.history.undo()
            val undone=recorder.snapshot().actionCount-1
            stack.transformSelectedImage(64f,0f,1f,90f); recorder.sync(stack,false)
            val moved=recorder.snapshot().actionCount-1
            stack.toggleVisible(id); recorder.sync(stack,false)
            val hidden=recorder.snapshot().actionCount-1
            stack.deleteSelected(); recorder.sync(stack,false)
            val data=recorder.snapshot(); assertNull(data.stoppedReason)
            ProcessReplay(data).use { replay ->
                replay.frame(masked).also { assertEquals(Color.WHITE,it.getPixel(256,256)); it.recycle() }
                replay.frame(undone).also { assertNotEquals(Color.WHITE,it.getPixel(256,256)); it.recycle() }
                replay.frame(moved).also { assertNotEquals(Color.WHITE,it.getPixel(420,256)); assertEquals(Color.WHITE,it.getPixel(140,256)); it.recycle() }
                replay.frame(hidden).also { assertEquals(Color.WHITE,it.getPixel(256,256)); it.recycle() }
                replay.frame(data.actionCount-1).also { assertEquals(Color.WHITE,it.getPixel(256,256)); it.recycle() }
            }
        } finally { file.delete() }
    }

    @Test fun legacyImageCutoffResumesWithExplicitCurrentArtworkCheckpoint() {
        val file=pixelImage()
        try {
            val stack=stack(); val original=ProcessRecorder(512,512); original.sync(stack,false)
            val before=original.snapshot(); original.detach()
            stack.addImage(Uri.fromFile(file)).getOrThrow(); stack.setImageScale(64f)
            stack.addRaster(); stack.selectedRaster()!!.tiles.commit(stroke())
            val stopped=ProcessRecording(512,512,before.actions,
                "Recording stopped: image layers, animation and canvas resizing are not supported yet.")
            val resumed=ProcessRecorder(512,512,stopped); resumed.sync(stack,false,true)
            val baseline=resumed.snapshot()
            assertNull(baseline.stoppedReason); assertTrue(baseline.hasRecoveryGap)
            stack.selectedRaster()!!.tiles.commit(stroke())
            assertEquals(baseline.actionCount+1,resumed.snapshot().actionCount)
            ProcessReplay(baseline).use { replay ->
                replay.frame(0).also { assertEquals(Color.WHITE,it.getPixel(20,20)); it.recycle() }
                replay.frame(baseline.actionCount-1).also {
                    assertEquals(Color.RED,it.getPixel(20,20))
                    assertNotEquals(Color.RED,it.getPixel(270,145)); it.recycle()
                }
            }
            val reopened=ProcessRecorder(512,512,baseline); reopened.sync(stack,false,true)
            assertEquals(baseline.actionCount,reopened.snapshot().actionCount)
        } finally { file.delete() }
    }

}
