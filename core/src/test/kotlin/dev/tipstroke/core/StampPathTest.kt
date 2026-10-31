package dev.tipstroke.core

import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.*
import kotlin.test.*

class StampPathTest {
    private val style = StrokeStyle(BrushPreset.Blank.copy(spacing = .17f, stamp = StampBrush(scatter = .4f,
        rotationJitter = 120f, sizeJitter = .3f, startTaperPx = 70f, fadeOutPx = 1000f)), 21f, .8f, RgbaColor(.1f, .3f, .8f), BlendBehavior.PAINT)
    private fun sample(x: Float, time: Long) = StrokeSample(0, Point(x, 30f), .7f, .4f, 0f, time, 0, PointerKind.STYLUS)

    @Test fun eventBatchesAndRepeatedEndpointsDoNotRestartSpacingOrRandomness() {
        val samples = (0..20).map { sample(it * 7f, it * 10_000_000L) }
        val whole = StampPath(style).append(samples)
        val session = StampPath(style)
        val streamed = session.append(samples.take(1)) + samples.zipWithNext().flatMap { (a, b) -> session.append(listOf(a, b)) }
        assertEquals(whole, streamed)
        assertTrue(whole.size > 20)
    }

    @Test fun spacingAndCountControlTheNumberOfMarks() {
        val samples = listOf(sample(0f, 0), sample(100f, 100_000_000))
        val spaced = style.copy(brush = style.brush.copy(spacing = 1f, stamp = StampBrush(count = 3)), sizePx = 20f)
        assertEquals(18, StampPath(spaced).append(samples).size)
        assertEquals(1, StampPath(style).append(listOf(samples.first(), samples.first())).size)
    }

    @Test fun sourceBytesAreImmutableAndValidateDimensions() {
        val bytes = byteArrayOf(0, 127, -1, 0)
        val texture = BrushTexture(2, 2, bytes)
        bytes[0] = -1
        assertEquals(0, texture.coverage(0, 0))
        assertEquals(255, texture.inverted().coverage(0, 0))
        assertFailsWith<IllegalArgumentException> { BrushTexture(257, 1, ByteArray(257)) }
        assertFailsWith<IllegalArgumentException> { StampBrush(shape = StampShape.IMAGE) }
    }
}
