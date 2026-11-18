package dev.tipstroke.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.canvas.standardCanvasPresets
import dev.tipstroke.ui.ResolutionBrowser
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h800dp-mdpi")
class ResolutionBrowserTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @After fun close() { activity?.close() }

    @Test fun scrollSelectsPaperAndShortcutsSurviveWindowResize() {
        val presets = standardCanvasPresets()
        var selected by mutableStateOf(presets.first().id)
        var favorites by mutableStateOf(listOf(presets[0].id, presets[1].id, presets[2].id))
        var width by mutableStateOf(440)
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme {
                ResolutionBrowser(presets, selected, favorites, { selected = it.id }, { favorites = it }, Modifier.width(width.dp))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("3 / 4 shortcuts · Hidden pins return when there’s room.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Favorite 4K UHD, 3840 by 2160 pixels").performClick()
        compose.waitForIdle()
        assertEquals("uhd-4k", selected)
        compose.onNodeWithText("3840 × 2160 px").assertIsDisplayed()
        compose.runOnIdle { width = 240 }
        compose.onNodeWithText("2 / 2 shortcuts · Hidden pins return when there’s room.").assertIsDisplayed()
        assertEquals(3, favorites.size)
        compose.onNodeWithText("★ Remove shortcut").performClick()
        assertEquals(listOf("full-hd", "square-2048"), favorites)
        compose.runOnIdle { width = 440 }
        val beforeScroll = selected
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertNotEquals(beforeScroll, selected)
        val paper = presets.single { it.id == selected }
        compose.onNodeWithText("${paper.widthPx} × ${paper.heightPx} px").assertIsDisplayed()
    }
}
