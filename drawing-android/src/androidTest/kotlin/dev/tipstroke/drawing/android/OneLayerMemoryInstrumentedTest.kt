package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OneLayerMemoryInstrumentedTest {
    @Test fun fullyPainted5632SquareKeepsRoomForOrdinaryEdits() {
        val store = TileStore(5632, 5632)
        try {
            store.replaceTiles((0 until 22).flatMap { x -> (0 until 22).map { y ->
                TileCoordinate(x, y) to Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            } }.toMap())
            val style = StrokeStyle(BrushPreset.Ink, 80f, 1f, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT)
            repeat(12) { row ->
                fun sample(x: Float, time: Long) = StrokeSample(0, Point(x, 300f + row * 400), 1f, 0f, 0f,
                    time, 0, PointerKind.STYLUS)
                store.commit(CompletedStroke(listOf(sample(500f, 0), sample(5100f, 320_000_000)), style))
            }
            assertEquals(484, store.allocatedTileCount)
            assertTrue(store.history.canUndo())
            assertTrue(store.history.estimatedBytes <= TileStore.defaultHistoryBudget(5632, 5632))
            val documentBytes = 121L * 1024 * 1024 + store.history.estimatedBytes
            val context = ApplicationProvider.getApplicationContext<Context>()
            val memory = MemoryBudgetAdvisor(context).snapshot(5632, 5632, documentBytes, force = true)
            assertEquals("A full layer plus bounded ordinary edits must not trigger a false warning: $memory",
                MemoryPressure.NORMAL, memory.pressure)
            assertTrue(store.history.undo())
            assertEquals(Color.WHITE, store.colorAt(2800, 4700))
            assertTrue(store.history.redo())
            assertEquals(Color.BLACK, store.colorAt(2800, 4700))
        } finally { store.discard() }
    }
}
