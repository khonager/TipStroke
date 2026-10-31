package dev.tipstroke.app

import dev.tipstroke.core.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BrushLibraryTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearLibrary() {
        listOf("brush-library.json", "brush-library.json.bak", "brush-library.json.new").forEach {
            File(context.filesDir, it).delete()
        }
    }

    @Test fun allEnginesAndDetailedSettingsSurvivePortableRoundTrip() {
        BrushPreset.builtIns.forEach { base ->
            val original = LibraryBrush(base.copy(
                id = BrushId("custom-roundtrip"), displayName = "Fine café ✏", baseSizePx = 17f,
                pressureToSize = PressureCurve(.37f, 1f, 1.43f),
                pressureToOpacity = PressureCurve(1f, 1f, .29f), speedTaper = .83f,
                pencilPointSize = .63f, pencilShadeSize = 3.7f, pencilGrain = .23f,
                pencilTiltMode = PencilTiltMode.GRADUAL,
            ), "Creator", "Use and share freely")
            assertEquals(original, BrushPackage.read(ByteArrayInputStream(BrushPackage.encode(original).toByteArray())))
        }
    }

    @Test fun invalidAndFuturePackagesAreRejected() {
        val source = BrushPackage.encode(LibraryBrush(BrushPreset.Ink))
        listOf("version" to 99, "schemaVersion" to 99, "size" to -1,
            "opacity" to 5, "pressureSizeExponent" to 0, "pressureOpacityEnd" to .5,
            "engine" to "UNKNOWN", "name" to " ", "author" to "a".repeat(81)).forEach { (key, value) ->
            assertTrue("Accepted invalid $key", runCatching {
                BrushPackage.decode(JSONObject(source).put(key, value).toString())
            }.isFailure)
        }
        assertTrue(runCatching { BrushPackage.decode("not json") }.isFailure)
        assertTrue(runCatching { BrushPackage.read(ByteArrayInputStream(ByteArray(BrushPackage.MAX_BYTES + 1))) }.isFailure)
    }

    @Test fun importCopiesNeverCollideWithOriginalOrBuiltIns() {
        val original = LibraryBrush(BrushPreset.Ink)
        val first = BrushLibrary.copyOf(original)
        val second = BrushLibrary.copyOf(original)
        assertNotEquals(original.preset.id, first.preset.id)
        assertNotEquals(first.preset.id, second.preset.id)
        assertEquals(original.preset.copy(id = first.preset.id), first.preset)
    }

    @Test fun libraryPersistsEditsAndDeletionAndRejectsOverflowWithoutDataLoss() {
        val library = BrushLibrary(context)
        assertTrue(library.load().isEmpty())
        val first = BrushLibrary.copyOf(LibraryBrush(BrushPreset.Pencil))
        val second = BrushLibrary.copyOf(LibraryBrush(BrushPreset.Airbrush))
        library.save(listOf(first, second))
        assertEquals(listOf(first, second), BrushLibrary(context).load())
        val edited = first.copy(preset = first.preset.copy(displayName = "Changed"))
        library.save(listOf(edited, second))
        assertEquals(edited, library.load().first())
        assertTrue(runCatching { library.save(List(201) { first }) }.isFailure)
        assertEquals(listOf(edited, second), library.load())
        library.save(listOf(second))
        assertEquals(listOf(second), library.load())
    }

    @Test fun customSourcesAndDynamicsTravelTogetherAndLegacyPackagesStillImport() {
        val texture = BrushTexture(3, 3, byteArrayOf(0, 32, 64, 96, 127, -96, -64, -32, -1))
        val brush = LibraryBrush(BrushPreset.Blank.copy(stamp = StampBrush(shape = StampShape.IMAGE,
            shapeTexture = texture, grain = StampGrain.IMAGE, grainTexture = texture.inverted(),
            grainPlacement = GrainPlacement.STAMP, scatter = 1.2f, count = 5, hueJitter = .3f)))
        assertEquals(brush, BrushPackage.decode(BrushPackage.encode(brush)))
        val legacy = JSONObject(BrushPackage.encode(LibraryBrush(BrushPreset.Ink))).put("version", 1).put("schemaVersion", 2)
        assertNull(BrushPackage.decode(legacy.toString()).preset.stamp)
        val broken = JSONObject(BrushPackage.encode(brush))
        broken.getJSONObject("stamp").getJSONObject("shapeTexture").put("width", 257)
        assertTrue(runCatching { BrushPackage.decode(broken.toString()) }.isFailure)
    }

    @Test fun selectingCreatedBrushRetainsExactDynamics() {
        val brush = BrushPreset.Ink.copy(id = BrushId("custom-selection"), speedTaper = .21f,
            pressureToSize = PressureCurve(1f, 1f, 1.8f), pressureToOpacity = PressureCurve(.44f, 1f, 1.6f))
        val preferences = BrushPreferences(context)
        preferences.save(brush, brush.creatorTuning())
        val loaded = preferences.load(brush)
        assertFalse(loaded.pressureSize)
        assertTrue(loaded.pressureOpacity)
        assertEquals(.21f, loaded.speedTaperAmount, .0001f)
        assertEquals(.44f, loaded.pressureOpacityStart, .0001f)
        assertEquals(1.6f, loaded.pressureOpacityExponent, .0001f)
    }
}
