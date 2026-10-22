package dev.tipstroke.drawing.android

import android.graphics.Color
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.geometry.TileCoordinate
import java.util.BitSet
import java.util.PriorityQueue
import kotlin.math.*

/** Connected propagation ordered by circular distance; boundary overlap never propagates. */
internal class AnimatedFill(
    private val source: TileStore,
    private val x: Int,
    private val y: Int,
    private val color: Int,
    private val selection: SelectionRegion?,
) {
    val preview = TileStore(source.canvasWidth, source.canvasHeight)
    private val visited = mutableMapOf<TileCoordinate, BitSet>()
    private data class Pending(val pixel: Int, val radius: Float)
    private val frontier = PriorityQueue<Pending>(compareBy<Pending> { it.radius }.thenBy { it.pixel })
    private val target = source.colorAt(x, y)
    private var radius = 24f
    var pixelCount = 0L
        private set
    init {
        if (color != target && Color.alpha(color) != 0 && allowed(x, y)) {
            markVisited(x, y)
            frontier.add(Pending(y * source.canvasWidth + x, 0f))
        }
    }
    val complete get() = frontier.isEmpty()

    private fun allowed(x: Int, y: Int) = x in 0 until source.canvasWidth && y in 0 until source.canvasHeight &&
        (selection == null || selection.contains(Point(x + .5f, y + .5f)))

    private fun markVisited(x: Int, y: Int): Boolean {
        val bits = visited.getOrPut(TileCoordinate(x / 256, y / 256)) { BitSet(256 * 256) }
        val index = (y % 256) * 256 + x % 256
        if (bits[index]) return false
        bits.set(index)
        return true
    }

    private fun distance(value: Int): Int {
        var difference = abs(Color.alpha(value) - Color.alpha(target))
        for (shift in 0..16 step 8) {
            fun channel(c: Int) = ((c ushr shift) and 255) * Color.alpha(c) / 255
            difference = max(difference, abs(channel(value) - channel(target)))
        }
        return difference
    }

    private fun write(x: Int, y: Int, value: Int, dirty: MutableSet<TileCoordinate>) {
        check(preview.allocatedTileBytes < 32L * 1024 * 1024 || preview.hasTile(x, y)) {
            "Fill is too large for the preview budget. Select a smaller area and try again."
        }
        dirty += preview.putFillPixel(x, y, value)
        pixelCount++
    }

    /** Extend exactly one pixel into antialiasing, without using those pixels as fill seeds. */
    private fun overlap(x: Int, y: Int, value: Int, difference: Int, dirty: MutableSet<TileCoordinate>) {
        val channels = IntArray(3)
        if (Color.alpha(target) <= 24) {
            // Underpaint translucent ink. This overlay, composited over the source, is
            // algebraically equivalent to placing the fill behind that source pixel.
            if (Color.alpha(value) == 255) return
            val coverage = Color.alpha(value) / 255f
            for (i in 0..2) {
                val shift = i * 8
                channels[i] = ((((value ushr shift) and 255) * coverage) +
                    ((color ushr shift) and 255) * (1f - coverage)).roundToInt()
            }
        } else {
            // Flattened artwork: estimate edge coverage from nearby stronger ink,
            // then replace only its background contribution with the fill color.
            var strongest = difference
            for (dy in -1..1) for (dx in -1..1) {
                if (allowed(x + dx, y + dy)) strongest = max(strongest, distance(source.colorAt(x + dx, y + dy)))
            }
            if (strongest == difference) return // Solid outline, not a softened edge.
            val background = 1f - difference.toFloat() / strongest
            for (i in 0..2) {
                val shift = i * 8
                channels[i] = (((value ushr shift) and 255) + background *
                    (((color ushr shift) and 255) - ((target ushr shift) and 255))).roundToInt().coerceIn(0, 255)
            }
        }
        write(x, y, Color.argb(Color.alpha(color), channels[2], channels[1], channels[0]), dirty)
    }

    private fun enqueue(x: Int, y: Int, arrival: Float, dirty: MutableSet<TileCoordinate>) {
        if (!allowed(x, y) || !markVisited(x, y)) return
        val value = source.colorAt(x, y)
        val difference = distance(value)
        if (difference > 24) {
            overlap(x, y, value, difference, dirty)
            return
        }
        // A pixel must be reachable before its radial wave arrives. The inherited
        // radius keeps regions beyond a gap from appearing before the gap is reached.
        frontier.add(Pending(y * source.canvasWidth + x, max(arrival, hypot((x - this.x).toFloat(), (y - this.y).toFloat()))))
    }

    /** Bounded work per frame; finish any backlog before advancing the circular front. */
    fun advance(): Set<TileCoordinate> {
        val dirty = mutableSetOf<TileCoordinate>()
        val deadline = System.nanoTime() + 4_000_000L
        frontier.peek()?.let { if (it.radius > radius) radius += 24f }
        var processed = 0
        while ((frontier.peek()?.radius ?: Float.POSITIVE_INFINITY) <= radius && processed < 16384 && System.nanoTime() < deadline) {
            val pending = frontier.remove()
            val x = pending.pixel % source.canvasWidth
            val y = pending.pixel / source.canvasWidth
            write(x, y, color, dirty)
            enqueue(x - 1, y, pending.radius, dirty); enqueue(x + 1, y, pending.radius, dirty)
            enqueue(x, y - 1, pending.radius, dirty); enqueue(x, y + 1, pending.radius, dirty)
            processed++
        }
        return dirty
    }
}
