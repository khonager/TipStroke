package dev.tipstroke.app

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.tipstroke.drawing.android.DrawingSurface
import androidx.core.view.descendants
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

    @Test fun brushAidsCanBeToggledAndToolChoicesRemainReachable() {
        var assists by mutableStateOf(dev.tipstroke.core.drawing.BrushAssists())
        var copies by mutableStateOf(1)
        var push = false
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme {
                BrushFavoritesDock(BrushPreset.Ink, emptyList(), true, {}, {}, {}, {}, {}, {}, {}, {}, "Adjust tip", true,
                    Modifier.width(400.dp), assists = assists, onAssists = { assists = it },
                    mirrorCopies = copies, onMirrorCopies = { copies = it }, onColorPush = { push = true })
            }
        }
        compose.onNodeWithContentDescription("Drawing tools").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithContentDescription("Brush aids").performClick()
        compose.onNodeWithText("Paint inside").performClick()
        compose.onNodeWithText("Elastic line").performClick()
        compose.onNodeWithText("Mirror wheel: 6 copies").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(assists.paintInside); assertTrue(assists.elasticLine); assertEquals(6, copies) }
        compose.onNodeWithText("Back to drawing tools").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Ribbon").assertDoesNotExist()
        compose.onNodeWithContentDescription("Color push").performClick()
        compose.runOnIdle { assertTrue(push) }
        compose.onNodeWithContentDescription("Drawing tools").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithContentDescription("Brush aids").performClick()
        compose.onNodeWithText("Paint inside").performClick()
        compose.onNodeWithText("Elastic line").performClick()
        compose.onNodeWithText("Mirror wheel: Off").performClick()
        compose.runOnIdle { assertFalse(assists.paintInside); assertFalse(assists.elasticLine); assertEquals(1, copies) }
    }

    @Test fun holdSlidesToSmudgeAndOnlySelectsOnRelease() {
        var erases = 0
        var smudges = 0
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            Box(Modifier.fillMaxSize()) {
                BrushFavoritesDock(BrushPreset.Ink, emptyList(), false, {}, {}, {}, { erases++ }, { smudges++ },
                    {}, {}, {}, "Adjust tip", true, Modifier.height(300.dp))
            }
        }
        val dock = compose.onNodeWithContentDescription("Drawing tools")
        dock.performTouchInput { click() }
        assertEquals(1, erases)
        dock.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(750)
        compose.onNodeWithContentDescription("Smudge").assertIsDisplayed()
        // In this left dock the grid opens immediately to the right, clamped to the window top.
        val target = compose.onNodeWithContentDescription("Smudge").fetchSemanticsNode().layoutInfo.coordinates.localToScreen(androidx.compose.ui.geometry.Offset(24f, 24f))
        val local = dock.fetchSemanticsNode().layoutInfo.coordinates.screenToLocal(target)
        dock.performTouchInput { moveTo(local) }
        compose.waitForIdle()
        compose.onNodeWithText("Smudge").assertIsDisplayed()
        assertEquals(0, smudges)
        dock.performTouchInput { up() }
        assertEquals(1, smudges)
        compose.onNodeWithContentDescription("Smudge").assertDoesNotExist()
        dock.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(750)
        dock.performTouchInput { up() }
        assertEquals(1, erases)
        assertEquals(1, smudges)
    }

    @Test fun oneSlotShowsItsPreviewAndOpensItsPicker() {
        val tips = MangaTips.presets.take(5)
        var opened: Int? = null
        var pixelTool: dev.tipstroke.core.drawing.PixelTool? = null
        val showPixelTips = mutableStateOf(false)
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            BrushFavoritesDock(tips.first(), tips, true, { opened = it }, {}, {}, {}, {}, { pixelTool = it }, {}, {},
                "Adjust tip", true, Modifier.width(150.dp), showPixelTips = showPixelTips.value)
        }
        compose.onNodeWithContentDescription(tips.first().displayName).performClick()
        compose.runOnIdle { assertEquals(0, opened) }
        compose.onNodeWithContentDescription(tips.last().displayName).assertDoesNotExist()
        compose.onNodeWithContentDescription("Pixel tips").assertDoesNotExist()
        compose.runOnIdle { showPixelTips.value = true }
        compose.onNodeWithContentDescription("Pixel tips").performClick()
        compose.onNodeWithContentDescription("Pixel pencil").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dither").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pixel line").performClick()
        compose.runOnIdle { assertEquals(dev.tipstroke.core.drawing.PixelTool.LINE, pixelTool); showPixelTips.value = false }
        compose.onNodeWithContentDescription("Pixel tips").assertDoesNotExist()
    }

    @Test fun pixelTipCanBeChosenAtFitZoomAndStaysSelectedInPicker() {
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            TipStrokeTheme { CanvasScreen() }
        }
        compose.onNodeWithContentDescription("Pixel tips").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ink").performClick()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithText("Search tips").performTextInput("Pixel line")
        compose.onNodeWithTag("Choose Pixel line").performClick()
        compose.onNodeWithContentDescription("Pixel tips").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pixel line").performClick()
        compose.onNodeWithTag("Choose Pixel line").assertIsSelected()
        compose.onNodeWithText("Close").performClick()
        val surface = compose.runOnIdle {
            activity!!.get().findViewById<android.view.ViewGroup>(android.R.id.content)
                .descendants.filterIsInstance<DrawingSurface>().single()
        }
        compose.runOnIdle {
            val coords = MotionEvent.PointerCoords().apply {
                x = surface.width / 2f
                y = surface.height / 2f
                setAxisValue(MotionEvent.AXIS_VSCROLL, 50f)
            }
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val wheel = MotionEvent.obtain(100, 100, MotionEvent.ACTION_SCROLL, 1, arrayOf(pointer), arrayOf(coords),
                KeyEvent.META_CTRL_ON, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            try { surface.dispatchGenericMotionEvent(wheel) } finally { wheel.recycle() }
        }
        compose.onNodeWithContentDescription("Pixel tips").assertIsDisplayed()
        compose.runOnIdle { surface.resetView() }
        compose.onNodeWithContentDescription("Pixel tips").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pixel line").performClick()
        compose.onNodeWithTag("Choose Pixel line").assertIsSelected()
    }

    @Test fun pickerRemembersGroupAfterPickingAndLeavesDockInOrder() {
        val favorite = MangaTips.presets.first()
        BrushShelfPreferences(RuntimeEnvironment.getApplication()).save(listOf(favorite.id.value))
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme { CanvasScreen() }
        }
        compose.onNodeWithContentDescription(favorite.displayName).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithText("Manga").performClick()
        compose.onNodeWithText(favorite.displayName).performClick()
        compose.onNodeWithContentDescription(favorite.displayName).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithText("Edit group").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription(favorite.displayName).assertIsSelected()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h900dp-port-mdpi")
    fun phoneCentersBothRowsOfControls() {
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            TipStrokeTheme { CanvasScreen(onBackToGallery = {}) }
        }
        val center = compose.onRoot().fetchSemanticsNode().boundsInRoot.center.x
        fun assertCentered(first: String, last: String) {
            val left = compose.onNodeWithContentDescription(first).fetchSemanticsNode().boundsInRoot.left
            val right = compose.onNodeWithContentDescription(last).fetchSemanticsNode().boundsInRoot.right
            assertEquals(center, (left + right) / 2, 2f)
        }
        assertCentered("Back to gallery", "More canvas actions")
        assertCentered("Ink", "Show size and opacity")
    }

    @Test fun dockFitsMeasuredWidthAndShrinksWithoutScrolling() {
        var width by mutableStateOf(420)
        val favorites = List(20) { BrushPreset.Blank.copy(id = BrushId("pin-$it"), displayName = "Pinned $it") }
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme {
                BrushFavoritesDock(favorites.first(), favorites, true, {}, {}, {}, {}, {}, {}, {}, {}, "Adjust tip", true,
                    Modifier.width(width.dp))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pinned 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pinned 4").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        compose.runOnIdle { width = 320 }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("More pinned tips").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pinned 3").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pinned 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drawing tools").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithContentDescription("Eraser").assertIsDisplayed()
    }

    @Test fun stylusHoverWaitsCancelsOnExitAndStillAllowsSelection() {
        var opened = 0
        var selected = 0
        val tip = BrushPreset.Blank.copy(displayName = "Textured tip")
        val screen = Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get()
        screen.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    BrushFavoritesDock(BrushPreset.Ink, listOf(tip), false, { opened++ }, { selected++ }, {}, {}, {}, {}, {}, {}, "Adjust tip", true,
                        Modifier.height(350.dp))
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
        assertEquals(1, opened)
        assertEquals(1, selected)
    }

    @Test fun pinsAndFavoritesMigrateOnceThenChangeIndependently() {
        val preferences = BrushShelfPreferences(RuntimeEnvironment.getApplication())
        preferences.save(listOf("a", "b"))
        val available = setOf("a", "b", "c")
        assertEquals(listOf("a", "b"), preferences.loadDock().ordered(available))
        preferences.saveDock(preferences.loadDock().replace(0, "c", available))
        preferences.save(listOf("a", "c"))
        assertEquals(listOf("c", "b"), preferences.loadDock().ordered(available))
        assertEquals(listOf("a", "c"), preferences.load())
    }

    @Test fun pinsRemainOrderedAndPersistWithoutDuplicating() {
        val preferences = BrushShelfPreferences(RuntimeEnvironment.getApplication())
        preferences.save(listOf("custom-b", "ink-v1", "custom-b", "pencil-v1"))
        assertEquals(listOf("custom-b", "ink-v1", "pencil-v1"), preferences.load())
        for (available in 120..1200) {
            val slots = brushFavoriteCapacity(available.toFloat())
            if (slots > 0) assertTrue((slots + 4) * 48 + (slots + 3) * 2 + 8 <= available)
        }
    }
}
