package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileStoreTest {
    private val ink = StrokeStyle(BrushPreset.Ink, 24f, 1f, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT)

    @Test fun allocatesOnlyIntersectingTilesAndUndoRemovesThem() {
        val store = TileStore(2048, 2048)
        store.commit(stroke(Point(250f, 80f), Point(270f, 80f), ink))
        assertEquals(2, store.allocatedTileCount)
        assertTrue(store.history.undo())
        assertEquals(0, store.allocatedTileCount)
        assertTrue(store.history.redo())
        assertEquals(2, store.allocatedTileCount)
    }

    @Test fun colorUsageFollowsPaintUndoAndRedo() {
        val store = TileStore(512, 512)
        val red = ink.copy(color = RgbaColor(1f, 0f, 0f))
        store.commit(stroke(Point(20f, 20f), Point(220f, 20f), red))
        assertEquals(1, store.snapshotColorUsage().size)

        assertTrue(store.history.undo())
        assertTrue(store.snapshotColorUsage().isEmpty())

        assertTrue(store.history.redo())
        assertEquals(1, store.snapshotColorUsage().size)
    }

    @Test fun eraserClearsToTransparencyRatherThanWhite() {
        val store = TileStore(512, 512)
        store.commit(stroke(Point(30f, 30f), Point(220f, 30f), ink))
        val erase = ink.copy(sizePx = 40f, blend = BlendBehavior.ERASE)
        store.beginLiveStroke()
        store.appendLiveStroke(stroke(Point(30f, 30f), Point(220f, 30f), erase))
        val result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        store.draw(Canvas(result), Paint())
        assertEquals(0, Color.alpha(result.getPixel(100, 30)))
        store.finishLiveStroke()
    }

    @Test fun fingerSmudgeIsGroupedIntoOneUndoStep() {
        val store = TileStore(512, 512)
        store.commit(stroke(Point(80f, 80f), Point(120f, 80f), ink))
        val historyBefore = store.history.estimatedBytes
        store.beginSmudge()
        store.smudge(100f, 80f, 150f, 80f, 28f, .8f)
        store.smudge(150f, 80f, 190f, 90f, 28f, .8f)
        store.finishSmudge()
        assertTrue(store.history.estimatedBytes > historyBefore)
        assertTrue(store.history.undo())
    }

    @Test fun smudgeDoesNotAmplifyASmallMarkIntoMorePigment() {
        val store = TileStore(512, 256)
        store.commit(stroke(Point(55f, 128f), Point(65f, 128f), ink.copy(sizePx = 14f)))
        val alphaBefore = totalAlpha(store, 512, 256)

        store.beginSmudge()
        var x = 60f
        repeat(20) {
            store.smudge(x, 128f, x + 14f, 128f, 22f, .7f)
            x += 14f
        }
        store.finishSmudge()

        val alphaAfter = totalAlpha(store, 512, 256)
        assertTrue("Smudge created alpha: before=$alphaBefore after=$alphaAfter", alphaAfter <= alphaBefore * 1.01)
        assertTrue("Smudge did not move the mark", (80..350).any { Color.alpha(store.colorAt(it, 128)) != 0 })
    }

    @Test fun smudgeBlendsAnOpaqueRainbowWithoutErasingItToWhite() {
        val store = TileStore(256, 256)
        val rainbow = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(256 * 256) { index ->
            Color.HSVToColor(floatArrayOf((index % 256) * 360f / 256f, 1f, 1f))
        }
        rainbow.setPixels(pixels, 0, 256, 0, 0, 256, 256)
        store.replaceTiles(mapOf(TileCoordinate(0, 0) to rainbow))
        val destinationBefore = store.colorAt(155, 128)

        store.beginSmudge()
        store.smudge(90f, 128f, 150f, 128f, 42f, .8f)
        store.finishSmudge()

        for (x in 50..192 step 4) {
            val color = store.colorAt(x, 128)
            assertEquals("Smudge changed opaque coverage at x=$x", 255, Color.alpha(color))
            assertFalse("Smudge produced white at x=$x", Color.red(color) > 245 && Color.green(color) > 245 && Color.blue(color) > 245)
        }
        assertNotEquals(destinationBefore, store.colorAt(155, 128))
    }

    @Test fun airbrushWetPainterMatchesCommittedPixels() {
        val airbrush = StrokeStyle(BrushPreset.Airbrush, 48f, .37f, RgbaColor(.8f, .2f, .1f), BlendBehavior.PAINT)
        val points = listOf(sample(Point(60f, 90f), 0), sample(Point(125f, 115f), 1), sample(Point(190f, 140f), 2))
        val completed = CompletedStroke(points, airbrush)
        val preview = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        StrokeCanvasPainter.draw(Canvas(preview), completed)
        val store = TileStore(256, 256)
        store.commit(completed)
        val committed = store.snapshotTiles().getValue(TileCoordinate(0, 0))
        val previewPixels = IntArray(256 * 256)
        val committedPixels = IntArray(256 * 256)
        preview.getPixels(previewPixels, 0, 256, 0, 0, 256, 256)
        committed.getPixels(committedPixels, 0, 256, 0, 0, 256, 256)
        assertArrayEquals(previewPixels, committedPixels)
        preview.recycle()
        committed.recycle()
    }

    @Test fun finishingLivePencilKeepsTheExactWetPixels() {
        val pencil = StrokeStyle(
            BrushPreset.Pencil,
            42f,
            .68f,
            RgbaColor(.12f, .18f, .24f),
            BlendBehavior.PAINT,
        )
        val first = sample(Point(60f, 80f), 0).copy(pressure = .35f, opacityPressure = .35f)
        val middle = sample(Point(125f, 115f), 8_000_000).copy(
            pressure = .7f,
            opacityPressure = .7f,
            tiltRadians = .8f,
            orientationRadians = .45f,
        )
        val last = sample(Point(190f, 140f), 16_000_000).copy(
            pressure = .9f,
            opacityPressure = .9f,
            tiltRadians = 1.1f,
            orientationRadians = .7f,
        )
        val completed = CompletedStroke(listOf(first, middle, last), pencil)
        val preview = TileStore(256, 256)
        preview.appendPencilPreview(CompletedStroke(listOf(first), pencil), capStart = true)
        preview.appendPencilPreview(CompletedStroke(listOf(first, middle), pencil))
        preview.appendPencilPreview(CompletedStroke(listOf(middle, last), pencil))
        preview.appendPencilPreview(
            CompletedStroke(listOf(middle, last), pencil),
            capEnd = true,
            skipFirstSegment = true,
        )
        val wet = preview.snapshotTiles().getValue(TileCoordinate(0, 0))

        val store = TileStore(256, 256)
        store.commitOverlay(preview, completed)

        val dry = store.snapshotTiles().getValue(TileCoordinate(0, 0))
        val wetPixels = IntArray(256 * 256)
        val dryPixels = IntArray(256 * 256)
        wet.getPixels(wetPixels, 0, 256, 0, 0, 256, 256)
        dry.getPixels(dryPixels, 0, 256, 0, 0, 256, 256)
        assertArrayEquals(wetPixels, dryPixels)
        assertTrue(store.history.undo())
        assertEquals(0, store.allocatedTileCount)
        wet.recycle()
        dry.recycle()
    }

    @Test fun pencilGrainIsContinuousAcrossSparseTileSeams() {
        val pencil = StrokeStyle(
            BrushPreset.Pencil,
            36f,
            .85f,
            RgbaColor(.08f, .09f, .1f),
            BlendBehavior.PAINT,
        )
        val samples = (0..60).map { index ->
            sample(Point(100f + index * 5f, 128f), index * 8_000_000L).copy(
                pressure = .72f,
                opacityPressure = .72f,
                tiltRadians = 1.15f,
                orientationRadians = (Math.PI / 2).toFloat(),
            )
        }
        val completed = CompletedStroke(samples, pencil)
        val direct = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        StrokeCanvasPainter.draw(Canvas(direct), completed)
        val store = TileStore(512, 256)
        store.commit(completed)
        val tiled = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        store.draw(Canvas(tiled), Paint())
        val directPixels = IntArray(512 * 256)
        val tiledPixels = IntArray(512 * 256)
        direct.getPixels(directPixels, 0, 512, 0, 0, 512, 256)
        tiled.getPixels(tiledPixels, 0, 512, 0, 0, 512, 256)
        val differing = directPixels.indices.count { directPixels[it] != tiledPixels[it] }
        val seamDiffering = (0 until 256).sumOf { y ->
            (252..259).count { x -> directPixels[y * 512 + x] != tiledPixels[y * 512 + x] }
        }
        val maximumAlphaDifference = directPixels.indices.maxOf { index ->
            kotlin.math.abs(Color.alpha(directPixels[index]) - Color.alpha(tiledPixels[index]))
        }
        val transparentInteriorAtSeam = (112..144).sumOf { y ->
            (252..259).count { x -> Color.alpha(tiledPixels[y * 512 + x]) == 0 }
        }
        // Separate tile clips can round the outer antialiased contour differently. The central
        // seam itself must remain limited to a few low-alpha edge pixels; a texture-coordinate
        // reset or clipped ribbon would alter hundreds of pixels there.
        assertTrue(
            "different=$differing seam=$seamDiffering alphaDelta=$maximumAlphaDifference " +
                "transparentInterior=$transparentInteriorAtSeam",
            differing <= 200 && seamDiffering <= 16 && maximumAlphaDifference <= 32 &&
                transparentInteriorAtSeam == 0,
        )
        direct.recycle()
        tiled.recycle()
    }

    @Test fun tiltedPencilPreviewDoesNotDependOnInputBatching() {
        val pencil = StrokeStyle(
            BrushPreset.Pencil,
            28f,
            .9f,
            RgbaColor(.06f, .07f, .08f),
            BlendBehavior.PAINT,
        )
        val samples = (0..84).map { index ->
            sample(
                Point(28f + index * 2.4f, 128f + kotlin.math.sin(index * .17f) * 36f),
                index * 4_000_000L,
            ).copy(
                pressure = .68f,
                opacityPressure = .68f,
                tiltRadians = 1.28f,
                orientationRadians = .55f + kotlin.math.sin(index * .11f) * .38f,
            )
        }
        fun render(batchSize: Int): Bitmap {
            val preview = TileStore(256, 256)
            preview.appendPencilPreview(CompletedStroke(listOf(samples.first()), pencil), capStart = true)
            var context = listOf(samples.first())
            samples.drop(1).chunked(batchSize).forEach { batch ->
                preview.appendPencilPreview(
                    CompletedStroke(context + batch, pencil),
                    skipFirstSegment = context.size == 2,
                )
                context = (context + batch).takeLast(2)
            }
            preview.appendPencilPreview(
                CompletedStroke(samples.takeLast(2), pencil),
                capEnd = true,
                skipFirstSegment = true,
            )
            return Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { bitmap ->
                preview.draw(Canvas(bitmap), Paint())
                preview.discard()
            }
        }
        val singleSampleBatches = render(1)
        val sevenSampleBatches = render(7)
        val singlePixels = IntArray(256 * 256)
        val sevenPixels = IntArray(256 * 256)
        singleSampleBatches.getPixels(singlePixels, 0, 256, 0, 0, 256, 256)
        sevenSampleBatches.getPixels(sevenPixels, 0, 256, 0, 0, 256, 256)
        val differing = singlePixels.indices.count { singlePixels[it] != sevenPixels[it] }
        val maximumAlphaDifference = singlePixels.indices.maxOf { index ->
            kotlin.math.abs(Color.alpha(singlePixels[index]) - Color.alpha(sevenPixels[index]))
        }
        val alphaDifference = singlePixels.indices.sumOf { index ->
            kotlin.math.abs(Color.alpha(singlePixels[index]) - Color.alpha(sevenPixels[index])).toLong()
        }
        assertTrue(
            "different=$differing alphaDelta=$maximumAlphaDifference totalAlphaDelta=$alphaDifference",
            differing == 0,
        )
        singleSampleBatches.recycle()
        sevenSampleBatches.recycle()
    }

    @Test fun canceledLiveEraserRestoresPixelsAndDoesNotAddHistory() {
        val store = TileStore(256, 256)
        store.commit(stroke(Point(30f, 80f), Point(220f, 80f), ink))
        val historyBefore = store.history.estimatedBytes
        val before = store.snapshotTiles().getValue(TileCoordinate(0, 0))
        val erase = ink.copy(sizePx = 52f, blend = BlendBehavior.ERASE)
        store.beginLiveStroke()
        store.appendLiveStroke(stroke(Point(90f, 80f), Point(160f, 80f), erase))
        store.cancelLiveStroke()
        val restored = store.snapshotTiles().getValue(TileCoordinate(0, 0))
        val expected = IntArray(256 * 256)
        val actual = IntArray(256 * 256)
        before.getPixels(expected, 0, 256, 0, 0, 256, 256)
        restored.getPixels(actual, 0, 256, 0, 0, 256, 256)
        assertArrayEquals(expected, actual)
        assertEquals(historyBefore, store.history.estimatedBytes)
        before.recycle()
        restored.recycle()
    }

    @Test fun selectionClipRestrictsAnEraserStroke() {
        val store = TileStore(256, 256)
        store.commit(stroke(Point(20f, 80f), Point(230f, 80f), ink.copy(sizePx = 50f)))
        val erase = ink.copy(
            sizePx = 50f,
            blend = BlendBehavior.ERASE,
            selection = dev.tipstroke.core.geometry.SelectionRegion.rectangle(dev.tipstroke.core.geometry.Rect(100f, 0f, 150f, 256f)),
        )
        store.beginLiveStroke()
        store.appendLiveStroke(stroke(Point(20f, 80f), Point(230f, 80f), erase))
        store.finishLiveStroke()

        assertNotEquals(0, Color.alpha(store.colorAt(70, 80)))
        assertEquals(0, Color.alpha(store.colorAt(125, 80)))
        assertNotEquals(0, Color.alpha(store.colorAt(180, 80)))
    }

    @Test fun movesOnlySelectedPixelsAndUndoRestoresTheirOriginalPosition() {
        val store = TileStore(256, 256)
        store.commit(stroke(Point(20f, 80f), Point(100f, 80f), ink.copy(sizePx = 20f)))
        val selection = dev.tipstroke.core.geometry.SelectionRegion.rectangle(
            dev.tipstroke.core.geometry.Rect(40f, 60f, 80f, 100f),
        )

        store.moveSelection(selection, 100f, 0f)
        assertNotEquals(0, Color.alpha(store.colorAt(25, 80)))
        assertEquals(0, Color.alpha(store.colorAt(55, 80)))
        assertNotEquals(0, Color.alpha(store.colorAt(155, 80)))

        assertTrue(store.history.undo())
        assertNotEquals(0, Color.alpha(store.colorAt(55, 80)))
        assertEquals(0, Color.alpha(store.colorAt(155, 80)))
    }

    private fun stroke(a: Point, b: Point, style: StrokeStyle) = CompletedStroke(
        listOf(sample(a, 0), sample(b, 1)), style
    )

    private fun sample(point: Point, t: Long) = StrokeSample(1, point, 1f, 0f, 0f, t, 0, PointerKind.STYLUS)

    private fun totalAlpha(store: TileStore, width: Int, height: Int): Long {
        val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        store.draw(Canvas(rendered), Paint())
        val pixels = IntArray(width * height)
        rendered.getPixels(pixels, 0, width, 0, 0, width, height)
        rendered.recycle()
        return pixels.sumOf { Color.alpha(it).toLong() }
    }
}
