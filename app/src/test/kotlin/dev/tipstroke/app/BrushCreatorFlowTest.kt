package dev.tipstroke.app

import androidx.activity.ComponentActivity
import androidx.core.view.descendants
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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

    @Config(sdk = [35], qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun sourceEditorRemainsUsableInShortLandscapeWindows() {
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            TipStrokeTheme { BrushCreatorDialog(LibraryBrush(BrushPreset.Blank), false, null, {}, {}) }
        }
        compose.onNodeWithText("Shape").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            and hasAnyDescendant(hasContentDescription("Draw source")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100f) }
        compose.onNodeWithContentDescription("Draw source").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Use source").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear").assertIsDisplayed()
        compose.onNodeWithContentDescription("Paint width (px)").assertIsDisplayed()
        compose.runOnIdle {
            val root = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView as android.view.ViewGroup
            val pad = root.descendants.filterIsInstance<dev.tipstroke.drawing.android.BrushSourcePad>().single()
            assertTrue("Source pad needs room to draw", pad.width >= 100 && pad.height >= 100)
            val image = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(image))
            val output = java.io.File("build/qa/tip-source-landscape.png").also { it.parentFile?.mkdirs() }
            output.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test fun createSaveReopenAndCancelPreservesSavedPreset() {
        val library = BrushLibrary(RuntimeEnvironment.getApplication())
        library.save(emptyList())
        var selected: BrushPreset? = null
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            TipStrokeTheme { BrushLibraryDialog(BrushPreset.Pencil, { selected = it }, {}) }
        }
        try {
            compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Create tip") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            println(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 20))
            throw error
        }
        compose.onNodeWithContentDescription("Create tip").performClick()
        compose.onNodeWithText("Tip name").performTextReplacement("Soft graphite")
        compose.onNodeWithText("Creator name (optional)").performTextReplacement("Artist")
        compose.onNodeWithText("Grain").performClick()
        compose.onNodeWithText("Lines").performClick()
        compose.runOnIdle {
            val root = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
            val image = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(image))
            val output = java.io.File("build/qa/brush-studio-grain.png").also { it.parentFile?.mkdirs() }
            output.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        compose.onNodeWithContentDescription("Grain depth minimum · 0.00").assertIsDisplayed()
        compose.onNodeWithContentDescription("Grain depth maximum · 1.00").assertIsDisplayed()
        compose.onNodeWithContentDescription("Import image").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Shape").performClick()
        compose.onNodeWithContentDescription("Draw source").performClick()
        compose.runOnIdle {
            val root = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView as android.view.ViewGroup
            val pad = root.descendants.filterIsInstance<dev.tipstroke.drawing.android.BrushSourcePad>().single()
            val now = android.os.SystemClock.uptimeMillis()
            listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val fraction = .2f + index * .3f
                android.view.MotionEvent.obtain(now, now + index * 100L, action, pad.width * fraction, pad.height * fraction, 0).also {
                    pad.dispatchTouchEvent(it); it.recycle()
                }
            }
        }
        compose.onNodeWithContentDescription("Use source").performClick()
        compose.onNodeWithText("Image").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save and use").performClick()
        compose.waitUntil(10_000) { selected != null }
        compose.onNodeWithText("Soft graphite").assertIsDisplayed()
        assertEquals("Soft graphite", selected!!.displayName)
        assertEquals("Artist", library.load().single().author)
        assertEquals(BrushPreset.Blank.speedTaper, selected!!.speedTaper)
        assertNotNull(selected!!.stamp)
        assertEquals(dev.tipstroke.core.model.StampGrain.LINES, selected!!.stamp!!.grain)
        assertEquals(.6f, selected!!.stamp!!.grainDepth, 0f)
        assertEquals(dev.tipstroke.core.model.StampShape.IMAGE, selected!!.stamp!!.shape)
        val source = requireNotNull(selected!!.stamp!!.shapeTexture)
        assertTrue(source.bytes().any { it.toInt() != 0 })
        assertTrue(source.bytes().any { it.toInt() == 0 })
        assertEquals(source, library.load().single().preset.stamp!!.shapeTexture)
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.onNodeWithText("Tip name").performTextReplacement("Unsaved name")
        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.onNodeWithText("Soft graphite").assertIsDisplayed()
        assertEquals("Soft graphite", library.load().single().preset.displayName)
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithContentDescription("Cancel").performClick()
        assertEquals(1, library.load().size)
        compose.runOnIdle {
            val root = org.robolectric.shadows.ShadowDialog.getShownDialogs().last { it.isShowing }.window!!.decorView
            val image = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(image))
            val output = java.io.File("build/qa/tip-library.png").also { it.parentFile?.mkdirs() }
            output.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        compose.onNodeWithContentDescription("Select Soft graphite").performClick()
        assertEquals(library.load().single().preset.id, selected!!.id)
    }
}
