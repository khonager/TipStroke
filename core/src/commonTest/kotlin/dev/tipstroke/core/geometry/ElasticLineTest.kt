package dev.tipstroke.core.geometry

import kotlin.test.*

class ElasticLineTest {
    @Test fun fitAndBendKeepEndpointsAndMoveMiddleByDragDistance() {
        val input = listOf(Point(0f, 0f), Point(50f, 40f), Point(100f, 0f))
        val fitted = ElasticLine.fit(input)
        assertEquals(input.first(), fitted.first())
        assertEquals(input.last(), fitted.last())
        assertEquals(40f, fitted[64].y, .01f)
        val bent = ElasticLine.bend(fitted, Point(20f, -30f))
        assertEquals(fitted.first(), bent.first())
        assertEquals(fitted.last(), bent.last())
        assertEquals(70f, bent[64].x, .01f)
        assertEquals(10f, bent[64].y, .01f)
        assertEquals(fitted, ElasticLine.bend(fitted, Point(0f, 0f)))
    }
}
