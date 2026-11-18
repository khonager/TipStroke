package dev.tipstroke.core.brush

import dev.tipstroke.core.editor.NativeBrush
import dev.tipstroke.core.model.*
import kotlin.test.*

class BrushPackageTest {
    @Test fun everyBuiltInRoundTripsWithoutChangingPressureOrTilt() {
        for (preset in BrushPreset.libraryBuiltIns + BrushPreset.Blank) {
            val original = LibraryBrush(preset, "TipStroke", "Test brush")
            assertEquals(original, BrushPackage.decode(BrushPackage.encode(original)))
        }
    }
    @Test fun customTextureBytesRoundTripOnAllPlatforms() {
        val brush = LibraryBrush(BrushPreset.Blank.copy(stamp = StampBrush(
            shape = StampShape.IMAGE,
            shapeTexture = BrushTexture(2, 2, byteArrayOf(0, 127, -1, 32)),
        )))
        val restored = BrushPackage.decode(BrushPackage.encode(brush))
        assertContentEquals(brush.preset.stamp!!.shapeTexture!!.bytes(), restored.preset.stamp!!.shapeTexture!!.bytes())
    }
    @Test fun unsafeAndFuturePackagesAreRejected() {
        val valid = BrushPackage.encode(LibraryBrush(BrushPreset.Ink))
        assertFails { BrushPackage.decode(valid.replace("\"version\":2", "\"version\":500")) }
        assertFails { BrushPackage.decode(valid.replace("\"size\":18.0", "\"size\":-10")) }
        assertFails { BrushPackage.decode(" ".repeat(BrushPackage.MAX_BYTES + 1)) }
        assertFails { BrushPackage.decode(valid.replace("\"schemaVersion\":4", "\"schemaVersion\":4.5")) }
    }
    @Test fun nativeAdapterUsesAndroidPresetCurvesAndTiltRules() {
        for (preset in BrushPreset.builtIns) {
            val brush = NativeBrush(preset)
            for (pressure in listOf(0f, .2f, .5f, 1f)) {
                assertEquals(preset.pressureToSize.map(pressure), brush.sizeResponse(pressure))
                assertEquals(preset.pressureToOpacity.map(pressure), brush.opacityResponse(pressure))
            }
            assertEquals(preset.pencilTiltResponse(.8f), brush.tiltResponse(.8f))
            assertEquals(preset, NativeBrush.builtIn(brush.id).preset)
        }
    }
}
