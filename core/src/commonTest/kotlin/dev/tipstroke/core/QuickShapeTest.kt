package dev.tipstroke.core

import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.QuickShape
import kotlin.math.*
import kotlin.test.*

class QuickShapeTest {
    private fun path(vararg vertices: Point): List<Point> = vertices.toList().zipWithNext().flatMap { (a, b) ->
        List(30) { i -> val t = i / 30f; Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t) }
    } + vertices.last()

    @Test fun recognizesLinesAndRejectsCurves() {
        assertEquals(QuickShape.Kind.LINE, QuickShape.recognize(path(Point(0f, 0f), Point(120f, 40f)))?.kind)
        assertNull(QuickShape.recognize(path(Point(0f, 0f), Point(50f, 100f), Point(100f, 0f))))
        assertNull(QuickShape.recognize(List(100) { Point(2f, 2f) }))
    }

    @Test fun recognizesClosedShapes() {
        for ((width, kind) in listOf(100f to QuickShape.Kind.SQUARE, 200f to QuickShape.Kind.RECTANGLE)) {
            assertEquals(kind, QuickShape.recognize(path(Point(0f, 0f), Point(width, 0f), Point(width, 100f), Point(0f, 100f), Point(0f, 0f)))?.kind)
        }
        assertEquals(QuickShape.Kind.TRIANGLE, QuickShape.recognize(path(Point(50f, 0f), Point(100f, 100f), Point(0f, 100f), Point(50f, 0f)))?.kind)
        for ((radius, kind) in listOf(100f to QuickShape.Kind.CIRCLE, 180f to QuickShape.Kind.ELLIPSE)) {
            val loop = List(181) { i -> val t = i * PI / 90; Point(radius * cos(t).toFloat(), 100f * sin(t).toFloat()) }
            val shape = assertNotNull(QuickShape.recognize(loop))
            assertEquals(kind, shape.kind)
            assertTrue(hypot(shape.points.first().x - shape.points.last().x, shape.points.first().y - shape.points.last().y) < .001f)
        }
    }

    @Test fun recognizesContinuousArrow() {
        assertEquals(QuickShape.Kind.ARROW, QuickShape.recognize(path(Point(0f, 0f), Point(150f, 0f), Point(110f, -30f), Point(150f, 0f), Point(110f, 30f)))?.kind)
    }

    @Test fun rectangleCanBeginInMiddleOfEdgeAndBeRotated() {
        val points = path(Point(50f, 0f), Point(100f, 0f), Point(100f, 60f), Point(0f, 60f), Point(0f, 0f), Point(50f, 0f))
            .map { Point(it.x * .8f - it.y * .6f, it.x * .6f + it.y * .8f) }
        assertEquals(QuickShape.Kind.RECTANGLE, QuickShape.recognize(points)?.kind)
    }

    @Test fun adjustedLineKeepsItsStartAndFollowsThePen() {
        val line = requireNotNull(QuickShape.recognize(path(Point(10f, 20f), Point(110f, 20f))))
        val adjusted = QuickShape.adjust(line, Point(113f, 22f), Point(13f, 222f))
        assertEquals(line.points.first(), adjusted.points.first())
        assertTrue(hypot(adjusted.points.last().x - 10f, adjusted.points.last().y - 220f) < .001f)
        assertEquals(line, QuickShape.adjust(line, Point(110f, 20f), Point(110f, 20f)))
    }

    @Test fun resizedCircleStaysClosedAndCircular() {
        val circle = requireNotNull(QuickShape.recognize(List(181) { i ->
            val angle = i * PI / 90
            Point(100f * cos(angle).toFloat(), 100f * sin(angle).toFloat())
        }))
        val resized = QuickShape.adjust(circle, Point(100f, 0f), Point(0f, 200f))
        assertEquals(resized.points.first(), resized.points.last())
        resized.points.forEach { assertTrue(abs(hypot(it.x, it.y) - 200f) < .01f) }
    }

    @Test fun arrowShortcutProducesSymmetricHeadAndRemainsAdjustable() {
        val line = requireNotNull(QuickShape.recognize(path(Point(0f, 0f), Point(100f, 0f))))
        val arrow = QuickShape.arrow(line)
        assertEquals(QuickShape.Kind.ARROW, arrow.kind)
        assertEquals(-arrow.points.minOf { it.y }, arrow.points.maxOf { it.y })
        val adjusted = QuickShape.adjust(arrow, Point(100f, 0f), Point(0f, 200f))
        assertEquals(Point(0f, 0f), adjusted.points.first())
        assertTrue(abs(adjusted.points.maxOf { it.y } - 200f) < .01f)
    }

    @Test fun constraintsWorkInEveryQuadrant() {
        for (x in listOf(-1, 1)) for (y in listOf(-1, 1)) {
            val bounds = QuickShape.selectionBounds(Point(10f, 20f), Point(10f + 80 * x, 20f + 40 * y), true)
            assertEquals(80f, bounds.right - bounds.left)
            assertEquals(80f, bounds.bottom - bounds.top)
        }
        val bounds = QuickShape.selectionBounds(Point(0f, 0f), Point(80f, 40f), false)
        assertEquals(40f, bounds.bottom - bounds.top)
    }
}
