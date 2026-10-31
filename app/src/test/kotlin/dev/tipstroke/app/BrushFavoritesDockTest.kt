package dev.tipstroke.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.model.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h600dp-land-mdpi")
class BrushFavoritesDockTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @After fun close() { activity?.close() }

    @Test fun dockFitsMeasuredWidthAndShrinksWithoutScrolling() {
        var width by mutableStateOf(420)
        var capacity = -1
        val favorites = List(20) { BrushPreset.Blank.copy(id = BrushId("pin-$it"), displayName = "Pinned $it") }
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme {
                BrushFavoritesDock(favorites.first(), favorites, true, { capacity = it }, {}, {}, {}, {}, {}, false, {}, {}, {}, "Adjust brush", true,
                    Modifier.width(width.dp))
            }
        }
        compose.waitForIdle()
        assertEquals(5, capacity)
        compose.onNodeWithText("Pinned 4").assertIsDisplayed()
        compose.onNodeWithText("Pinned 5").assertDoesNotExist()
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        compose.runOnIdle { width = 220 }
        compose.waitForIdle()
        assertEquals(1, capacity)
        compose.onNodeWithText("Pinned 0").assertIsDisplayed()
        compose.onNodeWithText("Pinned 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Brushes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drawing tools").performClick()
        compose.onNodeWithText("Eraser").assertIsDisplayed()
    }

    @Test fun pinsRemainOrderedAndPersistWithoutDuplicating() {
        val preferences = BrushShelfPreferences(RuntimeEnvironment.getApplication())
        preferences.save(listOf("custom-b", "ink-v1", "custom-b", "pencil-v1"))
        assertEquals(listOf("custom-b", "ink-v1", "pencil-v1"), preferences.load())
        for (available in 120..1200) {
            val slots = brushFavoriteCapacity(available.toFloat())
            if (slots > 0) assertTrue((slots + 3) * 48 + (slots + 2) * 2 + 8 <= available)
        }
    }
}
