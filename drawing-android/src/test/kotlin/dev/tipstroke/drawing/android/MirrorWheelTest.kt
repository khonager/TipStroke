package dev.tipstroke.drawing.android

import android.graphics.Color
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
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
class MirrorWheelTest {
    @Test fun fourCopiesCommitAcrossTilesAsOneUndoAndRecordPatches() {
        val store = TileStore(1024, 1024)
        var patch = false
        store.recordingListener = { commands, _ -> patch = commands == null }
        val stroke = CompletedStroke(listOf(StrokeSample(0, Point(800f, 512f), 1f, 0f, 0f, 0, 0, PointerKind.STYLUS)),
            StrokeStyle(BrushPreset.Ink, 10f, 1f, RgbaColor(1f, 0f, 0f), BlendBehavior.PAINT,
                mirrorWheel = MirrorWheel(4, Point(512f, 512f))))
        store.commit(stroke)
        for ((x, y) in listOf(800 to 512, 512 to 800, 224 to 512, 512 to 224)) assertTrue(Color.alpha(store.colorAt(x, y)) > 0)
        assertEquals(0, Color.alpha(store.colorAt(512, 512)))
        assertTrue(patch)
        assertTrue(store.history.undo())
        assertEquals(0, store.allocatedTileCount)
        assertFalse(store.history.canUndo())
        assertTrue(store.history.redo())
        assertTrue(Color.alpha(store.colorAt(224, 512)) > 0)
    }
    @Test fun mirroredEraserPreviewRevealsBackgroundAtBothTips() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val layers = LayerStack(context.contentResolver, 64, 64) {}
        layers.setBackground(Color.GREEN)
        val sample = StrokeSample(0, Point(16f, 32f), 1f, 0f, 0f, 0, 0, PointerKind.STYLUS)
        val style = StrokeStyle(BrushPreset.Ink, 16f, 1f, RgbaColor(1f, 0f, 0f), BlendBehavior.PAINT,
            mirrorWheel = MirrorWheel(2, Point(32f, 32f)))
        layers.selectedRaster()!!.tiles.commit(CompletedStroke(listOf(sample), style))
        val view = RasterCanvasView(context, layers)
        view.layout(0, 0, 64, 64)
        view.updateTransform(32f, 32f, 1f, 0f)
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        view.draw(canvas)
        for (x in listOf(16, 48)) assertEquals(Color.RED, bitmap.getPixel(x, 32))
        view.previewStroke = CompletedStroke(listOf(sample), style.copy(blend = BlendBehavior.ERASE))
        view.draw(canvas)
        for (x in listOf(16, 48)) assertEquals(Color.GREEN, bitmap.getPixel(x, 32))
        bitmap.recycle()
    }
    @Test fun twoCopiesReflectAroundChosenCenter() {
        assertEquals(Point(30f, 25f), MirrorWheel(2, Point(20f, 90f)).point(Point(10f, 25f), 1))
    }
}
