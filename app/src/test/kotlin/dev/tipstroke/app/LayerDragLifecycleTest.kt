package dev.tipstroke.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerDragLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun releaseAndCancelFloatingRowWithoutInvalidPlacement() {
        val layers = (0..7).map { LayerSummary(LayerId("layer-$it"), "Layer $it", LayerKind.RASTER, true, 1f) }
        var reorderCount = 0
        compose.setContent {
            MaterialTheme {
                LayersPanel(layers, emptyMap(), layers.first().id, setOf(layers.first().id),
                    onBackgroundOpacityFinished = {}, imageTransforming = false,
                    onSelect = {}, onToggleSelection = {}, onToggleVisibility = {}, onOpacity = {},
                    onOpacityFinished = {}, onRename = {}, onImageScale = {}, onFitImage = {},
                    onOriginalImageSize = {}, onImageTransforming = {}, onAddPaint = {}, onImportImage = {},
                    onMoveForward = {}, onMoveBackward = {}, onDuplicate = {}, onDelete = {},
                    onReorder = { _, _, _ -> reorderCount++ })
            }
        }
        val panel = compose.onNodeWithContentDescription("Layers panel")
        val origin = panel.fetchSemanticsNode().boundsInRoot.topLeft
        val start = compose.onNodeWithText("Layer 0").fetchSemanticsNode().boundsInRoot.center - origin
        val end = compose.onNodeWithText("Layer 2").fetchSemanticsNode().boundsInRoot.center - origin
        panel.performTouchInput { down(start) }
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        panel.performTouchInput { advanceEventTime(800); moveTo(start); moveTo(end) }
        compose.onAllNodesWithText("Layer 0").assertCountEquals(2)
        compose.waitForIdle() // Ensure the floating row has been composed before release.
        panel.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, reorderCount)
        panel.performTouchInput { down(start) }
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        panel.performTouchInput { advanceEventTime(800); moveTo(start); moveTo(end) }
        compose.waitForIdle()
        panel.performTouchInput { cancel() }
        compose.waitForIdle()
        compose.onNodeWithText("Layer 0").assertExists()
        assertEquals(1, reorderCount)
    }

    @Test fun clearedOrInvalidCoordinatesNeverReachPixelRounding() {
        assertEquals(IntOffset.Zero, layerDragOffset(Offset.Unspecified, Offset.Zero, Offset.Zero))
        assertEquals(IntOffset.Zero, layerDragOffset(Offset(Float.POSITIVE_INFINITY, 0f), Offset.Zero, Offset.Zero))
        assertEquals(IntOffset(24, 38), layerDragOffset(Offset(34f, 58f), Offset(5f, 10f), Offset(5f, 10f)))
    }
}
