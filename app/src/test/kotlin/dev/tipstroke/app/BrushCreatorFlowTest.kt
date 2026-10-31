package dev.tipstroke.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.tipstroke.core.model.BrushPreset
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrushCreatorFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @After fun close() { activity?.close() }

    @Test fun createSaveReopenAndCancelPreservesSavedPreset() {
        val library = BrushLibrary(RuntimeEnvironment.getApplication())
        library.save(emptyList())
        var selected: BrushPreset? = null
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            TipStrokeTheme { BrushLibraryDialog(BrushPreset.Pencil, { selected = it }, {}) }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Create brush") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Create brush").performClick()
        compose.onNodeWithText("Brush name").performTextReplacement("Soft graphite")
        compose.onNodeWithText("Creator name (optional)").performTextReplacement("Artist")
        compose.onNodeWithText("Save and use").performClick()
        compose.waitUntil(10_000) { selected != null }
        compose.onNodeWithText("Soft graphite").assertIsDisplayed()
        assertEquals("Soft graphite", selected!!.displayName)
        assertEquals("Artist", library.load().single().author)
        assertEquals(BrushPreset.Pencil.speedTaper, selected!!.speedTaper)
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Brush name").performTextReplacement("Unsaved name")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Soft graphite").assertIsDisplayed()
        assertEquals("Soft graphite", library.load().single().preset.displayName)
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, library.load().size)
    }
}
