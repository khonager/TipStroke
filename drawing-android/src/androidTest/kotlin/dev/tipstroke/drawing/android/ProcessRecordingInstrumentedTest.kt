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
}
