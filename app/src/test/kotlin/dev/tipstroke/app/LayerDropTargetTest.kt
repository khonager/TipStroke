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

    @Test fun everyGapInBothDirectionsResolvesToExactlyTheIndicatedPosition() {
        val boundaries = listOf(0f, 67.5f, 138.5f, 209.5f, 277f)
        for (source in layers.indices) for ((boundary, y) in boundaries.withIndex()) {
            val drop = resolveLayerDrop(layers, ids[source], Offset(140f, y), viewport, rows, previews)
            if (boundary == source || boundary == source + 1) {
                assertNull("Dropping beside itself must not move a layer", drop)
                continue
            }
            requireNotNull(drop)
            assertFalse(drop.group)
            assertEquals(boundary - if (source < boundary) 1 else 0, drop.destinationIndex)
            assertEquals(y, insertionLineY(layers, drop, rows)!!, .01f)
            // Apply the public callback contract, independently of destinationIndex.
            val result = ids.toMutableList().apply { remove(ids[source]) }
            result.add(result.indexOf(drop.targetId) + if (drop.towardFront) 0 else 1, ids[source])
            val expected = ids.toMutableList().apply { add(boundary, ids[source]); removeAt(if (source < boundary) source else source + 1) }
            assertEquals("source=$source, boundary=$boundary", expected, result)
        }
    }

    @Test fun thumbnailLaneNeverFallsThroughToReordering() {
        for (y in 0..277) {
            val point = Offset(30f, y.toFloat())
            val drop = resolveLayerDrop(layers, ids[0], point, viewport, rows, previews)
            val target = ids.drop(1).firstOrNull { previews.getValue(it).contains(point) }
            if (target == null) assertNull("No reorder between thumbnails at y=$y", drop)
            else {
                assertTrue(drop!!.group)
                assertEquals(target, drop.targetId)
                assertNull(insertionLineY(layers, drop, rows))
            }
        }
    }

    @Test fun movingSidewaysSwitchesActionsWithoutMovingTargets() {
        val group = resolveLayerDrop(layers, ids[0], Offset(30f, 190f), viewport, rows, previews)!!
        val reorder = resolveLayerDrop(layers, ids[0], Offset(140f, 190f), viewport, rows, previews)!!
        assertTrue(group.group)
        assertEquals(ids[2], group.targetId)
        assertFalse(reorder.group)
        assertEquals(ids[2], reorder.targetId)
        assertFalse(reorder.towardFront)
        assertEquals(209.5f, insertionLineY(layers, reorder, rows)!!, .01f)
    }

    @Test fun outsideLayerCanOnlyLandBeforeOrAfterWholeGroup() {
        val grouped = layers.mapIndexed { i, layer -> layer.copy(groupId = if (i in 1..2) "group" else null) }
        val above = resolveLayerDrop(grouped, ids[3], Offset(140f, 90f), viewport, rows, previews)!!
        assertEquals(ids[1], above.targetId)
        assertEquals(1, above.destinationIndex)
        assertEquals(67.5f, insertionLineY(grouped, above, rows)!!, .01f)
        val below = resolveLayerDrop(grouped, ids[0], Offset(140f, 180f), viewport, rows, previews)!!
        assertEquals(ids[2], below.targetId)
        assertEquals(2, below.destinationIndex)
        assertEquals(209.5f, insertionLineY(grouped, below, rows)!!, .01f)
    }

    @Test fun groupMembersCanReorderButCannotAccidentallyRegroup() {
        val grouped = layers.map { it.copy(groupId = "group") }
        assertNull(resolveLayerDrop(grouped, ids[0], Offset(30f, 190f), viewport, rows, previews))
        val drop = resolveLayerDrop(grouped, ids[0], Offset(140f, 190f), viewport, rows, previews)!!
        assertFalse(drop.group)
        assertEquals(2, drop.destinationIndex)
    }

    @Test fun groupHeadingHeightIsIncludedInInsertionGuide() {
        val items = rows + (ids[1] to Rect(0f, 71f, 296f, 165f)) +
            (ids[2] to Rect(0f, 172f, 296f, 236f)) + (ids[3] to Rect(0f, 243f, 296f, 307f))
        val drop = resolveLayerDrop(layers, ids[0], Offset(140f, 168f), viewport, rows, previews, items)!!
        assertEquals(1, drop.destinationIndex)
        assertEquals(168.5f, insertionLineY(layers, drop, items)!!, .01f)
    }

    @Test fun scrolledViewportUsesVisibleBoundariesAndRejectsOutsideRelease() {
        val scrolled = rows.mapValues { (_, rect) -> rect.translate(Offset(0f, -71f)) }
        val scrolledPreviews = previews.mapValues { (_, rect) -> rect.translate(Offset(0f, -71f)) }
        val drop = resolveLayerDrop(layers, ids[0], Offset(140f, 138.5f), viewport, scrolled, scrolledPreviews)!!
        assertEquals(2, drop.destinationIndex)
        assertEquals(138.5f, insertionLineY(layers, drop, scrolled)!!, .01f)
        for (point in listOf(Offset(-1f, 100f), Offset(300f, 100f), Offset(140f, -1f), Offset(140f, 300f), Offset.Unspecified)) {
            assertNull(resolveLayerDrop(layers, ids[0], point, viewport, rows, previews))
        }
    }
}
