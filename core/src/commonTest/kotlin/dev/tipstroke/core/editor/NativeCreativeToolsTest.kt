package dev.tipstroke.core.editor

import kotlin.test.*

class NativeCreativeToolsTest {
    @Test fun elasticKeepsEndpoints() {
        val tracker = NativeShapeTracker()
        for (i in 0..100) tracker.append(i.toFloat(), 0f)
        val bent = tracker.elastic().bend(0f, 20f).points
        assertEquals(0f, bent.first().y)
        assertEquals(0f, bent.last().y)
        assertEquals(20f, bent[bent.size / 2].y)
    }
    @Test fun perspectiveLocksDirectionAndPushIsBounded() {
        val ruler = NativePerspectiveRuler(1, 0f, 0f, 100f, 0f, 4f)
        ruler.project(50f, 50f)
        assertEquals(50f, ruler.project(51f, 51f).x)
        val diagonal = ruler.project(70f, 69f)
        assertEquals(diagonal.x, diagonal.y)
        val push = NativeColorPush(20f, 20f, 1000f, 0f, 10f, 1f)
        assertEquals(15f, push.source(20f, 20f).x)
        assertEquals(30f, push.source(30f, 20f).x)
    }
    @Test fun recipesAndTransparentSeedsSurviveNativeBoundary() {
        val recipe = NativeColorizeRecipe("line-art", "hints", 0, .2f)
        val restored = NativeColorizeRecipe.decode(recipe.encode())
        assertEquals("line-art", restored.lineArtId)
        assertEquals("hints", restored.hintsId)
        val result = NativeRasterEffects().colorize(3, 1, byteArrayOf(0, -1, 0),
            intArrayOf(0xffff0000.toInt(), 0, 0xffff00ff.toInt()), 0, .2f)
        assertContentEquals(intArrayOf(0xffff0000.toInt(), 0, 0), result)
        assertFailsWith<IllegalArgumentException> { NativeColorizeRecipe.decode("{}") }
    }
    @Test fun nativeFiltersUseTransparentPremultipliedEdges() {
        val input = IntArray(9 * 9); input[40] = 0xffff0000.toInt()
        val engine = NativeRasterEffects()
        for (effect in 0..3) {
            val output = engine.apply(input, 9, effect, 2, 30f, .5f, .5f, 0f, 1f, 0, 0, 9, 9)
            assertEquals(input.size, output.size)
            assertTrue(output[40] ushr 24 > 0)
            output.filter { it ushr 24 > 0 }.forEach { assertEquals(0, it and 0xffff) }
        }
    }
}
