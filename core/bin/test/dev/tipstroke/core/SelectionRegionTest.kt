package dev.tipstroke.core

import dev.tipstroke.core.geometry.*
import kotlin.test.*

class SelectionRegionTest {
    private val a = SelectionRegion.rectangle(Rect(0f, 0f, 20f, 20f))
    private val b = SelectionRegion.rectangle(Rect(10f, 10f, 30f, 30f))
    @Test fun booleanOperationsAndTranslationKeepHoles() {
        val union = a.combined(b, SelectionOperation.ADD)
        val difference = a.combined(b, SelectionOperation.SUBTRACT)
        val intersection = a.combined(b, SelectionOperation.INTERSECT)
        assertTrue(union.contains(Point(25f, 25f)))
        assertTrue(difference.contains(Point(5f, 5f)))
        assertFalse(difference.contains(Point(15f, 15f)))
        assertTrue(intersection.contains(Point(15f, 15f)))
        assertFalse(intersection.contains(Point(5f, 5f)))
        assertFalse(difference.translated(40f, 50f).contains(Point(55f, 65f)))
        assertTrue(difference.translated(40f, 50f).contains(Point(45f, 55f)))
        assertEquals(b, a.combined(b, SelectionOperation.REPLACE))
    }
    @Test fun ellipseExcludesCornersAndNormalizesReverseDrag() {
        val oval = SelectionRegion.ellipse(Rect(100f, 50f, 0f, 0f))
        assertTrue(oval.contains(Point(50f, 25f)))
        assertFalse(oval.contains(Point(1f, 1f)))
    }
}
