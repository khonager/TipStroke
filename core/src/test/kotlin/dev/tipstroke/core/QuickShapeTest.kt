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
