package dev.tipstroke.core.drawing

import dev.tipstroke.core.model.*
import dev.tipstroke.core.brush.*
import kotlin.test.*

class MangaTipsTest {
    @Test fun tonesIncreaseDensityAndRoundTripAsOrdinaryBrushes() {
        val tips = MangaTips.presets
        val densities = tips.take(3).map { it.stamp!!.grainTexture!!.bytes().count { b -> b.toInt() != 0 } }
        assertTrue(densities.zipWithNext().all { (a,b) -> a < b })
        tips.forEach { tip ->
            val decoded = BrushPackage.decode(BrushPackage.encode(LibraryBrush(tip,"TipStroke"))).preset
            assertEquals(tip, decoded)
            assertEquals(GrainPlacement.CANVAS, tip.stamp!!.grainPlacement)
        }
        assertEquals(6,tips.map { it.id }.distinct().size)
    }
}
