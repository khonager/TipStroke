package dev.tipstroke.drawing.android

import android.graphics.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.SelectionRegion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShadowPullTest {
    @Test fun shadowUsesSelectedAlphaBehindOriginalAndUndoesAsOneEdit() {
        val store = TileStore(512,512)
        store.putFillPixel(255,100,Color.RED)
        store.putFillPixel(256,100,Color.GREEN)
        store.putFillPixel(255,101,Color.argb(128,255,0,0))
        val selection = SelectionRegion(listOf(Point(250f,90f),Point(256f,90f),Point(256f,110f),Point(250f,110f)))
        val selected = store.duplicate(selection)
        val paint = Paint().apply { colorFilter = PorterDuffColorFilter(Color.BLACK,PorterDuff.Mode.SRC_IN); alpha = 128 }
        store.commitShadow(selected,1f,0f,paint)
        assertEquals(Color.RED,store.colorAt(255,100))
        assertEquals(Color.GREEN,store.colorAt(256,100))
        assertEquals(0,Color.alpha(store.colorAt(257,100)))
        assertEquals(64,Color.alpha(store.colorAt(256,101)))
        assertTrue(store.history.undo())
        assertEquals(0,Color.alpha(store.colorAt(256,101)))
        assertFalse(store.history.canUndo())
        assertTrue(store.history.redo())
        assertEquals(64,Color.alpha(store.colorAt(256,101)))
        selected.discard()
    }
    @Test fun nativeDragPreviewsWithoutEditingAndCancelDiscardsShadow() {
        val surface = DrawingSurface(org.robolectric.RuntimeEnvironment.getApplication()).apply {
            configureBlank(512,512); layout(0,0,800,800)
        }
        val raster = surface.getChildAt(0) as RasterCanvasView
        val store = raster.layerStack.selectedStore()!!
        store.putFillPixel(100,100,Color.RED)
        surface.selectAll()
        var time = 0L
        fun send(action: Int, x: Float) {
            val xy = floatArrayOf(x,100f).also(raster.transformMatrix::mapPoints)
            android.view.MotionEvent.obtain(0,time.also { time+=10 },action,1,
                arrayOf(android.view.MotionEvent.PointerProperties().apply { id=0; toolType=android.view.MotionEvent.TOOL_TYPE_STYLUS }),
                arrayOf(android.view.MotionEvent.PointerCoords().apply { this.x=xy[0]; y=xy[1]; pressure=1f }),
                0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_STYLUS,0).also {
                surface.dispatchTouchEvent(it); it.recycle()
            }
        }
        assertTrue(surface.armShadowPull())
        send(android.view.MotionEvent.ACTION_DOWN,100f)
        send(android.view.MotionEvent.ACTION_MOVE,300f)
        assertNotNull(raster.shadowPreview)
        assertEquals(0,Color.alpha(store.colorAt(300,100)))
        send(android.view.MotionEvent.ACTION_CANCEL,300f)
        assertNull(raster.shadowPreview)
        assertFalse(store.history.canUndo())
        assertTrue(surface.armShadowPull())
        send(android.view.MotionEvent.ACTION_DOWN,100f)
        send(android.view.MotionEvent.ACTION_UP,300f)
        assertEquals(Color.RED,store.colorAt(100,100))
        assertTrue(Color.alpha(store.colorAt(300,100)) > 0)
        assertTrue(store.history.undo())
        assertEquals(0,Color.alpha(store.colorAt(300,100)))
    }

}
