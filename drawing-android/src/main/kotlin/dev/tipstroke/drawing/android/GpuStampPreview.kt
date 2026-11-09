package dev.tipstroke.drawing.android

import android.graphics.Canvas
import android.graphics.RenderNode
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.drawing.StrokeStyle
import dev.tipstroke.core.geometry.TileCoordinate
import dev.tipstroke.core.geometry.TileGrid
import kotlin.math.ceil
import kotlin.math.floor

/** Records only newly sampled stamps. Hardware canvas renders coverage at the current view scale. */
internal class GpuStampPreview(val style: StrokeStyle, private val width: Int, private val height: Int) : AutoCloseable {
    private val renderer = StampBrushRenderer(style)
    private val nodes = mutableListOf<RenderNode>()
    val dirtyTiles = linkedSetOf<TileCoordinate>()
    private var stampCount = 0
    var capacityReached = false
        private set

    fun append(samples: List<StrokeSample>): Set<TileCoordinate> {
        val stamps = renderer.path.append(samples)
        if (stamps.isEmpty()) return emptySet()
        // A very long gesture falls back to incremental raster painting; never
        // retain an unbounded display list of every stamp made before pen-up.
        if (nodes.size >= 2048 || stampCount + stamps.size > 65_536) {
            capacityReached = true
            return emptySet()
        }
        val bounds = stamps.map(StampBrushRenderer::bounds)
        val left = floor(bounds.minOf { it.left }).toInt().coerceAtLeast(0)
        val top = floor(bounds.minOf { it.top }).toInt().coerceAtLeast(0)
        val right = ceil(bounds.maxOf { it.right }).toInt().coerceAtMost(width)
        val bottom = ceil(bounds.maxOf { it.bottom }).toInt().coerceAtMost(height)
        if (right <= left || bottom <= top) return emptySet()
        val dirty = bounds.flatMapTo(linkedSetOf()) { TileGrid.intersecting(it, width, height) }
        val node = RenderNode("Stamp batch").apply { setPosition(left, top, right, bottom) }
        val canvas = node.beginRecording()
        canvas.translate(-left.toFloat(), -top.toFloat())
        renderer.draw(canvas, stamps)
        node.endRecording()
        nodes += node
        stampCount += stamps.size
        dirtyTiles += dirty
        return dirty
    }

    private var cachedPrefix: RenderNode? = null
    private var cachedCount = 0
    private var cachedScale = 0f

    /** A bounded display-resolution layer keeps old stamps from being shaded every frame.
     * Original nodes remain available for exact full-resolution tile commits. */
    fun drawPreview(canvas: Canvas, scale: Float) {
        val resolution = scale.coerceIn(.01f, 2048f / maxOf(width, height))
        if (cachedScale != resolution || nodes.size - cachedCount >= 64) {
            cachedPrefix?.discardDisplayList()
            cachedScale = resolution
            cachedCount = nodes.size
            cachedPrefix = RenderNode("Stamp preview prefix").apply {
                setPosition(0, 0, ceil(width * resolution).toInt(), ceil(height * resolution).toInt())
                setUseCompositingLayer(true, null)
                val recording = beginRecording()
                recording.scale(resolution, resolution)
                nodes.take(cachedCount).forEach(recording::drawRenderNode)
                endRecording()
            }
        }
        cachedPrefix?.let {
            val saved = canvas.save()
            canvas.scale(1f / resolution, 1f / resolution)
            canvas.drawRenderNode(it)
            canvas.restoreToCount(saved)
        }
        for (index in cachedCount until nodes.size) canvas.drawRenderNode(nodes[index])
    }

    fun draw(canvas: Canvas) { nodes.forEach(canvas::drawRenderNode) }
    override fun close() { cachedPrefix?.discardDisplayList(); cachedPrefix = null; nodes.forEach(RenderNode::discardDisplayList); nodes.clear(); renderer.close() }
}
