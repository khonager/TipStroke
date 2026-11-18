package dev.tipstroke.drawing.android
import android.graphics.Color
import dev.tipstroke.core.effects.*
import dev.tipstroke.core.geometry.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RasterAdjustmentTest {
    @Test fun haloCrossesTileBoundaryAndApplyUndoesWithoutChangingPreviewSource() {
        val store=TileStore(512,256); store.putFillPixel(255,100,Color.RED)
        val (output,dirty)=RasterAdjustment.render(store,store.residentCoordinates(),RasterEffectSettings(radius=3),null){false}
        assertEquals(Color.RED,store.colorAt(255,100)); assertEquals(0,store.colorAt(256,100))
        assertTrue(Color.alpha(output.colorAt(256,100))>0)
        store.commitReplacement(output,dirty)
        assertTrue(Color.alpha(store.colorAt(256,100))>0)
        assertTrue(store.history.undo()); assertEquals(Color.RED,store.colorAt(255,100)); assertEquals(0,store.colorAt(256,100))
        assertFalse(store.history.canUndo()); output.discard()
    }
    @Test fun selectionLeavesOutsidePixelsUntouchedAndCancellationProducesNoEdit() {
        val store=TileStore(512,256); store.putFillPixel(255,100,Color.RED)
        val clip=SelectionRegion(listOf(Point(0f,0f),Point(256f,0f),Point(256f,256f),Point(0f,256f)))
        val (output,dirty)=RasterAdjustment.render(store,store.residentCoordinates(),RasterEffectSettings(radius=3),clip){false}
        store.commitReplacement(output,dirty); assertEquals(0,store.colorAt(256,100));output.discard()
        val before=store.colorAt(255,100)
        assertTrue(runCatching { RasterAdjustment.render(store,store.residentCoordinates(),RasterEffectSettings(),null){true} }.isFailure)
        assertEquals(before,store.colorAt(255,100))
    }
}
