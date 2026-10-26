package dev.tipstroke.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.junit.After
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
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @After fun closeActivity() { activity?.close() }

    @Test fun releaseAndCancelFloatingRowWithoutInvalidPlacement() {
        val layers = (0..7).map { LayerSummary(LayerId("layer-$it"), "Layer $it", LayerKind.RASTER, true, 1f) }
        var reorderCount = 0
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
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

    @Test fun releaseKeepsInterveningRowsInTheirPreviewedPositions() {
        val initial = (0..4).map { LayerSummary(LayerId("layer-$it"), "Layer $it", LayerKind.RASTER, true, 1f) }
        var layers by mutableStateOf(initial)
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme {
                LayersPanel(layers, emptyMap(), layers.first().id, setOf(layers.first().id),
                    onBackgroundOpacityFinished = {}, imageTransforming = false,
                    onSelect = {}, onToggleSelection = {}, onToggleVisibility = {}, onOpacity = {},
                    onOpacityFinished = {}, onRename = {}, onImageScale = {}, onFitImage = {},
                    onOriginalImageSize = {}, onImageTransforming = {}, onAddPaint = {}, onImportImage = {},
                    onMoveForward = {}, onMoveBackward = {}, onDuplicate = {}, onDelete = {},
                    onReorder = { source, target, towardFront ->
                        val reordered = layers.toMutableList()
                        val moving = reordered.first { it.id == source }
                        reordered.remove(moving)
                        val targetIndex = reordered.indexOfFirst { it.id == target }
                        reordered.add(targetIndex + if (towardFront) 0 else 1, moving)
                        layers = reordered
                    })
            }
        }
        val panel = compose.onNodeWithContentDescription("Layers panel")
        val origin = panel.fetchSemanticsNode().boundsInRoot.topLeft
        val start = compose.onNodeWithText("Layer 0").fetchSemanticsNode().boundsInRoot.center - origin
        val target = compose.onNodeWithText("Layer 2").fetchSemanticsNode().boundsInRoot
        val end = target.center - origin + Offset(0f, target.height / 4f)
        panel.performTouchInput { down(start) }
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        panel.performTouchInput { advanceEventTime(800); moveTo(start); moveTo(end) }
        compose.waitForIdle()
        val previewTop = compose.onNodeWithText("Layer 1").fetchSemanticsNode().boundsInRoot.top
        panel.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf("layer-1", "layer-2", "layer-0", "layer-3", "layer-4"), layers.map { it.id.value })
        val settledTop = compose.onNodeWithText("Layer 1").fetchSemanticsNode().boundsInRoot.top
        assertEquals(previewTop, settledTop, 1f)
    }

    @Test fun upwardDropAtRowsCenterUsesThatRowsSlot() {
        val initial = (0..4).map { LayerSummary(LayerId("layer-$it"), "Layer $it", LayerKind.RASTER, true, 1f) }
        var layers by mutableStateOf(initial)
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme {
                LayersPanel(layers, emptyMap(), layers.last().id, setOf(layers.last().id),
                    onBackgroundOpacityFinished = {}, imageTransforming = false,
                    onSelect = {}, onToggleSelection = {}, onToggleVisibility = {}, onOpacity = {},
                    onOpacityFinished = {}, onRename = {}, onImageScale = {}, onFitImage = {},
                    onOriginalImageSize = {}, onImageTransforming = {}, onAddPaint = {}, onImportImage = {},
                    onMoveForward = {}, onMoveBackward = {}, onDuplicate = {}, onDelete = {},
                    onReorder = { source, target, towardFront ->
                        val reordered = layers.toMutableList()
                        val moving = reordered.first { it.id == source }
                        reordered.remove(moving)
                        val targetIndex = reordered.indexOfFirst { it.id == target }
                        reordered.add(targetIndex + if (towardFront) 0 else 1, moving)
                        layers = reordered
                    })
            }
        }
        val panel = compose.onNodeWithContentDescription("Layers panel")
        val origin = panel.fetchSemanticsNode().boundsInRoot.topLeft
        val start = compose.onNodeWithText("Layer 4").fetchSemanticsNode().boundsInRoot.center - origin
        val end = compose.onNodeWithText("Layer 2").fetchSemanticsNode().boundsInRoot.center - origin
        panel.performTouchInput { down(start) }
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        panel.performTouchInput { advanceEventTime(800); moveTo(start); moveTo(end) }
        compose.waitForIdle()
        panel.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf("layer-0", "layer-1", "layer-4", "layer-2", "layer-3"), layers.map { it.id.value })
    }

    @Test fun backgroundUsesAllThreeColorEditorsAndAppliesHexValue() {
        val layer = LayerSummary(LayerId("paint"), "Paint", LayerKind.RASTER, true, 1f)
        var background by mutableIntStateOf(android.graphics.Color.WHITE)
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme {
                LayersPanel(listOf(layer), emptyMap(), layer.id, setOf(layer.id),
                    backgroundColor = background, onBackgroundColor = { background = it },
                    onBackgroundOpacityFinished = {}, imageTransforming = false,
                    onSelect = {}, onToggleSelection = {}, onToggleVisibility = {}, onOpacity = {},
                    onOpacityFinished = {}, onRename = {}, onImageScale = {}, onFitImage = {},
                    onOriginalImageSize = {}, onImageTransforming = {}, onAddPaint = {}, onImportImage = {},
                    onMoveForward = {}, onMoveBackward = {}, onDuplicate = {}, onDelete = {})
            }
        }
        compose.onNodeWithText("Canvas background").performClick()
        compose.onNodeWithContentDescription("Wheel color picker").assertExists()
        compose.onNodeWithContentDescription("Sliders color picker").performClick()
        compose.onNodeWithContentDescription("Values color picker").performClick()
        compose.onNodeWithContentDescription("Hex color value").performTextReplacement("#336699")
        compose.waitForIdle()
        assertEquals(0xFF336699.toInt(), background)
        compose.onNodeWithText("Background opacity").assertExists()
    }
}
