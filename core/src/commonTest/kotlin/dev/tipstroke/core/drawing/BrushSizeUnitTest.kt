package dev.tipstroke.core.drawing

import kotlin.test.Test
import kotlin.test.assertEquals

class BrushSizeUnitTest {
    @Test fun screenDiameterStaysConstantAcrossZoomAndDocumentResolution() {
        for (zoom in listOf(.08f, .5f, 1f, 4f, 16f)) {
            val documentSize = BrushSizeUnit.SCREEN_DP.documentPixels(60f, zoom, 2f)
            assertEquals(120f, documentSize * zoom, .001f)
        }
    }

    @Test fun canvasPixelsAndPixelToolsNeverScaleTheirAuthoredCells() {
        for (zoom in listOf(.08f, 1f, 16f)) {
            assertEquals(32f, BrushSizeUnit.CANVAS_PIXELS.documentPixels(32f, zoom, 3f))
            for (tool in PixelTool.entries) {
                assertEquals(1f, BrushSizeUnit.SCREEN_DP.documentPixels(1f, zoom, 3f, tool))
            }
        }
    }
}
