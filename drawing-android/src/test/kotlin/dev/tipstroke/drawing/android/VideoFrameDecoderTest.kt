package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoFrameDecoderTest {
    private val source = MediaSource(File("test.source"), "video", 80, 40, 2_000_000)
    private fun bitmap(width: Int = 80, height: Int = 40) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    private class Retriever : MediaMetadataRetriever() {
        val requests = mutableListOf<Pair<String, Long>>()
        var scaled: () -> Bitmap? = { null }
        var full: () -> Bitmap? = { null }
        var releases = 0
        var openFailure: RuntimeException? = null
        override fun setDataSource(path: String) { openFailure?.let { throw it } }
        override fun getScaledFrameAtTime(timeUs: Long, option: Int, dstWidth: Int, dstHeight: Int): Bitmap? {
            assertEquals(OPTION_CLOSEST, option)
            requests += "scaled" to timeUs
            return scaled()
        }
        override fun getFrameAtTime(timeUs: Long, option: Int): Bitmap? {
            assertEquals(OPTION_CLOSEST, option)
            requests += "full" to timeUs
            return full()
        }
        override fun extractMetadata(keyCode: Int): String? = "test-metadata"
        override fun release() { releases++ }
    }

    @Test fun scaledSuccessDoesNotAllocateFullFrameAndClampsEndpoints() {
        val video = Retriever().apply { scaled = { bitmap(20, 10) }; full = { error("Unexpected fallback") } }
        VideoFrameDecoder(source, { video }).use { decoder ->
            for (time in listOf(-1L, 830_000L, source.durationUs)) {
                val frame = decoder.frame(time, 20)
                assertFalse(frame.isRecycled)
                frame.recycle()
            }
        }
        assertEquals(listOf("scaled" to 0L, "scaled" to 830_000L, "scaled" to 1_999_999L), video.requests)
        assertEquals(1, video.releases)
    }

    @Test fun nullScaledFrameFallsBackAtSameTimeAndRemembersWorkingMode() {
        val originals = mutableListOf<Bitmap>()
        val events = mutableListOf<String>()
        val video = Retriever().apply { full = { bitmap(40, 80).also { originals += it } } }
        VideoFrameDecoder(source, { video }, { event, _ -> events += event }).use { decoder ->
            for (time in listOf(830_000L, 900_000L)) {
                val frame = decoder.frame(time, 20)
                // Use the decoded orientation, without rotating twice or stretching.
                assertEquals(10, frame.width); assertEquals(20, frame.height)
                assertEquals(Color.RED, frame.getPixel(5, 10))
                assertFalse(frame.isRecycled)
                frame.recycle()
            }
        }
        assertTrue(originals.all { it.isRecycled })
        assertEquals(listOf("scaled" to 830_000L, "full" to 830_000L, "full" to 900_000L), video.requests)
        assertEquals(1, events.size)
        assertTrue(events.single().contains("recovered"))
    }

    @Test fun scaledExceptionFallsBackWithoutRecyclingReturnedSmallBitmap() {
        val original = bitmap()
        val video = Retriever().apply {
            scaled = { throw IllegalStateException("codec scale failed") }
            full = { original }
        }
        VideoFrameDecoder(source, { video }).use { decoder ->
            assertSame(original, decoder.frame(830_000, 1280))
            assertFalse(original.isRecycled)
        }
        original.recycle()
    }

    @Test fun failedDecoderIsReleasedBeforeRetryingSameTimestampOnFreshInstance() {
        val broken = Retriever()
        val working = Retriever().apply { scaled = { bitmap(20, 10) } }
        var created = 0
        val decoder = VideoFrameDecoder(source, {
            if (created++ == 0) broken else {
                assertEquals(1, broken.releases)
                working
            }
        })
        decoder.frame(830_000, 20).recycle()
        assertEquals(listOf("scaled" to 830_000L), working.requests)
        assertEquals(2, created)
        decoder.close(); decoder.close()
        assertEquals(1, working.releases)
        assertThrows(IllegalStateException::class.java) { decoder.frame(0, 20) }
    }

    @Test fun permanentFailureIsBoundedLoggedAndDoesNotReturnUnrelatedFrame() {
        val decoders = mutableListOf<Retriever>()
        val events = mutableListOf<Pair<String, Throwable?>>()
        VideoFrameDecoder(source, {
            Retriever().apply { full = { throw IllegalStateException("codec failed") } }.also { decoders += it }
        }, { event, error -> events += event to error }).use { decoder ->
            val failure = assertThrows(IllegalStateException::class.java) { decoder.frame(830_000, 20) }
            assertTrue(failure.message!!.contains("830 ms"))
            assertEquals(2, failure.suppressed.size)
            assertSame(failure, events.single().second)
        }
        assertEquals(2, decoders.size)
        assertTrue(decoders.all { it.releases == 1 })
        assertTrue(events.single().first.contains("containerMime=test-metadata"))
    }

    @Test fun failedSourceInitializationReleasesBothAttempts() {
        val decoders = mutableListOf<Retriever>()
        VideoFrameDecoder(source, {
            Retriever().apply { openFailure = IllegalArgumentException("bad source") }.also { decoders += it }
        }).use { decoder ->
            assertThrows(IllegalStateException::class.java) { decoder.frame(0, 20) }
        }
        assertEquals(2, decoders.size)
        assertTrue(decoders.all { it.releases == 1 && it.requests.isEmpty() })
    }

    @Test fun memoryExhaustionDoesNotTriggerMoreDecodeAllocations() {
        val video = Retriever().apply { scaled = { throw OutOfMemoryError("test") } }
        var created = 0
        VideoFrameDecoder(source, { created++; video }).use { decoder ->
            assertThrows(OutOfMemoryError::class.java) { decoder.frame(0, 20) }
        }
        assertEquals(1, created)
        assertEquals(listOf("scaled" to 0L), video.requests)
        assertEquals(1, video.releases)
    }
}
