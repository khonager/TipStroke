package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoFrameDecoderInstrumentedTest {
    @Test fun realUnscaledFallbackKeepsPortraitSizeAndFrameContentAcrossSeeks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("decode-fallback-", ".mp4", context.cacheDir)
        try {
            val frames = List(20) { AnimationFrame(FrameId("frame-$it")) }
            AnimationExporter.export(context.contentResolver, Uri.fromFile(file),
                SavedAnimationSnapshot(frames, 0, 10, PlaybackMode.ONCE, 0, 0, 0f,
                    emptySet(), emptyMap(), emptyMap()), AnimationExportFormat.MP4) { index ->
                Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (index < 10) Color.RED else Color.BLUE)
                }
            }
            val source = MediaSource(file, "video", 64, 96, 2_000_000, 10.0)
            var scaledCalls = 0
            VideoFrameDecoder(source, {
                object : MediaMetadataRetriever() {
                    override fun getScaledFrameAtTime(timeUs: Long, option: Int, dstWidth: Int, dstHeight: Int): Bitmap? {
                        scaledCalls++
                        return null // Reproduce the vendor failure; regular decoding stays real.
                    }
                }
            }).use { fallback ->
                MediaFrameDecoder(source).use { normal ->
                    for (time in listOf(830_000L, 1_200_000L, 0L, 1_999_999L, 830_000L)) {
                        val actual = fallback.frame(time, 24)
                        val expected = normal.frame(time, 24)
                        try {
                            assertEquals(16, actual.width); assertEquals(24, actual.height)
                            val color = actual.getPixel(8, 12)
                            val reference = expected.getPixel(expected.width / 2, expected.height / 2)
                            assertTrue(kotlin.math.abs(Color.red(color) - Color.red(reference)) <= 3)
                            assertTrue(kotlin.math.abs(Color.blue(color) - Color.blue(reference)) <= 3)
                            assertTrue(if (time < 1_000_000) Color.red(color) > 200 else Color.blue(color) > 200)
                        } finally { actual.recycle(); expected.recycle() }
                    }
                }
            }
            assertEquals(1, scaledCalls)
        } finally { file.delete() }
    }
}
