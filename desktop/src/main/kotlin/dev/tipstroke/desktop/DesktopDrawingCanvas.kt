package dev.tipstroke.desktop

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.ArrayDeque
import javax.imageio.ImageIO
import javax.swing.JComponent
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal enum class DesktopTool(val label: String) {
    PENCIL("Pencil"),
    INK("Ink"),
    AIRBRUSH("Airbrush"),
    ERASER("Eraser"),
}

internal data class HistoryState(val canUndo: Boolean, val canRedo: Boolean)

/**
 * Desktop's latency-sensitive surface is an AWT component, not Compose state. It owns sparse
 * raster tiles and mutates only tiles intersecting the current sample. Compose is only the shell.
 */
internal class DesktopDrawingCanvas : JComponent() {
    var selectedTool: DesktopTool = DesktopTool.PENCIL
    var brushSize: Float = 9f
    var brushOpacity: Float = .82f
    var brushColor: Color = Color(28, 28, 30)
    var onHistoryChanged: (HistoryState) -> Unit = {}
    var onDocumentChanged: (Int, Int) -> Unit = { _, _ -> }

    private var documentWidth = DEFAULT_DOCUMENT_SIZE
    private var documentHeight = DEFAULT_DOCUMENT_SIZE
    private val tiles = linkedMapOf<TileKey, BufferedImage>()
    private val undo = ArrayDeque<TileEdit>()
    private val redo = ArrayDeque<TileEdit>()
    private var undoBytes = 0L

    private var panX = 0.0
    private var panY = 0.0
    private var zoom = 1.0
    private var fittedOnce = false
    private var spaceDown = false
    private var drawing = false
    private var panning = false
    private var previousDocumentPoint: Point2D.Double? = null
    private var previousScreenPoint: Point? = null
    private var strokeBefore = linkedMapOf<TileKey, BufferedImage?>()
    private var activeAirbrush: DesktopAirbrushStroke? = null

    init {
        isFocusable = true
        preferredSize = Dimension(900, 700)
        background = WORKSPACE_COLOR
        cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (!fittedOnce && width > 0 && height > 0) {
                    fittedOnce = true
                    fitToView()
                }
            }
        })

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                when {
                    event.keyCode == KeyEvent.VK_SPACE -> {
                        spaceDown = true
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    }
                    shortcutDown(event) && event.keyCode == KeyEvent.VK_Z && event.isShiftDown -> redo()
                    shortcutDown(event) && event.keyCode == KeyEvent.VK_Z -> undo()
                    shortcutDown(event) && event.keyCode == KeyEvent.VK_Y -> redo()
                    shortcutDown(event) && event.keyCode == KeyEvent.VK_0 -> fitToView()
                }
            }

            override fun keyReleased(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_SPACE) {
                    spaceDown = false
                    cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                }
            }
        })

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                requestFocusInWindow()
                if (event.button == MouseEvent.BUTTON2 || event.button == MouseEvent.BUTTON3 || spaceDown) {
                    panning = true
                    previousScreenPoint = event.point
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    return
                }
                if (event.button == MouseEvent.BUTTON1) beginStroke(screenToDocument(event.point))
            }

            override fun mouseReleased(event: MouseEvent) {
                if (drawing) finishStroke()
                panning = false
                previousScreenPoint = null
                cursor = Cursor.getPredefinedCursor(if (spaceDown) Cursor.HAND_CURSOR else Cursor.CROSSHAIR_CURSOR)
            }

            override fun mouseExited(event: MouseEvent) {
                if (!drawing && !panning) repaint()
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) {
                if (panning) {
                    val previous = previousScreenPoint ?: event.point
                    panX += event.x - previous.x
                    panY += event.y - previous.y
                    previousScreenPoint = event.point
                    repaint()
                } else if (drawing) {
                    continueStroke(screenToDocument(event.point))
                }
            }
        })

        addMouseWheelListener(::handleWheel)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.color = WORKSPACE_COLOR
            g.fillRect(0, 0, width, height)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.translate(panX, panY)
            g.scale(zoom, zoom)

            g.color = Color.WHITE
            g.fillRect(0, 0, documentWidth, documentHeight)

            val clip = g.clipBounds
            for ((key, tile) in tiles) {
                val x = key.x * TILE_SIZE
                val y = key.y * TILE_SIZE
                if (clip == null || clip.intersects(x.toDouble(), y.toDouble(), TILE_SIZE.toDouble(), TILE_SIZE.toDouble())) {
                    g.drawImage(tile, x, y, null)
                }
            }

            activeAirbrush?.let { stroke ->
                val preview = g.create() as Graphics2D
                try {
                    preview.clip(Rectangle2D.Double(0.0, 0.0, documentWidth.toDouble(), documentHeight.toDouble()))
                    paintAirbrushStroke(preview, stroke)
                } finally {
                    preview.dispose()
                }
            }

            g.color = Color(255, 255, 255, 70)
            g.stroke = BasicStroke((1.0 / zoom).toFloat())
            g.drawRect(0, 0, documentWidth, documentHeight)
        } finally {
            g.dispose()
        }
    }

    fun fitToView() {
        if (width <= 0 || height <= 0) return
        val margin = 48.0
        zoom = min(
            (width - margin * 2) / documentWidth,
            (height - margin * 2) / documentHeight,
        ).coerceIn(MIN_ZOOM, 1.0)
        panX = (width - documentWidth * zoom) / 2.0
        panY = (height - documentHeight * zoom) / 2.0
        repaint()
    }

    fun undo() {
        val edit = undo.pollLast() ?: return
        restore(edit.before)
        undoBytes -= edit.bytes
        redo.addLast(edit)
        publishHistory()
    }

    fun redo() {
        val edit = redo.pollLast() ?: return
        restore(edit.after)
        undo.addLast(edit)
        undoBytes += edit.bytes
        trimUndo()
        publishHistory()
    }

    fun clearDocument() {
        if (tiles.isEmpty()) return
        val before = tiles.mapValues { copyImage(it.value) }
        val edit = TileEdit(before = before, after = before.keys.associateWith { null })
        tiles.clear()
        pushUndo(edit)
        repaint()
    }

    fun newDocument(width: Int = DEFAULT_DOCUMENT_SIZE, height: Int = DEFAULT_DOCUMENT_SIZE) {
        documentWidth = width.coerceIn(MIN_DOCUMENT_SIZE, MAX_DOCUMENT_SIZE)
        documentHeight = height.coerceIn(MIN_DOCUMENT_SIZE, MAX_DOCUMENT_SIZE)
        tiles.clear()
        undo.clear()
        redo.clear()
        undoBytes = 0
        publishHistory()
        onDocumentChanged(documentWidth, documentHeight)
        fitToView()
    }

    fun exportPng(file: File): Boolean {
        val output = BufferedImage(documentWidth, documentHeight, BufferedImage.TYPE_INT_ARGB)
        val g = output.createGraphics()
        try {
            for ((key, tile) in tiles) {
                g.drawImage(tile, key.x * TILE_SIZE, key.y * TILE_SIZE, null)
            }
        } finally {
            g.dispose()
        }
        return ImageIO.write(output, "png", file)
    }

    fun openImage(file: File) {
        val source = ImageIO.read(file) ?: error("Unsupported image: ${file.name}")
        require(source.width in MIN_DOCUMENT_SIZE..MAX_DOCUMENT_SIZE && source.height in MIN_DOCUMENT_SIZE..MAX_DOCUMENT_SIZE) {
            "Images must be between $MIN_DOCUMENT_SIZE and $MAX_DOCUMENT_SIZE pixels per side"
        }

        documentWidth = source.width
        documentHeight = source.height
        tiles.clear()
        val xTiles = ceil(documentWidth / TILE_SIZE.toDouble()).toInt()
        val yTiles = ceil(documentHeight / TILE_SIZE.toDouble()).toInt()
        for (tileY in 0 until yTiles) {
            for (tileX in 0 until xTiles) {
                val tile = newTile()
                val g = tile.createGraphics()
                try {
                    g.drawImage(source, -tileX * TILE_SIZE, -tileY * TILE_SIZE, null)
                } finally {
                    g.dispose()
                }
                if (!isEmpty(tile)) tiles[TileKey(tileX, tileY)] = tile
            }
        }
        undo.clear()
        redo.clear()
        undoBytes = 0
        publishHistory()
        onDocumentChanged(documentWidth, documentHeight)
        fitToView()
    }

    private fun beginStroke(point: Point2D.Double) {
        if (!insideDocument(point)) return
        drawing = true
        strokeBefore = linkedMapOf()
        previousDocumentPoint = point
        if (selectedTool == DesktopTool.AIRBRUSH) {
            activeAirbrush = DesktopAirbrushStroke(
                start = point,
                size = brushSize.coerceAtLeast(1f),
                opacity = brushOpacity.coerceIn(0f, 1f),
                color = brushColor,
            )
            repaintAirbrushSegment(point, point, activeAirbrush!!)
        } else {
            drawSegment(point, point)
        }
    }

    private fun continueStroke(point: Point2D.Double) {
        val previous = previousDocumentPoint ?: point
        val airbrush = activeAirbrush
        if (airbrush != null) {
            airbrush.append(point)
            repaintAirbrushSegment(previous, point, airbrush)
        } else {
            drawSegment(previous, point)
        }
        previousDocumentPoint = point
    }

    private fun finishStroke() {
        drawing = false
        previousDocumentPoint = null
        activeAirbrush?.let { stroke ->
            commitAirbrushStroke(stroke)
            activeAirbrush = null
            repaintDocumentRect(
                stroke.minX.toInt().coerceAtLeast(0),
                stroke.minY.toInt().coerceAtLeast(0),
                (stroke.maxX - stroke.minX).toInt().coerceAtLeast(1),
                (stroke.maxY - stroke.minY).toInt().coerceAtLeast(1),
            )
        }
        if (strokeBefore.isEmpty()) return

        val after = linkedMapOf<TileKey, BufferedImage?>()
        for (key in strokeBefore.keys) {
            val tile = tiles[key]
            if (tile != null && isEmpty(tile)) {
                tiles.remove(key)
                after[key] = null
            } else {
                after[key] = tile?.let(::copyImage)
            }
        }
        pushUndo(TileEdit(before = strokeBefore, after = after))
        strokeBefore = linkedMapOf()
    }

    /**
     * Keeps the airbrush as one path while the pointer is down. Rendering a radial stamp for
     * every mouse event made sparse desktop input visibly read as a row of circles. A nested set
     * of continuous strokes gives the same soft falloff without accumulating at sample joins.
     */
    private fun commitAirbrushStroke(stroke: DesktopAirbrushStroke) {
        val minX = floor(stroke.minX).toInt().coerceAtLeast(0)
        val minY = floor(stroke.minY).toInt().coerceAtLeast(0)
        val maxX = ceil(stroke.maxX).toInt().coerceAtMost(documentWidth - 1)
        val maxY = ceil(stroke.maxY).toInt().coerceAtMost(documentHeight - 1)
        if (minX > maxX || minY > maxY) return

        for (tileY in minY / TILE_SIZE..maxY / TILE_SIZE) {
            for (tileX in minX / TILE_SIZE..maxX / TILE_SIZE) {
                val key = TileKey(tileX, tileY)
                val existing = tiles[key]
                if (!strokeBefore.containsKey(key)) strokeBefore[key] = existing?.let(::copyImage)
                val tile = existing ?: newTile().also { tiles[key] = it }
                val g = tile.createGraphics()
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                    paintAirbrushStroke(g, stroke, tileX * TILE_SIZE, tileY * TILE_SIZE)
                } finally {
                    g.dispose()
                }
            }
        }
    }

    private fun drawSegment(from: Point2D.Double, to: Point2D.Double) {
        val radius = max(1f, brushSize) / 2f + 3f
        val minX = floor(min(from.x, to.x) - radius).toInt().coerceAtLeast(0)
        val minY = floor(min(from.y, to.y) - radius).toInt().coerceAtLeast(0)
        val maxX = ceil(max(from.x, to.x) + radius).toInt().coerceAtMost(documentWidth - 1)
        val maxY = ceil(max(from.y, to.y) + radius).toInt().coerceAtMost(documentHeight - 1)
        if (minX > maxX || minY > maxY) return

        for (tileY in minY / TILE_SIZE..maxY / TILE_SIZE) {
            for (tileX in minX / TILE_SIZE..maxX / TILE_SIZE) {
                val key = TileKey(tileX, tileY)
                val existing = tiles[key]
                if (selectedTool == DesktopTool.ERASER && existing == null) continue
                if (!strokeBefore.containsKey(key)) strokeBefore[key] = existing?.let(::copyImage)
                val tile = existing ?: newTile().also { tiles[key] = it }
                drawOnTile(tile, key, from, to)
            }
        }
        repaintDocumentRect(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun drawOnTile(
        tile: BufferedImage,
        key: TileKey,
        from: Point2D.Double,
        to: Point2D.Double,
    ) {
        val offsetX = key.x * TILE_SIZE
        val offsetY = key.y * TILE_SIZE
        val g = tile.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            if (selectedTool == DesktopTool.ERASER) {
                g.composite = AlphaComposite.getInstance(AlphaComposite.DST_OUT, brushOpacity.coerceIn(0f, 1f))
                g.color = Color.BLACK
                g.stroke = BasicStroke(brushSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine(
                    (from.x - offsetX).toInt(), (from.y - offsetY).toInt(),
                    (to.x - offsetX).toInt(), (to.y - offsetY).toInt(),
                )
            } else {
                val width = if (selectedTool == DesktopTool.PENCIL) brushSize * .72f else brushSize
                val alpha = if (selectedTool == DesktopTool.PENCIL) brushOpacity * .82f else brushOpacity
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
                g.color = brushColor
                g.stroke = BasicStroke(width.coerceAtLeast(.75f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine(
                    (from.x - offsetX).toInt(), (from.y - offsetY).toInt(),
                    (to.x - offsetX).toInt(), (to.y - offsetY).toInt(),
                )
            }
        } finally {
            g.dispose()
        }
    }

    private fun paintAirbrushStroke(
        target: Graphics2D,
        stroke: DesktopAirbrushStroke,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ) {
        val g = target.create() as Graphics2D
        try {
            g.translate(-offsetX, -offsetY)
            g.color = stroke.color
            val bands = ceil(stroke.size / AIRBRUSH_BAND_PIXELS).toInt().coerceIn(MIN_AIRBRUSH_BANDS, MAX_AIRBRUSH_BANDS)
            val weightTotal = (1..bands).sumOf { it * it }.toFloat()
            val targetAlpha = stroke.opacity * AIRBRUSH_CENTER_OPACITY

            for (band in 0 until bands) {
                val weight = (band + 1f).pow(2) / weightTotal
                val alpha = 1f - (1f - targetAlpha).pow(weight)
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                val width = (stroke.size * (bands - band) / bands).coerceAtLeast(.75f)
                if (stroke.hasSegment) {
                    g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(stroke.path)
                } else {
                    val radius = width / 2f
                    g.fillOval(
                        floor(stroke.start.x - radius).toInt(),
                        floor(stroke.start.y - radius).toInt(),
                        ceil(radius * 2).toInt(),
                        ceil(radius * 2).toInt(),
                    )
                }
            }
        } finally {
            g.dispose()
        }
    }

    private fun repaintAirbrushSegment(
        from: Point2D.Double,
        to: Point2D.Double,
        stroke: DesktopAirbrushStroke,
    ) {
        val radius = stroke.size / 2f + 2f
        val minX = floor(min(from.x, to.x) - radius).toInt().coerceAtLeast(0)
        val minY = floor(min(from.y, to.y) - radius).toInt().coerceAtLeast(0)
        val maxX = ceil(max(from.x, to.x) + radius).toInt().coerceAtMost(documentWidth - 1)
        val maxY = ceil(max(from.y, to.y) + radius).toInt().coerceAtMost(documentHeight - 1)
        if (minX <= maxX && minY <= maxY) {
            repaintDocumentRect(minX, minY, maxX - minX + 1, maxY - minY + 1)
        }
    }

    private fun handleWheel(event: MouseWheelEvent) {
        if (event.isControlDown || event.isMetaDown) {
            val before = screenToDocument(event.point)
            val factor = Math.pow(1.12, -event.preciseWheelRotation)
            zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            panX = event.x - before.x * zoom
            panY = event.y - before.y * zoom
        } else if (event.isShiftDown) {
            panX -= event.preciseWheelRotation * 34.0
        } else {
            panY -= event.preciseWheelRotation * 34.0
        }
        repaint()
        event.consume()
    }

    private fun pushUndo(edit: TileEdit) {
        if (edit.before.isEmpty()) return
        undo.addLast(edit)
        undoBytes += edit.bytes
        redo.clear()
        trimUndo()
        publishHistory()
    }

    private fun trimUndo() {
        while (undoBytes > MAX_UNDO_BYTES && undo.size > 1) {
            undoBytes -= undo.removeFirst().bytes
        }
    }

    private fun restore(snapshot: Map<TileKey, BufferedImage?>) {
        for ((key, image) in snapshot) {
            if (image == null) tiles.remove(key) else tiles[key] = copyImage(image)
        }
        repaint()
    }

    private fun publishHistory() {
        onHistoryChanged(HistoryState(undo.isNotEmpty(), redo.isNotEmpty()))
    }

    private fun screenToDocument(point: Point): Point2D.Double = Point2D.Double(
        (point.x - panX) / zoom,
        (point.y - panY) / zoom,
    )

    private fun insideDocument(point: Point2D.Double): Boolean =
        point.x >= 0 && point.y >= 0 && point.x < documentWidth && point.y < documentHeight

    private fun repaintDocumentRect(x: Int, y: Int, width: Int, height: Int) {
        val screenX = floor(panX + x * zoom).toInt() - 2
        val screenY = floor(panY + y * zoom).toInt() - 2
        val screenWidth = ceil(width * zoom).toInt() + 4
        val screenHeight = ceil(height * zoom).toInt() + 4
        repaint(screenX, screenY, screenWidth, screenHeight)
    }

    private fun shortcutDown(event: KeyEvent): Boolean =
        event.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK) != 0

    private data class TileKey(val x: Int, val y: Int)

    private class DesktopAirbrushStroke(
        val start: Point2D.Double,
        val size: Float,
        val opacity: Float,
        val color: Color,
    ) {
        val path = java.awt.geom.Path2D.Double().apply { moveTo(start.x, start.y) }
        var hasSegment = false
            private set
        private val radius = size / 2f + 2f
        var minX = start.x - radius
            private set
        var minY = start.y - radius
            private set
        var maxX = start.x + radius
            private set
        var maxY = start.y + radius
            private set

        fun append(point: Point2D.Double) {
            path.lineTo(point.x, point.y)
            hasSegment = true
            minX = min(minX, point.x - radius)
            minY = min(minY, point.y - radius)
            maxX = max(maxX, point.x + radius)
            maxY = max(maxY, point.y + radius)
        }
    }

    private data class TileEdit(
        val before: Map<TileKey, BufferedImage?>,
        val after: Map<TileKey, BufferedImage?>,
    ) {
        val bytes: Long = (before.values.count { it != null } + after.values.count { it != null }) *
            TILE_SIZE.toLong() * TILE_SIZE * 4
    }

    companion object {
        private const val TILE_SIZE = 256
        private const val DEFAULT_DOCUMENT_SIZE = 2048
        private const val MIN_DOCUMENT_SIZE = 64
        private const val MAX_DOCUMENT_SIZE = 8192
        private const val MIN_ZOOM = .04
        private const val MAX_ZOOM = 16.0
        private const val MAX_UNDO_BYTES = 128L * 1024 * 1024
        private const val AIRBRUSH_BAND_PIXELS = 5f
        private const val MIN_AIRBRUSH_BANDS = 8
        private const val MAX_AIRBRUSH_BANDS = 32
        private const val AIRBRUSH_CENTER_OPACITY = .75f
        private val WORKSPACE_COLOR = Color(22, 23, 26)

        private fun newTile() = BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB)

        private fun copyImage(source: BufferedImage): BufferedImage {
            val copy = newTile()
            val g = copy.createGraphics()
            try {
                g.composite = AlphaComposite.Src
                g.drawImage(source, 0, 0, null)
            } finally {
                g.dispose()
            }
            return copy
        }

        private fun isEmpty(image: BufferedImage): Boolean {
            val pixels = image.getRGB(0, 0, TILE_SIZE, TILE_SIZE, null, 0, TILE_SIZE)
            return pixels.none { it ushr 24 != 0 }
        }
    }
}
