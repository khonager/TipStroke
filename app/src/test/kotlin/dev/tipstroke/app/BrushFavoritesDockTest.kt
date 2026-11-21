package dev.tipstroke.app

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
        var ribbon = false
        var push = false
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme {
                BrushFavoritesDock(BrushPreset.Ink, emptyList(), true, {}, {}, {}, {}, {}, false, {}, {}, {}, "Adjust tip", true,
                    Modifier.width(400.dp), assists = assists, onAssists = { assists = it },
                    mirrorCopies = copies, onMirrorCopies = { copies = it }, onRibbon = { ribbon = true }, onColorPush = { push = true })
            }
        }
        compose.onNodeWithContentDescription("Drawing tools").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithContentDescription("Brush aids").performClick()
        compose.onNodeWithText("Paint inside").performClick()
        compose.onNodeWithText("Elastic line").performClick()
        compose.onNodeWithText("Mirror wheel: 6 copies").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(assists.paintInside); assertTrue(assists.elasticLine); assertEquals(6, copies) }
        compose.onNodeWithText("Back to drawing tools").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Ribbon").performClick()
        compose.runOnIdle { assertTrue(ribbon) }
        compose.onNodeWithContentDescription("Drawing tools").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
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
                    false, {}, {}, {}, "Adjust tip", true, Modifier.height(300.dp))
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

    @Test fun tipGroupsStayReachableWithoutFavoriteSlots() {
        val tips = dev.tipstroke.core.model.MangaTips.presets
        var chosen: BrushPreset? = null
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            BrushFavoritesDock(tips.first(), emptyList(), true, {}, { chosen = it }, {}, {}, {}, false, {}, {}, {},
                "Adjust tip", true, Modifier.width(150.dp), groups = listOf(DockTipGroup("manga", "Manga", tips)))
        }
        compose.onNodeWithContentDescription("Tip groups").performClick()
        compose.onNodeWithContentDescription("Manga").performClick()
        compose.onNodeWithContentDescription("Back to tip groups").performClick()
        compose.onNodeWithContentDescription("Manga").performClick()
        compose.onNodeWithContentDescription(tips.last().displayName).performClick()
        compose.runOnIdle { assertEquals(tips.last(), chosen) }
    }

    @Test fun aSingleFavoriteInMangaHasOneShortcutAndGroupsCanBeHidden() {
        val favorite = MangaTips.presets.first()
        val context = RuntimeEnvironment.getApplication()
        BrushShelfPreferences(context).save(listOf(favorite.id.value))
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme { CanvasScreen() }
        }
        compose.onAllNodesWithContentDescription(favorite.displayName).assertCountEquals(1)
        compose.onNodeWithContentDescription(favorite.displayName).performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Tip groups").assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("Favorites").assertDoesNotExist()
        compose.onNodeWithContentDescription("Manga").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manage tip groups").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Show Manga in tip bar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Show Manga in tip bar").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Back to canvas").performClick()
        compose.onAllNodesWithContentDescription(favorite.displayName).assertCountEquals(1)
        compose.onNodeWithContentDescription("Tip groups").performClick()
        compose.onNodeWithContentDescription("Manga").assertDoesNotExist()
        assertFalse(BrushCategories(context).load().first { it.id == "builtin-manga" }.showInDock)
    }

    @Test fun dockFitsMeasuredWidthAndShrinksWithoutScrolling() {
        var width by mutableStateOf(420)
        val favorites = List(20) { BrushPreset.Blank.copy(id = BrushId("pin-$it"), displayName = "Pinned $it") }
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme {
                BrushFavoritesDock(favorites.first(), favorites, true, {}, {}, {}, {}, {}, false, {}, {}, {}, "Adjust tip", true,
                    Modifier.width(width.dp))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pinned 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pinned 3").assertDoesNotExist()
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        compose.runOnIdle { width = 320 }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pinned 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pinned 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tips").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drawing tools").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithContentDescription("Eraser").assertIsDisplayed()
    }

    @Test fun stylusHoverWaitsCancelsOnExitAndStillAllowsSelection() {
        var selected = 0
        val tip = BrushPreset.Blank.copy(displayName = "Textured tip")
        val screen = Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get()
        screen.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    BrushFavoritesDock(tip, listOf(tip), false, {}, { selected++ }, {}, {}, {}, false, {}, {}, {}, "Adjust tip", true,
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
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Textured tip").assertIsDisplayed()
        assertEquals(1, selected)
        compose.mainClock.advanceTimeBy(2100)
        compose.onNodeWithText("Textured tip").assertDoesNotExist()
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun selectedColorRepaintsSelectedAndUnselectedTipIcons() {
        var color by mutableStateOf(RgbaColor(1f, 0f, 0f))
        val tips = listOf(BrushPreset.Blank.copy(id = BrushId("first")), BrushPreset.Blank.copy(id = BrushId("second")))
        val screen = Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get()
        screen.setContent {
            TipStrokeTheme {
                CompositionLocalProvider(LocalTipPreviewColor provides color) {
                    BrushFavoritesDock(tips.first(), tips, true, {}, {}, {}, {}, {}, false, {}, {}, {}, "Adjust tip", true,
                        Modifier.width(400.dp))
                }
            }
        }
        fun assertColor(red: Boolean) {
            compose.waitForIdle()
            compose.runOnIdle {
                val views = (screen.window.decorView as android.view.ViewGroup).descendants
                    .filterIsInstance<dev.tipstroke.drawing.android.BrushStrokePreviewView>().toList()
                assertEquals(2, views.size)
                views.forEach { view ->
                    val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(bitmap))
                    try {
                        assertTrue((0 until bitmap.width).any { x -> (0 until bitmap.height).any { y ->
                            val pixel = bitmap.getPixel(x, y)
                            android.graphics.Color.alpha(pixel) > 32 && if (red)
                                android.graphics.Color.red(pixel) > android.graphics.Color.green(pixel) + 30
                            else android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel) + 30
                        } })
                    } finally { bitmap.recycle() }
                }
            }
        }
        assertColor(true)
        compose.runOnIdle { color = RgbaColor(0f, 1f, 0f) }
        assertColor(false)
    }

    @Test fun pinsRemainOrderedAndPersistWithoutDuplicating() {
        val preferences = BrushShelfPreferences(RuntimeEnvironment.getApplication())
        preferences.save(listOf("custom-b", "ink-v1", "custom-b", "pencil-v1"))
        assertEquals(listOf("custom-b", "ink-v1", "pencil-v1"), preferences.load())
        for (available in 120..1200) {
            val slots = brushFavoriteCapacity(available.toFloat())
            if (slots > 0) assertTrue((slots + 5) * 48 + (slots + 4) * 2 + 8 <= available)
        }
    }
}
