package dev.tipstroke.core.geometry

import kotlin.math.hypot
import kotlin.test.*

class RibbonPathTest {
    @Test fun mirroredRibbonKeepsItsReflectedHandedness() {
        val points = listOf(Point(20f,20f), Point(50f,40f), Point(90f,70f))
        val wheel = dev.tipstroke.core.drawing.MirrorWheel(2,Point(100f,100f))
        val expected = RibbonPath.outline(points,15f).map { wheel.point(it,1) }
        val actual = RibbonPath.outline(points.map { wheel.point(it,1) },15f,mirrored=true)
        expected.zip(actual).forEach { (a,b) -> assertEquals(a.x,b.x,.001f); assertEquals(a.y,b.y,.001f) }
    }

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
