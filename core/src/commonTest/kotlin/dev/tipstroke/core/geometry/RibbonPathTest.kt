package dev.tipstroke.core.geometry

import kotlin.math.hypot
import kotlin.test.*

class RibbonPathTest {
    @Test fun trailingEdgeStaysWithinWidthAcrossTurnsAndDuplicateSamples() {
        val points = listOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 0f), Point(100f, 100f), Point(30f, 30f))
        val outline = RibbonPath.outline(points, 20f)
        assertEquals(points.size * 2, outline.size)
        points.forEachIndexed { i, p ->
            val trailing = outline[outline.lastIndex - i]
            assertTrue(hypot(p.x - trailing.x, p.y - trailing.y) <= 20.001f)
        }
        assertEquals(emptyList(), RibbonPath.outline(listOf(points[0], points[0]), 20f))
    }
}
