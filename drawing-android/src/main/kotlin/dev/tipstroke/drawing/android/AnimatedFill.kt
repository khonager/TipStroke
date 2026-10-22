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
    private val prepareReveal: Boolean = false,
) {
    val preview = TileStore(source.canvasWidth, source.canvasHeight)
    private val visited = mutableMapOf<TileCoordinate, BitSet>()
    private data class Pending(val pixel: Int, val radius: Float)
    private val frontier = PriorityQueue<Pending>(compareBy<Pending> { it.radius }.thenBy { it.pixel })
    private val target = source.colorAt(x, y)
    private var radius = 24f
    private val arrivals = if (prepareReveal) mutableMapOf<TileCoordinate, ShortArray>() else null
    private val edgeDepths = mutableMapOf<TileCoordinate, ByteArray>()
    private var arrival = 0
    private var maximumArrival = 1

    fun takeRevealMasks(): Map<TileCoordinate, android.graphics.Bitmap> {
        val pixels = IntArray(256 * 256)
        return arrivals?.mapValues { (_, times) ->
            for (i in times.indices) {
                val time = times[i].toInt() and 0xffff
                pixels[i] = if (time == 0) 0 else ((1 + time * 254 / maximumArrival) shl 24)
            }
            android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ALPHA_8).apply {
                setPixels(pixels, 0, 256, 0, 0, 256, 256)
            }
        }.orEmpty().also { arrivals?.clear() }
    }

    fun discard() { preview.discard(); arrivals?.clear(); edgeDepths.clear() }

    var pixelCount = 0L
        private set
    init {
        if (color != target && Color.alpha(color) != 0 && allowed(x, y)) {
            markVisited(x, y)
            frontier.add(Pending(y * source.canvasWidth + x, 0f))
        }
    }
    /** Only call after preparation has finished; confirms a nearby hover seed was in the same region. */
    fun containsDrop(x: Int, y: Int, currentColor: Int): Boolean =
        allowed(x, y) && distance(currentColor) <= 24 && Color.alpha(preview.colorAt(x, y)) != 0

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
        // Allocation is limited to reachable tiles of the finite document. A spill
        // must not disappear merely because its preview crosses 128 tiles (32 MiB).
        dirty += preview.putFillPixel(x, y, value)
        arrivals?.getOrPut(TileCoordinate(x / 256, y / 256)) { ShortArray(256 * 256) }
            ?.set((y % 256) * 256 + x % 256, (arrival + 1).toShort())
        maximumArrival = max(maximumArrival, arrival + 1)
        pixelCount++
    }

    private fun premultiplied(value: Int): FloatArray {
        val alpha = Color.alpha(value).toFloat()
        return floatArrayOf(Color.red(value) * alpha / 255f, Color.green(value) * alpha / 255f,
            Color.blue(value) * alpha / 255f, alpha)
    }

    /** Replace the old region contribution, including alpha, rather than tinting its fringe. */
    private fun edgeReplacement(x: Int, y: Int, value: Int): Int? {
        val edge = premultiplied(value)
        val replacement = premultiplied(color)
        if (Color.alpha(target) <= 24) {
            if (Color.alpha(value) == 255) return null
            val background = 1f - edge[3] / 255f
            for (i in 0..3) edge[i] += replacement[i] * background
        } else {
            val original = premultiplied(target)
            var strongest = distance(value)
            var background = 0f
            // Look past a two-pixel softened edge for the solid line or transparent
            // exterior. Only accept references that explain this pixel as a mixture.
            for (dy in -2..2) for (dx in -2..2) {
                val sx = x + dx; val sy = y + dy
                if (sx !in 0 until source.canvasWidth || sy !in 0 until source.canvasHeight) continue
                val candidate = source.colorAt(sx, sy)
                val contrast = distance(candidate)
                if (contrast <= strongest) continue
                val boundary = premultiplied(candidate)
                var dot = 0f
                var length = 0f
                for (i in 0..3) {
                    val delta = boundary[i] - original[i]
                    dot += (edge[i] - original[i]) * delta
                    length += delta * delta
                }
                if (length == 0f) continue
                val coverage = (dot / length).coerceIn(0f, 1f)
                if (coverage >= 1f || (0..3).any {
                    abs(edge[it] - (original[it] + coverage * (boundary[it] - original[it]))) > 16f
                }) continue
                strongest = contrast
                background = 1f - coverage
            }
            if (background == 0f) return null // Solid ink or unrelated neighboring color.
            for (i in 0..3) edge[i] += (replacement[i] - original[i]) * background
        }
        val alpha = edge[3].roundToInt().coerceIn(1, 255)
        fun channel(i: Int) = (edge[i] * 255f / alpha).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha, channel(0), channel(1), channel(2))
    }

    /** Eight-direction, two-pixel cleanup. Soft edges may extend cleanup, never the flood. */
    private fun overlap(x: Int, y: Int, depth: Int, dirty: MutableSet<TileCoordinate>) {
        if (!allowed(x, y)) return
        val coordinate = TileCoordinate(x / 256, y / 256)
        val index = (y % 256) * 256 + x % 256
        val previous = edgeDepths[coordinate]?.get(index)?.toInt() ?: 0
        if (previous != 0 && previous <= depth) return
        val value = source.colorAt(x, y)
        if (distance(value) <= 24) return
        edgeDepths.getOrPut(coordinate) { ByteArray(256 * 256) }[index] = depth.toByte()
        val result = edgeReplacement(x, y, value) ?: return
        if (previous == 0) write(x, y, result, dirty)
        if (depth < 2) for (dy in -1..1) for (dx in -1..1) {
            if (dx != 0 || dy != 0) overlap(x + dx, y + dy, depth + 1, dirty)
        }
    }

    private fun enqueue(x: Int, y: Int, arrival: Float, dirty: MutableSet<TileCoordinate>) {
        if (!allowed(x, y) || !markVisited(x, y)) return
        val value = source.colorAt(x, y)
        val difference = distance(value)
        if (difference > 24) {
            overlap(x, y, 1, dirty)
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
        while (frontier.isNotEmpty() && (prepareReveal || requireNotNull(frontier.peek()).radius <= radius) && processed < 16384 && System.nanoTime() < deadline) {
            val pending = frontier.remove()
            arrival = pending.radius.roundToInt()
            val x = pending.pixel % source.canvasWidth
            val y = pending.pixel / source.canvasWidth
            write(x, y, color, dirty)
            enqueue(x - 1, y, pending.radius, dirty); enqueue(x + 1, y, pending.radius, dirty)
            enqueue(x, y - 1, pending.radius, dirty); enqueue(x, y + 1, pending.radius, dirty)
            // Diagonal edge pixels at tight corners are not cardinal flood neighbors.
            overlap(x - 1, y - 1, 1, dirty); overlap(x + 1, y - 1, 1, dirty)
            overlap(x - 1, y + 1, 1, dirty); overlap(x + 1, y + 1, 1, dirty)
            processed++
        }
        return dirty
    }
}
