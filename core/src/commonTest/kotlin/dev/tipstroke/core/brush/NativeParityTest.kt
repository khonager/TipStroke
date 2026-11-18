package dev.tipstroke.core.brush

import dev.tipstroke.core.drawing.BrushTip
import dev.tipstroke.core.editor.NativeBrush
import dev.tipstroke.core.editor.NativeTimeline
import dev.tipstroke.core.model.*
import kotlin.test.*

class NativeParityTest {
    @Test fun importedTipPreservesCoverageAndTransparentBorder() {
        val source = BrushTexture(2, 2, byteArrayOf(0, 64, -128, -1))
        val tip = BrushTip.shape(StampBrush(shape = StampShape.IMAGE, shapeTexture = source), 1f)
        assertEquals(4, tip.width); assertEquals(4, tip.height)
        assertEquals(64, tip.coverage(6)); assertEquals(128, tip.coverage(9)); assertEquals(255, tip.coverage(10))
        for (index in listOf(0, 1, 2, 3, 4, 7, 8, 11, 12, 13, 14, 15)) assertEquals(0, tip.coverage(index))
    }
    @Test fun grainDepthInterpolatesImportedCoverage() {
        val tip = BrushTip.grain(StampBrush(grain = StampGrain.IMAGE,
            grainTexture = BrushTexture(2, 1, byteArrayOf(0, -1)), grainDepth = .5f))
        assertEquals(128, tip.coverage(0)); assertEquals(255, tip.coverage(1))
    }
    @Test fun nativeStampPathHasTheSameResultsAcrossEventBatches() {
        val brush = NativeBrush(BrushPreset.Blank.copy(stamp = StampBrush(scatter = .3f, count = 3, rotationJitter = 90f)))
        val first = brush.path(20f, .8f)
        val second = brush.path(20f, .8f)
        assertEquals(first.append(0f, 0f, .5f, 0f, 0.0), second.append(0f, 0f, .5f, 0f, 0.0))
        val single = first.append(100f, 0f, .5f, 0f, .1)
        val split = second.append(50f, 0f, .5f, 0f, .05) + second.append(100f, 0f, .5f, 0f, .1)
        assertEquals(single.size, split.size)
        single.zip(split).forEach { (a, b) ->
            assertEquals(a.x, b.x, .0001f); assertEquals(a.y, b.y, .0001f)
            assertEquals(a.copy(x = b.x, y = b.y), b)
        }
    }
    @Test fun collectionsReadAndroidSingleLibraryAndPackFormats() {
        val brushes = listOf(LibraryBrush(BrushPreset.Pencil), LibraryBrush(BrushPreset.Blank))
        val library = BrushCollection.encode(brushes)
        assertEquals(brushes, BrushCollection.decode(library))
        assertEquals(brushes.take(1), BrushCollection.decode(BrushPackage.encode(brushes.first())))
        assertEquals(brushes, BrushCollection.decode("""{"format":"tipstroke-brush-pack","version":1,"brushes":$library}"""))
        assertFails { BrushCollection.encode(listOf(brushes.first(), brushes.first())) }
        assertFails { BrushCollection.decode("""{"format":"tipstroke-brush-pack","version":99,"brushes":$library}""") }
    }
    @Test fun applePlaybackUsesSharedExposureBasedPingPong() {
        val timeline = NativeTimeline("2,1,3", 12, "PING_PONG")
        val frames = (0L until 10L).map { timeline.frameAt(it) }
        assertEquals(listOf(0, 0, 1, 2, 2, 2, 2, 2, 1, 0), frames)
        assertEquals(frames, timeline.segments().flatMap { segment -> List(segment.ticks) { segment.index } })
        assertEquals(10, timeline.cycleTicks)
        assertEquals(0, NativeTimeline("1", 12, "PING_PONG").frameAt(100))
    }
}
