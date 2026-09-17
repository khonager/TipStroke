package dev.tipstroke.drawing.android

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerStackTest {
    @Test fun selectedLayerCanBeRenamed() {
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 2048, 2048) {}

        stack.renameSelected("  Line art  ")
        assertEquals("Line art", stack.selected().name)

        stack.renameSelected("   ")
        assertEquals("Line art", stack.selected().name)
    }

    @Test fun paintLayersCanBeOrderedHiddenAndMadeTranslucent() {
        var invalidations = 0
        val stack = LayerStack(RuntimeEnvironment.getApplication().contentResolver, 2048, 2048) { invalidations++ }
        val first = stack.selectedId
        val second = stack.addRaster()

        stack.setOpacity(.42f)
        stack.toggleVisible(second)
        assertEquals(listOf(second, first), stack.summariesFrontToBack().map { it.id })
        assertEquals(.42f, stack.selected().opacity, .001f)
        assertFalse(stack.selected().visible)

        stack.moveSelected(towardFront = false)
        assertEquals(listOf(first, second), stack.summariesFrontToBack().map { it.id })
        assertTrue(stack.deleteSelected())
        assertEquals(1, stack.layers.size)
        assertTrue(invalidations >= 4)
    }
}
