package dev.tipstroke.core

import dev.tipstroke.core.model.ColorHarmony
import dev.tipstroke.core.model.LayerBlendMode
import kotlin.test.*

class ColorHarmonyTest {
    @Test fun redSchemesHaveExpectedRelationships() {
        assertEquals(listOf(0f, 180f), ColorHarmony.COMPLEMENTARY.hues(0f))
        assertEquals(listOf(0f, 150f, 210f), ColorHarmony.SPLIT_COMPLEMENTARY.hues(0f))
        assertEquals(listOf(0f, 30f, 330f), ColorHarmony.ANALOGOUS.hues(0f))
        assertEquals(listOf(0f, 120f, 240f), ColorHarmony.TRIADIC.hues(0f))
        assertEquals(listOf(0f, 90f, 180f, 270f), ColorHarmony.TETRADIC.hues(0f))
    }
    @Test fun wraparoundAndInvalidInput() {
        assertEquals(listOf(350f, 170f), ColorHarmony.COMPLEMENTARY.hues(-10f))
        assertEquals(ColorHarmony.TRIADIC.hues(15f), ColorHarmony.TRIADIC.hues(735f))
        assertFailsWith<IllegalArgumentException> { ColorHarmony.TRIADIC.hues(Float.NaN) }
    }
    @Test fun oldAndUnknownBlendNamesRemainReadable() {
        assertEquals(LayerBlendMode.NORMAL, LayerBlendMode.fromStorage(""))
        assertEquals(LayerBlendMode.NORMAL, LayerBlendMode.fromStorage("FUTURE"))
        assertEquals(LayerBlendMode.COLOR_BURN, LayerBlendMode.fromStorage("COLOR_BURN"))
    }
}
