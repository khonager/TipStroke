package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProcessImageGestureTest {
    @Test fun imageDragRecordsOnlyReleasedPosition() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir,"gesture.png")
        Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.RED)
            file.outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG,100,out) }
            it.recycle()
        }
        try {
            val surface = DrawingSurface(context).apply {
                configureBlank(64,64)
                val spec = View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY)
                measure(spec,spec); layout(0,0,600,600)
                settings.emulateMouseWithTouch = false
                addImage(Uri.fromFile(file)).getOrThrow()
                setImageTransformMode(true)
            }
            val field = DrawingSurface::class.java.getDeclaredField("processRecorder").apply { isAccessible=true }
            val recorder = field.get(surface) as ProcessRecorder
            val before = recorder.snapshot().actionCount
            fun event(action: Int,x: Float) {
                val e = MotionEvent.obtain(0,20,action,x,300f,0)
                surface.dispatchTouchEvent(e); e.recycle()
            }
            event(MotionEvent.ACTION_DOWN,300f)
            repeat(20) { event(MotionEvent.ACTION_MOVE,320f+it*3) }
            assertEquals(before,recorder.snapshot().actionCount)
            event(MotionEvent.ACTION_UP,377f)
            assertEquals(before+1,recorder.snapshot().actionCount)
        } finally { file.delete() }
    }
}
