package dev.tipstroke.app

import dev.tipstroke.core.model.BrushPreset
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BrushPackTest {
    @Test fun packPreservesOrderedTipsAndSupportsSingleTipImports() {
        val brushes = listOf(LibraryBrush(BrushPreset.Blank, "Artist", "Texture tip"), LibraryBrush(BrushPreset.jetpackInk.last()))
        val pack = BrushPack.read(BrushPack.encode("My pack", brushes).byteInputStream())
        assertEquals("My pack", pack.name)
        assertEquals(brushes, pack.brushes)
        val single = BrushPack.read(BrushPackage.encode(brushes.first()).byteInputStream())
        assertNull(single.name)
        assertEquals(listOf(brushes.first()), single.brushes)
        val copies = pack.brushes.map { BrushLibrary.copyOf(it) }
        assertTrue(copies.none { copy -> brushes.any { it.preset.id == copy.preset.id } })
    }

    @Test fun invalidPackIsRejectedBeforeAnyTipsCanBeImported() {
        val original = BrushPack.encode("Pack", listOf(LibraryBrush(BrushPreset.Blank)))
        val invalid = JSONObject(original).apply {
            getJSONArray("brushes").put(JSONObject(BrushPackage.encode(LibraryBrush(BrushPreset.Blank))).put("engine", "UNKNOWN"))
        }
        assertTrue(runCatching { BrushPack.read(invalid.toString().byteInputStream()) }.isFailure)
        assertTrue(runCatching { BrushPack.read(JSONObject(original).put("version", 2).toString().byteInputStream()) }.isFailure)
        val duplicate = JSONObject(original).apply { getJSONArray("brushes").put(getJSONArray("brushes").getJSONObject(0)) }
        assertTrue(runCatching { BrushPack.read(duplicate.toString().byteInputStream()) }.isFailure)
        assertTrue(runCatching { BrushPack.encode("Empty", emptyList()) }.isFailure)
    }

    @Test fun categoryOrderMembershipAndRenamesPersist() {
        val store = BrushCategories(RuntimeEnvironment.getApplication())
        val first = BrushCategory(name = "Inks", brushIds = listOf("b", "a"))
        val second = BrushCategory(name = "Paint", brushIds = listOf("a"))
        store.save(listOf(second, first.copy(name = "Line tips")))
        assertEquals(listOf(second, first.copy(name = "Line tips")), store.load())
        store.save(listOf(first.copy(brushIds = listOf("a", "b"))))
        assertEquals(listOf("a", "b"), store.load().single().brushIds)
        store.save(emptyList())
        assertTrue(store.load().isEmpty())
    }
}
