package dev.tipstroke.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionToolControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @After fun close() { activity?.close() }
    @Test fun selectUsesToolRailAndBrushRestoresAdjustmentControls() {
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme { CanvasScreen(initialSelectionOpen = true) }
        }
        compose.onNodeWithContentDescription("Drawing tools").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select all").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Pencil").performClick()
        compose.onNodeWithContentDescription("Select all").assertDoesNotExist()
        compose.onNodeWithContentDescription("Drawing tools").performClick()
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithContentDescription("Select all").assertIsDisplayed()
        compose.onNodeWithText("Selection active").assertDoesNotExist()
    }

    @Test fun eraserAndSelectSwitchInBothDirectionsAndHoldRecallsSelection() {
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme { CanvasScreen(initialSelectionOpen = true) }
        }
        fun choose(label: String) {
            compose.onNodeWithContentDescription("Drawing tools").performClick()
            compose.onNodeWithText(label).performClick()
        }
        fun toolIs(label: String) {
            compose.onNodeWithContentDescription("Drawing tools").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, label))
        }
        compose.onNodeWithContentDescription("Select all").performClick()
        compose.onNodeWithContentDescription("Deselect").performClick().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Drawing tools").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Deselect").assertIsEnabled()
        repeat(2) {
            choose("Eraser"); toolIs("Eraser")
            compose.onNodeWithContentDescription("Select all").assertDoesNotExist()
            choose("Select"); toolIs("Select")
            compose.onNodeWithContentDescription("Select all").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Deselect").performClick()
        compose.onNodeWithContentDescription("Drawing tools").performClick()
        compose.onNodeWithContentDescription("Select").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Deselect").assertIsEnabled()
    }
    @Test fun transformPanelExposesMeshAndSharedActions() {
        var tool=dev.tipstroke.core.model.PhotoEditTool.MOVE
        var mesh=0
        var action=""
        var filtered=true
        var scale=1f to 1f
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity=it }.setup().get().setContent {
            MaterialTheme {
                SelectionTransformPanel(initialFiltered=true,onTool={ tool=it },onMesh={ mesh=it },
                    onFilter={ filtered=it },onRadius={},onReadScale={ scale },onScale={ x,y -> scale=x to y },
                    onRotation={},onAction={ action=it },onApply={},onCancel={})
            }
        }
        compose.onNodeWithText("Warp").performClick()
        compose.onNodeWithText("8 × 8").performClick()
        org.junit.Assert.assertEquals(dev.tipstroke.core.model.PhotoEditTool.WARP,tool)
        org.junit.Assert.assertEquals(8,mesh)
        compose.onNodeWithText("Fit to canvas").performScrollTo().performClick()
        org.junit.Assert.assertEquals("fit",action)
        compose.onNodeWithText("Reset").performScrollTo().performClick()
        org.junit.Assert.assertEquals("reset",action)
        compose.onNode(isToggleable()).performScrollTo().performClick()
        org.junit.Assert.assertFalse(filtered)
        compose.onNodeWithText("Uniform").performScrollTo().performClick()
        compose.onNodeWithText("Exact size").performScrollTo().performClick()
        compose.onNodeWithText("Width %").performScrollTo().performTextReplacement("150")
        org.junit.Assert.assertEquals(1.5f,scale.first,.001f)
        org.junit.Assert.assertEquals(1.5f,scale.second,.001f)
    }
}
