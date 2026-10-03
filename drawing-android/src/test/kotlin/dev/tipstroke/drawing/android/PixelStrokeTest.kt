package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.drawing.PixelTool
import dev.tipstroke.core.drawing.PointerKind
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.drawing.StrokeStyle
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.BlendBehavior
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.RgbaColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PixelStrokeTest {
    private fun sample(x: Float, y: Float) = StrokeSample(
        0, Point(x, y), 1f, 0f, 0f, 0L, 0, PointerKind.STYLUS,
    )

    private fun stroke(tool: PixelTool, vararg points: Point) = CompletedStroke(
        points.map { sample(it.x, it.y) },
        StrokeStyle(BrushPreset.Ink, 1f, 1f, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT, pixelTool = tool),
    )

    @Test fun pixelPencilCommitsFullCoverageGridCells() {
        val store = TileStore(32, 32)
        store.commit(stroke(PixelTool.PENCIL, Point(2.8f, 3.2f), Point(7.1f, 3.8f)))
        for (x in 2..7) assertEquals(255, Color.alpha(store.colorAt(x, 3)))
        for (y in 0..31) for (x in 0..31) {
            assertTrue(Color.alpha(store.colorAt(x, y)) == 0 || Color.alpha(store.colorAt(x, y)) == 255)
        }
    }

    @Test fun lineUsesOnlyFirstAndLastContact() {
        val store = TileStore(32, 32)
        store.commit(stroke(PixelTool.LINE, Point(2f, 2f), Point(15f, 20f), Point(8f, 2f)))
        for (x in 2..8) assertEquals(255, Color.alpha(store.colorAt(x, 2)))
        assertEquals(0, Color.alpha(store.colorAt(15, 20)))
    }

    @Test fun ditherIsStableAcrossPreviewAndCommit() {
        val completed = stroke(PixelTool.DITHER, Point(3f, 4f), Point(12f, 4f))
        val preview = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        StrokeCanvasPainter.draw(Canvas(preview), completed)
        val store = TileStore(32, 32)
        store.commit(completed)
        for (x in 3..12) {
            val alpha = if ((x + 4) % 2 == 0) 255 else 0
            assertEquals(alpha, Color.alpha(preview.getPixel(x, 4)))
            assertEquals(alpha, Color.alpha(store.colorAt(x, 4)))
        }
        preview.recycle()
    }

    @Test fun smallCanvasEnlargesCellsWithoutBlendingNeighbors() {
        val context = RuntimeEnvironment.getApplication()
        val layers = LayerStack(context.contentResolver, 16, 16) {}
        layers.selectedRaster()!!.tiles.commit(stroke(PixelTool.PENCIL, Point(4f, 4f)))
        val view = RasterCanvasView(context, layers)
        view.measure(View.MeasureSpec.makeMeasureSpec(128, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(128, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 128, 128)
        view.updateTransform(64f, 64f, 8f, 0f)
        val output = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(output))
        assertEquals(Color.WHITE, output.getPixel(31, 36))
        assertEquals(Color.BLACK, output.getPixel(32, 36))
        output.recycle()
    }
}
