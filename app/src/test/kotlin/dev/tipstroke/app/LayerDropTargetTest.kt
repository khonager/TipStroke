package dev.tipstroke.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.tipstroke.core.model.*
import org.junit.Assert.*
import org.junit.Test

class LayerDropTargetTest {
    private val ids = (0..3).map { LayerId("layer-$it") }
    private val layers = ids.map { LayerSummary(it, it.value, LayerKind.RASTER, true, 1f) }
    private val rows = ids.mapIndexed { index, id -> id to Rect(0f, index * 71f, 296f, index * 71f + 64f) }.toMap()
    private val previews = rows.mapValues { (_, rect) -> Rect(8f, rect.top + 8f, 56f, rect.top + 56f) }
    private val viewport = Rect(0f, 0f, 296f, 284f)

    @Test fun nameDropPreviewsInsertionButOnlyThumbnailDropGroups() {
        val name = resolveLayerDrop(layers, ids[0], Offset(140f, 195f), viewport, rows, previews)!!
        assertFalse(name.group)
        assertEquals(2, name.destinationIndex)
        assertFalse(name.towardFront)
        val preview = resolveLayerDrop(layers, ids[0], Offset(30f, 175f), viewport, rows, previews)!!
        assertTrue(preview.group)
        assertEquals(ids[2], preview.targetId)
        assertNull(resolveLayerDrop(layers, ids[0], Offset(140f, 300f), viewport, rows, previews))
    }

    @Test fun groupingUsesTheVisiblePreviewAfterRowsSlideAside() {
        val shifted = previews.mapValues { (id, rect) ->
            if (id != ids[0]) rect.translate(Offset(0f, -71f)) else rect
        }
        val gap = resolveLayerDrop(layers, ids[0], Offset(30f, 245f), viewport, rows, shifted)!!
        assertFalse(gap.group)
        val actualPreview = resolveLayerDrop(layers, ids[0], Offset(30f, 100f), viewport, rows, shifted)!!
        assertTrue(actualPreview.group)
        assertEquals(ids[2], actualPreview.targetId)
    }

    @Test fun insertionLineStaysOnOneSlotAcrossTargetBoundary() {
        val belowFirst = resolveLayerDrop(layers, ids[0], Offset(140f, 125f), viewport, rows, previews)!!
        val aboveSecond = resolveLayerDrop(layers, ids[0], Offset(140f, 150f), viewport, rows, previews)!!
        assertEquals(1, belowFirst.destinationIndex)
        assertEquals(1, aboveSecond.destinationIndex)
        assertNotEquals(belowFirst.targetId, aboveSecond.targetId)
        val itemBounds = rows + (ids[1] to Rect(0f, 64f, 296f, 135f))
        assertEquals(71f, insertionLineY(layers, 0, belowFirst.destinationIndex, itemBounds))
        assertEquals(71f, insertionLineY(layers, 0, aboveSecond.destinationIndex, itemBounds))

        val upwardFromFirst = resolveLayerDrop(layers, ids[3], Offset(140f, 60f), viewport, rows, previews)!!
        val upwardFromSecond = resolveLayerDrop(layers, ids[3], Offset(140f, 80f), viewport, rows, previews)!!
        assertEquals(1, upwardFromFirst.destinationIndex)
        assertEquals(1, upwardFromSecond.destinationIndex)
        assertEquals(64f, insertionLineY(layers, 3, upwardFromFirst.destinationIndex, itemBounds))
        assertEquals(64f, insertionLineY(layers, 3, upwardFromSecond.destinationIndex, itemBounds))
    }

    @Test fun outsideLayerSnapsBeyondWholeGroup() {
        val grouped = layers.mapIndexed { i, layer -> layer.copy(groupId = if (i in 1..2) "group" else null) }
        val drop = resolveLayerDrop(grouped, ids[3], Offset(140f, 80f), viewport, rows, previews)!!
        assertEquals(1, drop.destinationIndex)
        assertTrue(drop.towardFront)
    }
}
