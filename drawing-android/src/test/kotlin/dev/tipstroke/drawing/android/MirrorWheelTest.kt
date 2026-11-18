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
    @Test fun twoCopiesReflectAroundChosenCenter() {
        assertEquals(Point(30f, 25f), MirrorWheel(2, Point(20f, 90f)).point(Point(10f, 25f), 1))
    }
}
