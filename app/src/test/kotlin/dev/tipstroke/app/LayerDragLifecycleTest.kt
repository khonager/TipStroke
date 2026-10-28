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

    private var currentLayers by mutableStateOf(emptyList<LayerSummary>())
    private var reorderCount = 0
    private var groupCount = 0
    private lateinit var surface: dev.tipstroke.drawing.android.DrawingSurface

    private fun showNativeLayers(count: Int = 5): List<LayerSummary> {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get()
        surface = dev.tipstroke.drawing.android.DrawingSurface(host).apply {
            configureBlank(32, 32)
            layersListener = { layers, _, _, _, _, _ -> currentLayers = layers }
            repeat(count - 1) { addPaintLayer() }
            publishLayers()
        }
        host.setContent {
            MaterialTheme {
                LayersPanel(currentLayers, emptyMap(), currentLayers.first().id, setOf(currentLayers.first().id),
                    onBackgroundOpacityFinished = {}, imageTransforming = false,
                    onSelect = {}, onToggleSelection = {}, onToggleVisibility = {}, onOpacity = {},
                    onOpacityFinished = {}, onRename = {}, onImageScale = {}, onFitImage = {},
                    onOriginalImageSize = {}, onImageTransforming = {}, onAddPaint = {}, onImportImage = {},
                    onMoveForward = {}, onMoveBackward = {}, onDuplicate = {}, onDelete = {},
                    // Identical direction conversion to CanvasScreen, through the real native stack.
                    onReorder = { source, target, towardFront ->
                        reorderCount++
                        surface.reorderLayer(source, target, towardFront)
                    },
                    onGroup = { source, target -> groupCount++; surface.groupLayer(source, target) })
            }
        }
        compose.waitForIdle()
        return currentLayers.toList()
    }

    private fun row(layer: LayerSummary) = compose.onNodeWithContentDescription("Layer row: ${layer.name}")
    private fun preview(layer: LayerSummary) = compose.onNodeWithContentDescription("Preview of ${layer.name}", useUnmergedTree = true)
    private fun panel() = compose.onNodeWithContentDescription("Layers panel")

    private fun hold(layer: LayerSummary, fraction: Float = .5f) {
        val bounds = row(layer).fetchSemanticsNode().boundsInRoot
        val start = Offset(bounds.right - 100f, bounds.top + bounds.height * fraction) - panel().fetchSemanticsNode().boundsInRoot.topLeft
        panel().performTouchInput { down(start) }
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        panel().performTouchInput { advanceEventTime(800); moveTo(start) }
        compose.waitForIdle()
    }

    private fun move(pointInRoot: Offset) {
        val point = pointInRoot - panel().fetchSemanticsNode().boundsInRoot.topLeft
        panel().performTouchInput { moveTo(point) }
        compose.waitForIdle()
    }

    private fun gapAbove(layer: LayerSummary): Offset {
        val bounds = row(layer).fetchSemanticsNode().boundsInRoot
        return Offset(bounds.right - 100f, bounds.top - 3f)
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = activity!!.get().window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file = java.io.File("build/screenshots/$name.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun editorScreenReleaseUsesNativePainterOrder() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get()
        host.setContent { MaterialTheme { CanvasScreen(canvasWidthPx = 32, canvasHeightPx = 32, initialLayersOpen = true) } }
        compose.waitForIdle()
        fun findSurface(view: android.view.View): dev.tipstroke.drawing.android.DrawingSurface? {
            if (view is dev.tipstroke.drawing.android.DrawingSurface) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                findSurface(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        compose.runOnIdle {
            surface = requireNotNull(findSurface(host.window.decorView))
            val editorListener = surface.layersListener
            surface.layersListener = { layers, selected, selectedIds, previews, background, opacity ->
                currentLayers = layers
                editorListener?.invoke(layers, selected, selectedIds, previews, background, opacity)
            }
            repeat(4) { surface.addPaintLayer() }
        }
        compose.waitForIdle()
        val initial = currentLayers.toList()
        hold(initial[0])
        move(gapAbove(initial[3]))
        compose.onNodeWithText("Move below ${initial[2].name}").assertExists()
        panel().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(initial[1], initial[2], initial[0], initial[3], initial[4]), currentLayers)

        hold(initial[4])
        move(gapAbove(initial[2]))
        compose.onNodeWithText("Move above ${initial[2].name}").assertExists()
        panel().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(initial[1], initial[4], initial[2], initial[0], initial[3]), currentLayers)
    }

    @Test fun releaseInBothDirectionsMatchesPreviewAndNativeStackExactly() {
        val initial = showNativeLayers()
        val fixedBounds = initial.associate { it.id to row(it).fetchSemanticsNode().boundsInRoot }
        hold(initial[0], .15f)
        move(gapAbove(initial[3]))
        compose.onNodeWithText("Move below ${initial[2].name}").assertExists()
        assertEquals(initial, currentLayers) // Nothing changes until release.
        initial.forEach { assertEquals(fixedBounds[it.id], row(it).fetchSemanticsNode().boundsInRoot) }
        screenshot("layer-reorder-hover")
        panel().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(initial[1], initial[2], initial[0], initial[3], initial[4]), currentLayers)
        assertEquals(1, reorderCount)

        hold(initial[4], .85f)
        move(gapAbove(initial[2]))
        compose.onNodeWithText("Move above ${initial[2].name}").assertExists()
        panel().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(initial[1], initial[4], initial[2], initial[0], initial[3]), currentLayers)
        assertEquals(2, reorderCount)
    }

    @Test fun groupingLaneGapsDoNothingAndPreviewReleaseGroups() {
        val initial = showNativeLayers()
        val target = preview(initial[2]).fetchSemanticsNode().boundsInRoot
        hold(initial[0])
        move(target.center)
        compose.onNodeWithText("Group with ${initial[2].name}").assertExists()
        screenshot("layer-group-hover")
        val gap = gapAbove(initial[3]).copy(x = target.center.x)
        move(gap)
        compose.onNodeWithText("Release here to keep the layer in place").assertExists()
        panel().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(initial, currentLayers)
        assertEquals(0, reorderCount)
        assertEquals(0, groupCount)

        hold(initial[0])
        move(target.center)
        panel().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, groupCount)
        assertEquals(0, reorderCount)
        val sourceGroup = currentLayers.first { it.id == initial[0].id }.groupId
        assertNotNull(sourceGroup)
        assertEquals(sourceGroup, currentLayers.first { it.id == initial[2].id }.groupId)
    }

    @Test fun switchingFromPreviewToNameCommitsOnlyTheShownReorder() {
        val initial = showNativeLayers()
        hold(initial[0])
        val target = preview(initial[2]).fetchSemanticsNode().boundsInRoot
        move(target.center)
        compose.onNodeWithText("Group with ${initial[2].name}").assertExists()
        move(gapAbove(initial[3]))
        compose.onNodeWithText("Move below ${initial[2].name}").assertExists()
        panel().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(initial[1], initial[2], initial[0], initial[3], initial[4]), currentLayers)
        assertEquals(0, groupCount)
    }

    @Test fun groupBoundariesReleaseAtTheShownSideOfTheWholeGroup() {
        val initial = showNativeLayers()
        compose.runOnIdle { surface.groupLayer(initial[2].id, initial[1].id) }
        compose.waitForIdle()
        val grouped = currentLayers.toList()
        val members = grouped.filter { it.groupId != null }
        hold(initial[4])
        // The top of a group row is below its heading, but must resolve above the whole group.
        move(gapAbove(members.first()))
        compose.onNodeWithText("Move above ${members.first().name}'s group").assertExists()
        panel().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(initial[0].id, initial[4].id) + members.map { it.id } + initial[3].id, currentLayers.map { it.id })
        assertEquals(members.first().groupId, currentLayers.first { it.id == members.first().id }.groupId)

        hold(initial[0])
        move(gapAbove(initial[3]))
        compose.onNodeWithText("Move below ${members.last().name}'s group").assertExists()
        panel().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(initial[4].id) + members.map { it.id } + listOf(initial[0].id, initial[3].id), currentLayers.map { it.id })
        assertNull(currentLayers.first { it.id == initial[0].id }.groupId)
    }

    @Test fun cancellationAndOutsideReleaseDoNotCommit() {
        val initial = showNativeLayers()
        hold(initial[0])
        move(gapAbove(initial[3]))
        panel().performTouchInput { cancel() }
        compose.waitForIdle()
        assertEquals(initial, currentLayers)
        compose.onAllNodesWithText(initial[0].name).assertCountEquals(1)
        hold(initial[0])
        move(gapAbove(initial[3]))
        move(Offset(panel().fetchSemanticsNode().boundsInRoot.right + 20f, 250f))
        panel().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(initial, currentLayers)
        assertEquals(0, reorderCount)
        assertEquals(0, groupCount)
    }

    @Test fun edgeScrollThenReleaseUsesTheNewVisibleGap() {
        val initial = showNativeLayers(16)
        hold(initial[1])
        val panelBounds = panel().fetchSemanticsNode().boundsInRoot
        move(Offset(panelBounds.right - 100f, panelBounds.bottom - 24f))
        repeat(36) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
        }
        screenshot("layer-after-edge-scroll")
        val target = initial[13]
        row(target).assertIsDisplayed()
        move(gapAbove(target))
        compose.onNodeWithText("Move below ${initial[12].name}").assertExists()
        panel().performTouchInput { up() }
        compose.waitForIdle()
        val expected = initial.toMutableList().apply { removeAt(1); add(12, initial[1]) }
        assertEquals(expected, currentLayers)
        assertEquals(1, reorderCount)
    }

    @Test fun everyResolvedGapIsAppliedExactlyByTheNativeStack() {
        showNativeLayers()
        for (source in 0..4) for (boundary in 0..5) {
            compose.runOnIdle {
                surface.configureBlank(32, 32)
                repeat(4) { surface.addPaintLayer() }
                val initial = currentLayers.toList()
                val rows = initial.mapIndexed { index, layer -> layer.id to androidx.compose.ui.geometry.Rect(0f, index * 71f, 296f, index * 71f + 64f) }.toMap()
                val y = when (boundary) { 0 -> 0f; 5 -> 348f; else -> boundary * 71f - 3.5f }
                val drop = resolveLayerDrop(initial, initial[source].id, Offset(140f, y),
                    androidx.compose.ui.geometry.Rect(0f, 0f, 296f, 360f), rows, emptyMap())
                val expected = initial.toMutableList().apply { add(boundary, initial[source]); removeAt(if (source < boundary) source else source + 1) }
                if (drop != null) surface.reorderLayer(initial[source].id, drop.targetId, drop.towardFront)
                assertEquals("source=$source, boundary=$boundary", expected, currentLayers)
            }
        }
    }

    @Test fun clearedOrInvalidCoordinatesNeverReachPixelRounding() {
        assertEquals(IntOffset.Zero, layerDragOffset(Offset.Unspecified, Offset.Zero, Offset.Zero))
        assertEquals(IntOffset.Zero, layerDragOffset(Offset(Float.POSITIVE_INFINITY, 0f), Offset.Zero, Offset.Zero))
        assertEquals(IntOffset(24, 38), layerDragOffset(Offset(34f, 58f), Offset(5f, 10f), Offset(5f, 10f)))
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
