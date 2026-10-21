package dev.tipstroke.drawing.android

import android.graphics.Color
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.geometry.TileCoordinate
import java.util.BitSet
import kotlin.math.abs

/** Breadth-first propagation follows actual connectivity, including narrow leaks. */
internal class AnimatedFill(
    private val source: TileStore,
    x: Int,
    y: Int,
    private val color: Int,
    private val selection: SelectionRegion?,
) {
    val preview = TileStore(source.canvasWidth, source.canvasHeight)
    private val visited = mutableMapOf<TileCoordinate, BitSet>()
    private val frontier = ArrayDeque<Int>()
    private val target = source.colorAt(x, y)
    var pixelCount = 0L
        private set
    private var waveRemaining = 1
    init {
        if (color != target && Color.alpha(color) != 0) enqueue(x, y)
    }
    val complete get() = frontier.isEmpty()

    private fun enqueue(x: Int, y: Int) {
        if (x !in 0 until source.canvasWidth || y !in 0 until source.canvasHeight) return
        val coordinate = TileCoordinate(x / 256, y / 256)
        val bits = visited.getOrPut(coordinate) { BitSet(256 * 256) }
        val index = (y % 256) * 256 + x % 256
        if (bits[index]) return
        bits.set(index)
        if (selection != null && !selection.contains(Point(x + .5f, y + .5f))) return
        val value = source.colorAt(x, y)
        // Compare premultiplied channels: transparent pixels have no meaningful RGB.
        fun channel(c: Int, shift: Int) = ((c ushr shift) and 255) * Color.alpha(c) / 255
        if (abs(Color.alpha(value) - Color.alpha(target)) > 24) return
        for (shift in 0..16 step 8) {
            if (abs(channel(value, shift) - channel(target, shift)) > 24) return
        }
        frontier.addLast(y * source.canvasWidth + x)
    }

    /** Bounded work per frame, with a maximum wave speed independent of region area. */
    fun advance(): Set<TileCoordinate> {
        val dirty = mutableSetOf<TileCoordinate>()
        val deadline = System.nanoTime() + 4_000_000L
        var waves = 0
        var processed = 0
        while (frontier.isNotEmpty() && waves < 12 && processed < 8192 && System.nanoTime() < deadline) {
            val pixel = frontier.removeFirst()
            val x = pixel % source.canvasWidth
            val y = pixel / source.canvasWidth
            // Bound transient bitmap memory; never leave a partial fill committed.
            check(preview.allocatedTileBytes < 32L * 1024 * 1024 || preview.hasTile(x, y)) {
                "Fill is too large for the preview budget. Select a smaller area and try again."
            }
            dirty += preview.putFillPixel(x, y, color)
            pixelCount++
            enqueue(x - 1, y); enqueue(x + 1, y); enqueue(x, y - 1); enqueue(x, y + 1)
            processed++
            waveRemaining--
            if (waveRemaining <= 0) { waveRemaining = frontier.size; waves++ }
        }
        return dirty
    }
}
