package dev.tipstroke.core

import dev.tipstroke.core.drawing.UndoHistory
import dev.tipstroke.core.drawing.UndoTransaction
import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.drawing.PointerKind
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.drawing.StrokeStyle
import dev.tipstroke.core.geometry.*
import dev.tipstroke.core.model.*
import kotlin.test.*

class CoreTests {
    @Test fun imageTransformsRemainNonDestructiveMetadata() {
        val transformed = ImageTransform(250f, 200f, 1f).changedBy(35f, -20f, .5f, 22f)
        assertEquals(285f, transformed.centerX, .001f)
        assertEquals(180f, transformed.centerY, .001f)
        assertEquals(.5f, transformed.scale, .001f)
        assertEquals(22f, transformed.rotationDegrees, .001f)
    }

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
    @Test fun pencilTiltCalibrationMapsCapturedAngleToSensitivity() {
        val capturedAngle = .27f
        val sensitivity = pencilTiltSensitivityForFullAngle(capturedAngle)
        val calibrated = BrushPreset.Pencil.copy(pencilTiltSensitivity = sensitivity)
        assertEquals(capturedAngle, calibrated.pencilFullTiltRadians(), .001f)
        assertEquals(BrushPreset.MIN_PENCIL_FULL_TILT_RADIANS, BrushPreset.Pencil.copy(pencilTiltSensitivity = 1f).pencilFullTiltRadians(), .001f)
        assertEquals(BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS, BrushPreset.Pencil.copy(pencilTiltSensitivity = 0f).pencilFullTiltRadians(), .001f)
    }
    @Test fun customBrushBoundsCoverTiltAndParticleScatter() {
        val sample = StrokeSample(1, Point(100f, 100f), 1f, 1f, 0f, 0L, 0, PointerKind.STYLUS)
        val pencil = CompletedStroke(
            listOf(sample),
            StrokeStyle(BrushPreset.Pencil, 20f, 1f, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT),
        )
        val airbrush = CompletedStroke(
            listOf(sample),
            StrokeStyle(BrushPreset.Airbrush, 20f, 1f, RgbaColor(0f, 0f, 0f), BlendBehavior.PAINT),
        )
        assertEquals(65f, pencil.bounds.left)
        assertEquals(135f, pencil.bounds.right)
        assertEquals(73f, airbrush.bounds.left)
        assertEquals(127f, airbrush.bounds.right)
    }
    @Test fun lassoSelectionSupportsContainmentAndTranslation() {
        val lasso = SelectionRegion(listOf(Point(10f, 10f), Point(90f, 10f), Point(50f, 90f)))
        assertTrue(lasso.contains(Point(50f, 40f)))
        assertFalse(lasso.contains(Point(10f, 90f)))
        val moved = lasso.translated(100f, 20f)
        assertTrue(moved.contains(Point(150f, 60f)))
        assertEquals(110f, moved.bounds.left)
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
