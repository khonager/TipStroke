package dev.tipstroke.drawing.android

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorizePersistenceTest {
    @Test fun workerPreviewCancelsAndApplyRestoresPixelsAndRecipeThroughUndo() {
        val surface=DrawingSurface(RuntimeEnvironment.getApplication()).apply { configureBlank(32,32) }
        val raster=surface.getChildAt(0) as RasterCanvasView
        val stack=raster.layerStack;val lines=stack.selectedRaster()!!
        for(y in 0 until 32) lines.tiles.putFillPixel(16,y,Color.BLACK)
        assertTrue(surface.createColorizeMask())
        val hints=stack.selectedRaster()!!;hints.tiles.putFillPixel(5,5,Color.RED)
        val mask=surface.colorizeMaskSettings()!!.copy(gap=0)
        val output=stack.layers.filterIsInstance<RasterLayerRuntime>().single { it.colorizeMask!=null }
        fun preview(mutate: Boolean=false): Result<Unit> {
            var result: Result<Unit>?=null
            surface.previewColorizeMask(mask){result=it}
            if(mutate) hints.tiles.putFillPixel(5,5,Color.BLUE)
            val deadline=System.nanoTime()+20_000_000_000L
            while(result==null && System.nanoTime()<deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            return requireNotNull(result) { "Colorize worker timed out" }
        }
        preview().getOrThrow()
        assertEquals(0,output.tiles.colorAt(5,5));assertTrue(hints.visible)
        surface.cancelRasterAdjustment();assertNull(raster.adjustmentPreview)
        preview().getOrThrow();assertTrue(surface.applyRasterAdjustment())
        assertEquals(Color.RED,output.tiles.colorAt(5,5));assertFalse(hints.visible)
        assertTrue(output.tiles.history.undo());assertEquals(0,output.tiles.colorAt(5,5));assertTrue(hints.visible)
        assertTrue(output.tiles.history.redo());assertEquals(Color.RED,output.tiles.colorAt(5,5));assertFalse(hints.visible)
        assertTrue(surface.editColorizeHints())
        assertTrue(preview(mutate=true).isFailure)
        assertNull(raster.adjustmentPreview);assertEquals(Color.RED,output.tiles.colorAt(5,5))
        assertEquals(Color.BLACK,lines.tiles.colorAt(16,5))
    }

    @Test fun editableInputsAndSettingsSurviveSaveReopenAndArchiveExchange() {
        val context=RuntimeEnvironment.getApplication()
        val library=DrawingLibrary(context)
        val surface=DrawingSurface(context).apply { configureBlank(32,32) }
        val stack=(surface.getChildAt(0) as RasterCanvasView).layerStack
        val lines=stack.selectedRaster()!!
        lines.tiles.putFillPixel(16,16,Color.BLACK)
        assertTrue(surface.createColorizeMask())
        val mask=surface.colorizeMaskSettings()!!
        assertEquals(lines.id,mask.lineArtId)
        assertEquals(mask.hintsId,stack.selectedId)
        stack.selectedRaster()!!.tiles.putFillPixel(5,5,Color.RED)
        val output=stack.layers.filterIsInstance<RasterLayerRuntime>().single { it.colorizeMask!=null }
        output.colorizeMask=mask.copy(gap=3,threshold=.4f)
        assertTrue(stack.layers.indexOf(output)<stack.layers.indexOf(lines))
        val snapshot=stack.snapshot();val id=library.newId()
        try {
            ProjectPersistence.save(context.contentResolver,library,id,"Colorize round trip",snapshot).getOrThrow()
            val bytes=ByteArrayOutputStream().also { ProjectTransfer.exportProjects(library,listOf(id),it) }.toByteArray()
            val before=library.list().map { it.id }.toSet()
            ProjectTransfer.importProjects(library,ByteArrayInputStream(bytes))
            val imported=library.list().single { it.id !in before }.id
            for(saved in listOf(id,imported)) {
                val loaded=ProjectPersistence.load(library.projectDirectory(saved)).getOrThrow()
                val restored=DrawingSurface(context)
                val restoredStack=(restored.getChildAt(0) as RasterCanvasView).layerStack
                restoredStack.replaceWith(loaded)
                restoredStack.select(output.id)
                assertEquals(output.colorizeMask,restored.colorizeMaskSettings())
                assertTrue(restored.editColorizeHints())
                assertEquals(Color.RED,restoredStack.selectedRaster()!!.tiles.colorAt(5,5))
                loaded.layers.filterIsInstance<LoadedRaster>().forEach { layer -> layer.tiles.values.forEach { it.recycle() } }
            }
        } finally { snapshot.recycle() }
    }
}
