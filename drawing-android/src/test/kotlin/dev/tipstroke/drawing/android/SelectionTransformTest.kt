package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import dev.tipstroke.core.geometry.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionTransformTest {
    @Test fun polygonTapsKeepDraftUntilFirstCornerClosesIt() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication())
        surface.layout(0, 0, 512, 512); surface.configureBlank(512, 512)
        surface.setSelectionTool(SelectionTool.POLYGON)
        val view = surface.getChildAt(0) as RasterCanvasView
        fun tap(x: Float, y: Float) {
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(0, 0, action, x, y, 0)
                surface.dispatchTouchEvent(event); event.recycle()
            }
        }
        tap(50f, 50f); tap(300f, 50f); tap(300f, 300f)
        assertNull(view.selectionRegion)
        assertEquals(3, view.selectionDraftPoints.size)
        tap(50f, 50f)
        assertNotNull(view.selectionRegion)
        assertTrue(view.selectionDraftPoints.isEmpty())
        tap(80f, 80f)
        surface.setSelectionTool(SelectionTool.LASSO)
        assertTrue(view.selectionDraftPoints.isEmpty())
        assertNotNull(view.selectionRegion)
    }

    private fun store(): TileStore = TileStore(8192, 8192).apply {
        val first = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(254, 40, Color.RED) }
        val second = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            setPixel(1, 43, Color.BLUE); setPixel(14, 40, Color.GREEN)
        }
        replaceTiles(mapOf(TileCoordinate(0, 0) to first, TileCoordinate(1, 0) to second))
    }
    private val selection = SelectionRegion.rectangle(Rect(254f, 40f, 258f, 44f))

    @Test fun rotateResizeAndBothFlipsPreserveUnselectedPixelsAndUndo() {
        val transforms = listOf(
            SelectionTransform(Point(256f, 42f), rotationDegrees = 90f),
            SelectionTransform(Point(256f, 42f), scaleX = -1f),
            SelectionTransform(Point(256f, 42f), scaleY = -1f),
            SelectionTransform(Point(256f, 42f), scaleX = 2f, scaleY = 2f),
        )
        transforms.forEach { transform ->
            val store = store()
            val sibling = store.duplicate()
            store.transformSelection(selection, transform, filtered = false)
            val red = transform.map(Point(254.5f, 40.5f))
            val blue = transform.map(Point(257.5f, 43.5f))
            assertEquals("Transform $transform", Color.RED, store.colorAt(floor(red.x).toInt(), floor(red.y).toInt()))
            assertEquals(Color.BLUE, store.colorAt(floor(blue.x).toInt(), floor(blue.y).toInt()))
            assertEquals(Color.GREEN, store.colorAt(270, 40))
            assertEquals(Color.RED, sibling.colorAt(254, 40))
            assertTrue(store.allocatedTileCount <= 4)
            assertTrue(store.history.undo())
            assertEquals(Color.RED, store.colorAt(254, 40))
            assertEquals(Color.BLUE, store.colorAt(257, 43))
            assertTrue(store.history.redo())
            assertEquals("Transform $transform", Color.RED, store.colorAt(floor(red.x).toInt(), floor(red.y).toInt()))
            store.discard(); sibling.discard()
        }
    }

    @Test fun previewCancelAndApplyKeepOneUndoStepAcrossSelectedLayers() {
        val surface = DrawingSurface(RuntimeEnvironment.getApplication())
        surface.layout(0, 0, 512, 512); surface.configureBlank(512, 512)
        val view = surface.getChildAt(0) as RasterCanvasView
        val stack = view.layerStack
        val first = stack.selectedRaster()!!
        first.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(20, 20, Color.RED) }))
        surface.addPaintLayer()
        val second = stack.selectedRaster()!!
        second.tiles.replaceTiles(mapOf(TileCoordinate(0, 0) to Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { setPixel(30, 20, Color.BLUE) }))
        surface.toggleLayerSelection(first.id)
        surface.selectAll()
        assertTrue(surface.beginSelectionTransform())
        surface.previewSelectionTransform(-1f, 1f, 0f, false)
        assertNotNull(view.selectionMeshTransform)
        assertEquals(Color.RED, first.tiles.colorAt(20, 20))
        surface.cancelSelectionTransform()
        assertNull(view.selectionMeshTransform)
        assertEquals(Color.BLUE, second.tiles.colorAt(30, 20))
        assertTrue(surface.beginSelectionTransform())
        surface.previewSelectionTransform(-1f, 1f, 0f, false)
        assertTrue(surface.applySelectionTransform())
        assertEquals(Color.RED, first.tiles.colorAt(491, 20))
        assertEquals(Color.BLUE, second.tiles.colorAt(481, 20))
        surface.undo()
        assertEquals(Color.RED, first.tiles.colorAt(20, 20))
        assertEquals(Color.BLUE, second.tiles.colorAt(30, 20))
        surface.redo()
        assertEquals(Color.BLUE, second.tiles.colorAt(481, 20))
    }
    @Test fun meshAcrossTileBoundaryPreservesPixelsAndUndo() {
        val store=store()
        val bounds=selection.bounds
        val warp=dev.tipstroke.core.model.PhotoWarp.identity().moveControl(1,1,2,Point(.05f,.03f))
        val transform=SelectionTransform(Point(256f,42f),translation=Point(64f,32f),warp=warp,sourceBounds=bounds)
        val preview=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
        val source=store.duplicate(selection)
        source.drawTransformed(android.graphics.Canvas(preview),transform,android.graphics.Paint().apply { isFilterBitmap=false })
        store.transformSelection(selection,transform,false)
        for(y in 65..80) for(x in 310..330) assertEquals("$x,$y",preview.getPixel(x,y),store.colorAt(x,y))
        assertEquals(Color.GREEN,store.colorAt(270,40))
        assertEquals(Color.TRANSPARENT,store.colorAt(254,40))
        assertTrue(store.history.undo()); assertEquals(Color.RED,store.colorAt(254,40))
        assertTrue(store.history.redo()); assertEquals(Color.TRANSPARENT,store.colorAt(254,40))
        assertTrue(store.allocatedTileCount<=4)
        source.discard();store.discard();preview.recycle()
    }

    @Test fun nativeMeshGesturePreviewsWithoutMutatingAndCancelRestores() {
        val surface=DrawingSurface(RuntimeEnvironment.getApplication())
        surface.configureBlank(512,512)
        val spec=android.view.View.MeasureSpec.makeMeasureSpec(600,android.view.View.MeasureSpec.EXACTLY)
        surface.measure(spec,spec);surface.layout(0,0,600,600)
        val view=surface.getChildAt(0) as RasterCanvasView
        val layer=view.layerStack.selectedRaster()!!
        layer.tiles.replaceTiles(mapOf(TileCoordinate(0,0) to Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }))
        surface.selectAll(); assertTrue(surface.beginSelectionTransform())
        surface.selectionEditTool=dev.tipstroke.core.model.PhotoEditTool.WARP
        surface.transformMeshDivisions=4
        fun event(action: Int,x: Float) {
            android.view.MotionEvent.obtain(0,20,action,x,300f,0).also { surface.dispatchTouchEvent(it);it.recycle() }
        }
        event(android.view.MotionEvent.ACTION_DOWN,300f)
        event(android.view.MotionEvent.ACTION_MOVE,315f)
        assertNotNull(view.selectionMeshTransform!!.warp)
        assertEquals(Color.RED,layer.tiles.colorAt(200,200))
        event(android.view.MotionEvent.ACTION_CANCEL,315f)
        assertNull(view.selectionMeshTransform!!.warp)
        surface.selectionTransformAction("flipX")
        surface.selectionTransformAction("rotate")
        surface.selectionTransformAction("fit")
        val fit=view.selectionMeshTransform!!
        val points=listOf(Point(0f,0f),Point(512f,0f),Point(0f,512f),Point(512f,512f)).map(fit::map)
        assertTrue(points.all { it.x>=-.01f && it.x<=512.01f && it.y>=-.01f && it.y<=512.01f })
        surface.setSelectionTransformScale(.01f,8f)
        surface.selectionTransformAction("fit")
        assertTrue(view.selectionMeshTransform!!.scaleX.isFinite())
        surface.selectionTransformAction("reset")
        assertTrue(view.selectionMeshTransform!!.isIdentity)
        surface.cancelSelectionTransform();assertTrue(view.selectionSources.isEmpty())
    }
}
