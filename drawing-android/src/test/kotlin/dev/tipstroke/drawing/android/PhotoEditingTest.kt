package dev.tipstroke.drawing.android

import android.graphics.*
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEditingTest {
    @Test fun photoMetadataRoundTripsAndLegacyDefaultsWork() {
        val base=ImageTransform(50f,40f,2f,30f)
        val transformed=base.copy(stretchX=2f,stretchY=.7f,warp=PhotoWarp.identity().deform(PhotoEditTool.BULGE,Point(.5f,.5f),Point(.03f,0f),.6f,1f))
        assertEquals(transformed,JSONObject(JSONObject().putPhotoTransform(transformed).toString()).readPhotoTransform(base))
        assertEquals(base,JSONObject().readPhotoTransform(base))
        val p=transformed.documentPoint(.35f,.6f,100,80)
        val inverse=transformed.sourcePoint(p.x,p.y,100,80)!!
        assertEquals(.35f,inverse.x,.0001f);assertEquals(.6f,inverse.y,.0001f)
    }

    @Test fun warpedPhotoSurvivesProjectSaveAndExport() {
        val context=RuntimeEnvironment.getApplication()
        val library=DrawingLibrary(context); val id=library.newId(); val layerId=LayerId("warped-photo")
        val source=File(context.cacheDir,"warped-source.png")
        val output=File(context.cacheDir,"warped-export.png")
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        val t=ImageTransform(64f,64f,1f,20f,1.4f,.7f,
            PhotoWarp.identity().deform(PhotoEditTool.BULGE,Point(.5f,.5f),Point(.04f,0f),.6f,1f))
        val snapshot=DrawingSnapshot(128,128,layerId,listOf(SavedImageSnapshot(layerId,"Photo",true,1f,Uri.fromFile(source),64,64,t)))
        try {
            ProjectPersistence.save(context.contentResolver,library,id,"Warped",snapshot).getOrThrow()
            val loaded=ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
            assertEquals(t,(loaded.layers.single() as LoadedImage).transform)
            ProjectPersistence.export(context.contentResolver,Uri.fromFile(output),snapshot,ExportFormat.PNG,100,1f,transparent=true).getOrThrow()
            val exported=BitmapFactory.decodeFile(output.path)
            assertEquals(Color.RED,exported.getPixel(64,64))
            assertEquals(0,Color.alpha(exported.getPixel(0,0)))
            exported.recycle()
        } finally { snapshot.recycle();library.delete(id);source.delete();output.delete() }
    }

    @Test fun meshRenderingAndMaskMoveTogether() {
        val source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val output=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        val mask=TileStore(64,64)
        for(y in 20..40) for(x in 20..40) mask.putFillPixel(x,y,Color.BLACK)
        val t=ImageTransform(64f,64f,1f,20f,1.2f,.8f,PhotoWarp.identity().deform(PhotoEditTool.PUSH,Point(.5f,.5f),Point(.05f,0f),.4f,1f))
        Canvas(output).drawPhoto(source,64,64,t,Paint(),mask.photoMaskTiles())
        val hole=t.documentPoint(.5f,.5f,64,64)
        assertEquals(0,Color.alpha(output.getPixel(hole.x.toInt(),hole.y.toInt())))
        val red=t.documentPoint(.15f,.15f,64,64)
        assertTrue(Color.red(output.getPixel(red.x.toInt(),red.y.toInt()))>200)
        source.recycle();output.recycle();mask.discard()
    }

    @Test fun photoDragHasUndoAndCancelRestoresShape() {
        val context=RuntimeEnvironment.getApplication()
        val file=File(context.cacheDir,"photo-edit-test.png")
        Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).also { image ->
            image.eraseColor(Color.RED);file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        }
        try {
            val surface=DrawingSurface(context).apply {
                configureBlank(128,128)
                val spec=View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY)
                measure(spec,spec);layout(0,0,600,600)
                addImage(Uri.fromFile(file)).getOrThrow();setImageTransformMode(true)
                photoEditTool=PhotoEditTool.PUSH
            }
            val field=DrawingSurface::class.java.getDeclaredField("layerStack").apply { isAccessible=true }
            val image=(field.get(surface) as LayerStack).selectedImage()!!
            val before=image.transform
            fun event(action:Int,x:Float) { MotionEvent.obtain(0,20,action,x,300f,0).also { surface.dispatchTouchEvent(it);it.recycle() } }
            event(MotionEvent.ACTION_DOWN,300f);event(MotionEvent.ACTION_MOVE,320f);event(MotionEvent.ACTION_UP,320f)
            assertNotEquals(before,image.transform)
            val edited=image.transform
            surface.undo();assertEquals(before,image.transform)
            surface.redo();assertEquals(edited,image.transform)
            event(MotionEvent.ACTION_DOWN,300f);event(MotionEvent.ACTION_MOVE,330f);event(MotionEvent.ACTION_CANCEL,330f)
            assertEquals(edited,image.transform)
            val snap=DrawingSurface::class.java.getDeclaredMethod("snapPhoto",ImageLayerRuntime::class.java,ImageTransform::class.java).apply { isAccessible=true }
            val snapped=snap.invoke(surface,image,ImageTransform(32.5f,32.5f,1f,1f)) as ImageTransform
            assertEquals(32f,snapped.centerX,.001f);assertEquals(32f,snapped.centerY,.001f)
            assertEquals(0f,snapped.rotationDegrees,.001f)
            val free=snap.invoke(surface,image,ImageTransform(44f,44f,1f,7f)) as ImageTransform
            assertEquals(7f,free.rotationDegrees,.001f)
            surface.settings.gestures = surface.settings.gestures.copy(imageSnapAngle = 90, imageSnapStrength = 0f)
            val light = snap.invoke(surface,image,ImageTransform(44f,44f,1f,88f)) as ImageTransform
            assertEquals(88f,light.rotationDegrees,.001f)
            surface.settings.gestures = surface.settings.gestures.copy(imageSnapStrength = 1f)
            val strong = snap.invoke(surface,image,ImageTransform(44f,44f,1f,88f)) as ImageTransform
            assertEquals(90f,strong.rotationDegrees,.001f)
            val away = snap.invoke(surface,image,ImageTransform(44f,44f,1f,80f)) as ImageTransform
            assertEquals(80f,away.rotationDegrees,.001f)
        } finally { file.delete() }
    }
}
