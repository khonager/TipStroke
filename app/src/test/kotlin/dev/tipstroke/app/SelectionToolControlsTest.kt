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
        compose.onNodeWithText("Actions ▾").assertIsDisplayed().performClick()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Pencil").performClick()
        compose.onNodeWithText("Actions ▾").assertDoesNotExist()
        compose.onNodeWithContentDescription("Drawing tools").performClick()
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Actions ▾").assertIsDisplayed()
        compose.onNodeWithText("Selection active").assertDoesNotExist()
    }
}
