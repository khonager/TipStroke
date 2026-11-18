package dev.tipstroke.core.effects
import dev.tipstroke.core.model.LayerId
import kotlin.test.*
class ColorizeMaskTest {
    private val mask=ColorizeMask(LayerId("lines"),LayerId("hints"),gap=0)
    @Test fun inkSeparatesColoredAndTransparentRegions() {
        val ink=ByteArray(9*9) { if(it%9==4) 255.toByte() else 0 }
        val seeds=IntArray(81);seeds[4*9+1]=0xffff0000.toInt();seeds[4*9+7]=mask.transparentColor
        val out=ColorizeSolver.solve(9,9,ink,seeds,mask)
        assertEquals(0xffff0000.toInt(),out[0]);assertEquals(0,out[8]);assertEquals(0,out[4])
        seeds[4*9+7]=0xff0000ff.toInt()
        val edited=ColorizeSolver.solve(9,9,ink,seeds,mask)
        assertEquals(0xff0000ff.toInt(),edited[8]);assertEquals(0xffff0000.toInt(),edited[0])
    }
    @Test fun smallGapCanBeClosedAndUnseededRegionStaysEmpty() {
        val ink=ByteArray(81){if(it%9==4 && it/9!=4) 255.toByte() else 0}
        val seeds=IntArray(81);seeds[4*9+1]=0xffff0000.toInt()
        assertEquals(0xffff0000.toInt(),ColorizeSolver.solve(9,9,ink,seeds,mask)[8])
        assertEquals(0,ColorizeSolver.solve(9,9,ink,seeds,mask.copy(gap=1))[8])
    }
    @Test fun settingsRoundTripAndUnknownVersionFails() {
        assertEquals(mask,ColorizeMaskCodec.decode(ColorizeMaskCodec.encode(mask)))
        assertFails { ColorizeMaskCodec.decode(ColorizeMaskCodec.encode(mask).replace("\"version\":1","\"version\":99")) }
    }
}
