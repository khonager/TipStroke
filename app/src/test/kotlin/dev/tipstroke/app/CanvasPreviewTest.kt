package dev.tipstroke.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.junit.After
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-port-mdpi")
class CanvasPreviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @After fun close() { activity?.close() }
    private val preview get() = compose.onNodeWithContentDescription("Canvas preview")
    private val drop get() = compose.onNodeWithContentDescription("Tucked canvas preview")

    private fun open() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        requireNotNull(activity).get().setContent { CanvasScreen() }
        drop.assertDoesNotExist()
        preview.assertDoesNotExist()
        compose.onNodeWithContentDescription("More canvas actions").performClick()
        compose.onNodeWithText("Canvas preview").performClick()
        preview.assertExists()
    }

    @Test fun tuckPullAndRemove() {
        open()
        val original = preview.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot()
        root.performTouchInput { swipe(original.center, Offset(2f, original.center.y)) }
        val tucked = drop.fetchSemanticsNode().boundsInRoot
        assertTrue(tucked.width < original.width / 2)
        root.performTouchInput { swipe(tucked.center, original.center + Offset(65f, 50f)) }
        val restored = preview.fetchSemanticsNode().boundsInRoot
        assertEquals(original.width, restored.width, 1f)
        root.performTouchInput {
            down(restored.center)
            moveTo(restored.center + Offset(0f, 40f))
        }
        val trash = compose.onNodeWithContentDescription("Remove preview").fetchSemanticsNode().boundsInRoot
        root.performTouchInput { moveTo(trash.center); up() }
        preview.assertDoesNotExist()
        drop.assertDoesNotExist()
    }

    @Test fun cornersResizeAroundOppositeCorner() {
        open()
        for ((x, y) in listOf(1 to 1, 0 to 1, 1 to 0, 0 to 0)) {
            val before = preview.fetchSemanticsNode().boundsInRoot
            val corner = Offset(if (x == 0) before.left + 3 else before.right - 3,
                if (y == 0) before.top + 3 else before.bottom - 3)
            // Shrink at left/top and grow at right/bottom to stay inside the available area.
            val sign = if (x == 0) -1 else 1
            val delta = Offset((2 * x - 1) * sign * 20f, (2 * y - 1) * sign * 20f)
            compose.onRoot().performTouchInput { swipe(corner, corner + delta) }
            val after = preview.fetchSemanticsNode().boundsInRoot
            assertEquals(before.width + sign * 20, after.width, 2f)
            assertEquals(if (x == 0) before.right else before.left, if (x == 0) after.right else after.left, 1f)
            assertEquals(if (y == 0) before.bottom else before.top, if (y == 0) after.bottom else after.top, 1f)
        }
    }
}
