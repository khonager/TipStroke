package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProcessRecordingInstrumentedTest {
    @Test fun gifOpensAsAnAnimatedDrawable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir,"progress.gif")
        try {
            val frames = List(3) { AnimationFrame(FrameId("$it")) }
            val random = java.util.Random(73)
            AnimationExporter.export(context.contentResolver,Uri.fromFile(file),
                SavedAnimationSnapshot(frames,0,12,PlaybackMode.ONCE,0,0,0f,emptySet(),emptyMap(),emptyMap()),AnimationExportFormat.GIF) {
                Bitmap.createBitmap(IntArray(257*259) { Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256)) },257,259,Bitmap.Config.ARGB_8888)
            }
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
            assertTrue(drawable is android.graphics.drawable.AnimatedImageDrawable)
            assertEquals(257,drawable.intrinsicWidth)
            assertEquals(259,drawable.intrinsicHeight)
        } finally { file.delete() }
    }

    @Test fun shortOddSizedVideoIsPlayableAndCanReplaceExistingExport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir,"short-progress.mp4")
        try {
            for (count in listOf(8,1)) {
                val frames = List(count) { AnimationFrame(FrameId("$it")) }
                AnimationExporter.export(context.contentResolver,Uri.fromFile(file),
                    SavedAnimationSnapshot(frames,0,12,PlaybackMode.ONCE,0,0,0f,emptySet(),emptyMap(),emptyMap()),AnimationExportFormat.MP4) {
                    Bitmap.createBitmap(257,259,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                }
                MediaMetadataRetriever().use { video ->
                    video.setDataSource(file.absolutePath)
                    assertEquals("258",video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                    assertTrue(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>0)
                    val frame = requireNotNull(video.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST))
                    assertTrue(Color.red(frame.getPixel(100,100))>200)
                    frame.recycle()
                }
            }
        } finally { file.delete() }
    }

    @Test fun nativeInkRoundTripsPixelsAndExportsPlayableMp4() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val stack=LayerStack(context.contentResolver,512,512) {}
        val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
        val style=StrokeStyle(BrushPreset.Ink,24f,.8f,RgbaColor(.1f,.2f,.7f),BlendBehavior.PAINT)
        val samples=listOf(
            StrokeSample(1,Point(200f,100f),.2f,.3f,.2f,0,0,PointerKind.STYLUS),
            StrokeSample(1,Point(260f,130f),.9f,.5f,.4f,20_000_000,0,PointerKind.STYLUS),
            StrokeSample(1,Point(330f,150f),.4f,.6f,.6f,40_000_000,0,PointerKind.STYLUS),
        )
        val brushes=TipStrokeInkBrushes()
        val brush=Brush.createWithColorIntArgb(brushes.familyFor(style.brush.copy(pressureToOpacity=PressureCurve(1f,1f,1f))),0xcc1a33b3.toInt(),24f,.1f)
        val inputs=MutableStrokeInputBatch().apply {
            setNoiseSeed(1793)
            samples.forEach { add(InputToolType.STYLUS,it.position.x,it.position.y,it.elapsedNanos/1_000_000,StrokeInput.NO_STROKE_UNIT_LENGTH,it.pressure,it.tiltRadians,it.orientationRadians) }
        }
        val renderer=CanvasStrokeRenderer.create(brushes.textureStore)
        repeat(7) {
            stack.selectedRaster()!!.tiles.commitInk(CompletedStroke(samples,style),Stroke(brush,inputs),renderer)
        }
        ProjectPersistence.executor.submit {}.get(20,TimeUnit.SECONDS)
        val recording=recorder.snapshot()
        assertEquals(8,recording.actionCount)
        assertTrue(recording.bytes<16*1024)
        val expected=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
        Canvas(expected).apply { drawColor(Color.WHITE); stack.selectedRaster()!!.tiles.draw(this,Paint()) }
        // A blocked save must not freeze preview at an early frame or prevent a final seek.
        val release=java.util.concurrent.CountDownLatch(1)
        ProjectPersistence.executor.execute { release.await(15,TimeUnit.SECONDS) }
        try {
            ProcessPreview(recording,context.cacheDir).use { preview ->
                for (step in listOf(2,7,0,7)) {
                    val complete=java.util.concurrent.CountDownLatch(1)
                    var result: Result<Bitmap>? = null
                    preview.frame(step) { result=it; complete.countDown() }
                    assertTrue("Preview waited for project I/O",complete.await(10,TimeUnit.SECONDS))
                    val actual=requireNotNull(result).getOrThrow()
                    if(step==7) assertTrue("Final preview does not match artwork",expected.sameAs(actual))
                    actual.recycle()
                }
            }
        } finally { release.countDown() }
        val file=File(context.cacheDir,"process-recording.mp4")
        try {
            ProcessReplay(recording).use { replay ->
                val actual=replay.frame(recording.actionCount-1)
                assertTrue("Native Ink replay changed pixels",expected.sameAs(actual)); actual.recycle()
                val frames=List(recording.actionCount) { AnimationFrame(FrameId("$it"),6) }
                AnimationExporter.export(context.contentResolver,Uri.fromFile(file),
                    SavedAnimationSnapshot(frames,0,12,PlaybackMode.ONCE,0,0,0f,emptySet(),emptyMap(),emptyMap()),AnimationExportFormat.MP4) { replay.frame(it) }
            }
            MediaMetadataRetriever().use { video ->
                video.setDataSource(file.absolutePath)
                assertEquals("512",video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                assertTrue(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>0)
                val frame=video.getFrameAtTime(3_900_000,MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull(frame); frame?.recycle()
            }
        } finally { file.delete(); expected.recycle() }
    }
    @Test fun importedTinyImageEnlargesAndExportsWithLaterPaint() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val source=File(context.cacheDir,"tiny-recording-import.png")
        val videoFile=File(context.cacheDir,"tiny-recording-import.mp4")
        Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.RED)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        try {
            val stack=LayerStack(context.contentResolver,512,512) {}
            val recorder=ProcessRecorder(512,512); recorder.sync(stack,false)
            stack.addImage(Uri.fromFile(source)).getOrThrow(); recorder.sync(stack,false)
            stack.setImageScale(64f); recorder.sync(stack,false)
            stack.transformSelectedImage(0f,0f,2f); recorder.sync(stack,false)
            stack.addRaster(); recorder.sync(stack,false)
            val stroke=CompletedStroke(listOf(StrokeSample(1,Point(256f,256f),1f,0f,0f,0,0,PointerKind.STYLUS)),
                StrokeStyle(BrushPreset.Ink,60f,1f,RgbaColor(0f,0f,1f),BlendBehavior.PAINT,pixelTool=PixelTool.PENCIL))
            stack.selectedRaster()!!.tiles.commit(stroke)
            val data=recorder.snapshot(); assertNull(data.stoppedReason); assertTrue(data.bytes<16*1024)
            ProcessReplay(data,context.cacheDir).use { replay ->
                replay.frame(data.actionCount-1).also {
                    assertEquals(Color.RED,it.getPixel(20,20)); assertEquals(Color.BLUE,it.getPixel(256,256)); it.recycle()
                }
                val frames=List(data.actionCount) { AnimationFrame(FrameId("$it"),6) }
                AnimationExporter.export(context.contentResolver,Uri.fromFile(videoFile),
                    SavedAnimationSnapshot(frames,0,12,PlaybackMode.ONCE,0,0,0f,emptySet(),emptyMap(),emptyMap()),AnimationExportFormat.MP4) { replay.frame(it) }
            }
            MediaMetadataRetriever().use { video ->
                video.setDataSource(videoFile.absolutePath)
                val time=(data.actionCount*500L-100)*1000
                val frame=requireNotNull(video.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST))
                assertTrue(Color.red(frame.getPixel(20,20))>200)
                assertTrue(Color.blue(frame.getPixel(256,256))>200)
                assertTrue(Color.red(frame.getPixel(256,256))<50)
                frame.recycle()
            }
        } finally { source.delete(); videoFile.delete() }
    }

}
