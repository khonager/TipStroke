package dev.tipstroke.core

import dev.tipstroke.core.geometry.*
import kotlin.test.*

class SelectionTransformTest {
    @Test fun transformsBooleanSelectionsWithoutFillingTheirHoles() {
        val selection = SelectionRegion.rectangle(Rect(0f, 0f, 10f, 10f)).combined(
            SelectionRegion.rectangle(Rect(3f, 3f, 7f, 7f)), SelectionOperation.SUBTRACT)
        val transform = SelectionTransform(Point(5f, 5f), 2f, -1f, 90f)
        val result = selection.transformed(transform)
        assertTrue(result.contains(transform.map(Point(1f, 1f))))
        assertFalse(result.contains(transform.map(Point(5f, 5f))))
        assertFalse(result.contains(transform.map(Point(12f, 12f))))
    }
    @Test fun identityAndInvalidScalesAreHandledExplicitly() {
        assertTrue(SelectionTransform(Point(3f, 4f)).isIdentity)
        assertFalse(SelectionTransform(Point(3f, 4f), -1f, 1f).isIdentity)
        assertFailsWith<IllegalArgumentException> { SelectionTransform(Point(0f, 0f), 0f) }
        assertFailsWith<IllegalArgumentException> { SelectionTransform(Point(0f, 0f), Float.NaN) }
    }
}
