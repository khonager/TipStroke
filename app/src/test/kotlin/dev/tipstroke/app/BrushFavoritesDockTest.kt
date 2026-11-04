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
                BrushFavoritesDock(favorites.first(), favorites, true, { capacity = it }, {}, {}, {}, {}, {}, false, {}, {}, {}, "Adjust tip", true,
                    Modifier.width(width.dp))
            }
        }
        compose.waitForIdle()
        assertEquals(5, capacity)
        compose.onNodeWithContentDescription("Pinned 4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pinned 5").assertDoesNotExist()
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        compose.runOnIdle { width = 220 }
        compose.waitForIdle()
        assertEquals(1, capacity)
        compose.onNodeWithContentDescription("Pinned 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pinned 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tips").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drawing tools").performClick()
        compose.onNodeWithText("Eraser").assertIsDisplayed()
    }

    @Test fun stylusHoverWaitsCancelsOnExitAndStillAllowsSelection() {
        var selected = 0
        val tip = BrushPreset.Blank.copy(displayName = "Textured tip")
        val screen = Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get()
        screen.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    BrushFavoritesDock(tip, listOf(tip), false, {}, {}, { selected++ }, {}, {}, {}, false, {}, {}, {}, "Adjust tip", true,
                        Modifier.height(300.dp))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        fun hover(action: Int) {
            val point = compose.onNodeWithContentDescription("Textured tip").fetchSemanticsNode().boundsInWindow.center
            compose.runOnUiThread {
                val now = android.os.SystemClock.uptimeMillis()
                val properties = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_STYLUS }
                val coordinates = android.view.MotionEvent.PointerCoords().apply { x = point.x; y = point.y }
                android.view.MotionEvent.obtain(now, now, action, 1, arrayOf(properties), arrayOf(coordinates),
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0).also {
                    screen.window.decorView.dispatchGenericMotionEvent(it)
                    it.recycle()
                }
            }
            // AndroidComposeView defers in-bounds stylus exits by 8 ms. Advance the Android
            // looper as well as the Compose coroutine clock.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
            compose.mainClock.advanceTimeBy(32)
        }
        compose.onNodeWithText("Textured tip").assertDoesNotExist()
        hover(android.view.MotionEvent.ACTION_HOVER_ENTER)
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Textured tip").assertDoesNotExist()
        hover(android.view.MotionEvent.ACTION_HOVER_EXIT)
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Textured tip").assertDoesNotExist()
        hover(android.view.MotionEvent.ACTION_HOVER_ENTER)
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("Textured tip").assertIsDisplayed()
        hover(android.view.MotionEvent.ACTION_HOVER_EXIT)
        compose.onNodeWithText("Textured tip").assertDoesNotExist()
        compose.onNodeWithContentDescription("Textured tip").performClick()
        assertEquals(1, selected)
        compose.onNodeWithContentDescription("Textured tip").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Textured tip").assertIsDisplayed()
        assertEquals(1, selected)
        compose.mainClock.advanceTimeBy(2100)
        compose.onNodeWithText("Textured tip").assertDoesNotExist()
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
