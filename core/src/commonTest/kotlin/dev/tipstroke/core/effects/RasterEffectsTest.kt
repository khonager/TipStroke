package dev.tipstroke.core.effects
import kotlin.test.*
class RasterEffectsTest {
    @Test fun gaussianKeepsColorAtTransparentEdgesAndSpreadsSymmetrically() {
        val pixels=IntArray(21*21); pixels[10*21+10]=0xffff0000.toInt()
        val out=RasterEffects.apply(pixels,21,21,RasterEffectSettings(radius=3))
        assertEquals(out[10*21+9],out[10*21+11])
        assertTrue(out[10*21+9] ushr 24 > 0)
        assertEquals(255,(out[10*21+9] ushr 16) and 255)
    }
    @Test fun motionDirectionAndBloomThresholdDiffer() {
        val pixels=IntArray(21*21); pixels[10*21+10]=0xffffffff.toInt()
        val motion=RasterEffects.apply(pixels,21,21,RasterEffectSettings(RasterEffect.MOTION,radius=3))
        assertTrue(motion[10*21+9] ushr 24 > 0); assertEquals(0,motion[9*21+10])
        val bloom=RasterEffects.apply(pixels,21,21,RasterEffectSettings(RasterEffect.BLOOM,radius=3))
        assertEquals(0xffffffff.toInt(),bloom[10*21+10]); assertTrue(bloom[9*21+10] ushr 24 > 0)
        pixels[10*21+10]=0xff000000.toInt()
        assertContentEquals(pixels,RasterEffects.apply(pixels,21,21,RasterEffectSettings(RasterEffect.BLOOM,radius=3)))
    }
}
