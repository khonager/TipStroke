package dev.tipstroke.drawing.android
import android.graphics.Color
import dev.tipstroke.core.effects.ColorizeMask
import dev.tipstroke.core.model.LayerId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RasterColorizeTest {
    @Test fun hintsRegenerateAcrossTilesWithoutTouchingLineArt() {
        val lines=TileStore(512,256);val hints=TileStore(512,256)
        for(y in 0 until 256) lines.putFillPixel(256,y,Color.BLACK)
        hints.putFillPixel(10,100,Color.RED);hints.putFillPixel(400,100,Color.BLUE)
        val mask=ColorizeMask(LayerId("lines"),LayerId("hints"),gap=0)
        val output=RasterColorize.render(lines,hints,lines.residentCoordinates()+hints.residentCoordinates(),mask){false}
        assertEquals(Color.RED,output.colorAt(255,100));assertEquals(Color.BLUE,output.colorAt(257,100))
        assertEquals(Color.BLACK,lines.colorAt(256,100));assertEquals(0,lines.colorAt(255,100))
        hints.putFillPixel(400,100,mask.transparentColor)
        val changed=RasterColorize.render(lines,hints,lines.residentCoordinates()+hints.residentCoordinates(),mask){false}
        assertEquals(0,changed.colorAt(400,100));assertEquals(Color.RED,changed.colorAt(10,100))
        output.discard();changed.discard()
    }
}
