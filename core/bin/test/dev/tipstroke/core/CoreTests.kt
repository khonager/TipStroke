package dev.tipstroke.core

import dev.tipstroke.core.drawing.UndoHistory
import dev.tipstroke.core.drawing.UndoTransaction
import dev.tipstroke.core.geometry.*
import dev.tipstroke.core.model.*
import kotlin.test.*

class CoreTests {
    @Test fun transformRoundTripsWithRotation() {
        val transform = CanvasTransform(90f, -24f, 1.75f, 31f)
        val source = Point(310f, 722f)
        val result = transform.screenToDocument(transform.documentToScreen(source))
        assertEquals(source.x, result.x, .001f); assertEquals(source.y, result.y, .001f)
    }
    @Test fun dirtyTileIntersectionIsSparseAndClamped() {
        assertEquals(setOf(TileCoordinate(0, 0), TileCoordinate(1, 0)),
            TileGrid.intersecting(Rect(250f, -20f, 270f, 24f), 2048, 2048))
        assertTrue(TileGrid.intersecting(Rect(-50f, -50f, -2f, -2f), 2048, 2048).isEmpty())
    }
    @Test fun pressureCurveIsBoundedAndMonotonic() {
        val curve = PressureCurve(.2f, 1f, .7f)
        assertEquals(.2f, curve.map(-1f)); assertEquals(1f, curve.map(2f)); assertTrue(curve.map(.7f) > curve.map(.3f))
    }
    @Test fun undoHistoryEvictsWithinBudgetAndRedoes() {
        var value = 2
        fun tx(before: Int, after: Int) = object : UndoTransaction {
            override val estimatedBytes = 10L
            override fun undo() { value = before }
            override fun redo() { value = after }
        }
        val history = UndoHistory(15)
        history.push(tx(0, 1)); history.push(tx(1, 2))
        assertTrue(history.undo()); assertEquals(1, value)
        assertFalse(history.undo()); assertTrue(history.redo()); assertEquals(2, value)
    }

    @Test fun imageLayerKeepsOriginalSourceSeparateFromTransform() {
        val layer = ImageLayer(
            id = LayerId("image-1"), name = "Reference.png", sourceId = "asset/original",
            originalWidthPx = 4032, originalHeightPx = 3024,
            transform = ImageTransform(1024f, 1024f, .25f),
        )
        val resized = layer.copy(transform = layer.transform.copy(scale = 1f))
        assertEquals(4032, resized.originalWidthPx)
        assertEquals("asset/original", resized.sourceId)
    }

    @Test fun gestureSettingsHaveProfessionalDrawingDefaults() {
        val settings = GestureSettings()
        assertEquals(FingerAction.NAVIGATE, settings.oneFingerDrag)
        assertEquals(FingerAction.PICK_COLOR, settings.oneFingerHold)
        assertEquals(FingerAction.UNDO, settings.twoFingerTap)
        assertEquals(FingerAction.REDO, settings.threeFingerTap)
    }
}
