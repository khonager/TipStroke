package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.net.Uri
import dev.tipstroke.core.model.*
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
class AnimationExporterTest {
    @Test fun gifDecodesEveryPixelAcrossDictionaryGrowthAndReset() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "dictionary.gif")
        val frames = List(3) { AnimationFrame(FrameId("$it")) }
        val random = java.util.Random(73)
        val colors = List(3) { IntArray(257 * 259) {
            val index = random.nextInt(256)
            android.graphics.Color.rgb(((index shr 5) and 7)*255/7, ((index shr 2) and 7)*255/7, (index and 3)*255/3)
        } }
        try {
            AnimationExporter.export(context.contentResolver, Uri.fromFile(file),
                SavedAnimationSnapshot(frames,0,12,PlaybackMode.ONCE,0,0,0f,emptySet(),emptyMap(),emptyMap()), AnimationExportFormat.GIF) { i ->
                Bitmap.createBitmap(colors[i],257,259,Bitmap.Config.ARGB_8888)
            }
            // Android's compile classpath omits java.desktop; the host JVM supplies this independent decoder.
            val imageIo = Class.forName("javax.imageio.ImageIO")
            val input = imageIo.getMethod("createImageInputStream", Any::class.java).invoke(null,file)
            val readers = imageIo.getMethod("getImageReadersByFormatName",String::class.java).invoke(null,"gif") as Iterator<*>
            val reader = readers.next()!!
            val readerType = Class.forName("javax.imageio.ImageReader")
            try {
                readerType.getMethod("setInput",Any::class.java).invoke(reader,input)
                assertEquals(3,readerType.getMethod("getNumImages",Boolean::class.javaPrimitiveType).invoke(reader,true))
                repeat(3) { i ->
                    val decoded = readerType.getMethod("read",Int::class.javaPrimitiveType).invoke(reader,i)
                    val pixels = decoded.javaClass.getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,IntArray::class.java,
                        Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(decoded,0,0,257,259,null,0,257) as IntArray
                    assertArrayEquals(colors[i],pixels)
                }
            } finally {
                readerType.getMethod("dispose").invoke(reader)
                (input as java.io.Closeable).close()
            }
        } finally { file.delete() }
    }
}
