package dev.tipstroke.core.brush

import dev.tipstroke.core.model.BrushPreset
import kotlin.test.Test
import kotlin.test.assertEquals

class TipDockTest {
    private val starter = listOf(BrushPreset.Ink, BrushPreset.Pencil, BrushPreset.Airbrush, BrushPreset.HardAirbrush)
        .map { it.id.value }
    private val available = starter.toSet() + listOf("a", "b")

    @Test fun movingHiddenTipRestoresItsPreviousTipAfterReload() {
        val dock = TipDock.load(null, null)
        assertEquals(starter, dock.ordered(available))
        val edited = dock.replace(3, "a", available).replace(3, "b", available)
        val restored = TipDock.load(edited.encode(), null).replace(0, "b", available)
        assertEquals(listOf("b", starter[1], starter[2], "a"), restored.ordered(available))
        val movedAgain = restored.replace(1, "a", available)
        assertEquals(listOf("b", "a", starter[2], starter[3]), movedAgain.ordered(available))
    }

    @Test fun movingAStarterUsesTheFirstUnusedDefault() {
        val dock = TipDock.load(null, null).replace(2, starter.first(), available)
        assertEquals(listOf(starter[2], starter[1], starter[0], starter[3]), dock.ordered(available))
    }
}
