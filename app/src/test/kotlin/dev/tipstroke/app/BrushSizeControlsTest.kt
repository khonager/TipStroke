package dev.tipstroke.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.tipstroke.core.drawing.BrushSizeUnit
import dev.tipstroke.core.model.RgbaColor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h600dp-land-mdpi")
class BrushSizeControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @After fun close() { activity?.close() }

    @Test fun sizeValueOffersScreenUnitsAndPixelToolsKeepTheirLabel() {
        var unit by mutableStateOf(BrushSizeUnit.CANVAS_PIXELS)
        var pixelTool by mutableStateOf(false)
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme {
                TipControls(32f, 1f, RgbaColor(0f, 0f, 0f), emptyList(), {}, {}, {}, {},
                    horizontal = true, showColorSwitcher = false,
                    sizeUnit = if (pixelTool) BrushSizeUnit.CANVAS_PIXELS else unit,
                    onSizeUnit = if (pixelTool) null else { selected -> unit = selected })
            }
        }
        compose.onNodeWithContentDescription("Brush size units: Canvas pixels").performClick()
        compose.onNodeWithText("Screen size (dp)").performClick()
        assertEquals(BrushSizeUnit.SCREEN_DP, unit)
        compose.onNodeWithText("32 dp ▾").assertIsDisplayed()
        compose.runOnIdle { pixelTool = true }
        compose.onNodeWithText("32 px").assertIsDisplayed()
        compose.onNodeWithContentDescription("Brush size units: Screen size").assertDoesNotExist()
    }
}
