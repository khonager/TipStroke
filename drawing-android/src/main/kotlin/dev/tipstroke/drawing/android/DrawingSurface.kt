package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.input.motionprediction.MotionEventPredictor
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.Rect
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.model.*
import kotlin.math.*

enum class SelectionTool { RECTANGLE, LASSO }

@OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)
class DrawingSurface @JvmOverloads constructor(context: Context, attrs: android.util.AttributeSet? = null) : FrameLayout(context, attrs) {
    val settings = CanvasSettings()
    private lateinit var rasterView: RasterCanvasView
    private lateinit var colorLoupe: ColorPickerLoupeView
    private var layerStack: LayerStack = createLayerStack(2048, 2048)

    private fun createLayerStack(width: Int, height: Int): LayerStack = LayerStack(context.contentResolver, width, height) {
        if (::rasterView.isInitialized) rasterView.invalidate()
        notifyLayers()
        notifyVisiblePalette()
    }
    private val liveView = InProgressStrokesView(context)
    private val tipStrokeInkBrushes = TipStrokeInkBrushes()
    private val inkRenderer by lazy { CanvasStrokeRenderer.create(tipStrokeInkBrushes.textureStore) }
    private var predictor: MotionEventPredictor? = null
    private val pendingSamples = mutableMapOf<Int, MutableList<StrokeSample>>()
    private data class StrokeTarget(val store: TileStore, val image: ImageLayerRuntime? = null)
    private data class PendingCommit(val stroke: CompletedStroke, val target: StrokeTarget)
    private val pendingTargets = mutableMapOf<Int, StrokeTarget>()
    private var customPreviewStyle: StrokeStyle? = null
    private var airbrushPreviewStyle: StrokeStyle? = null
    private val finishedSamples = ArrayDeque<PendingCommit>()
    private var activeStylusId: Int? = null
    private var gestureStart = emptyMap<Int, android.graphics.PointF>()
    private var lastGestureCentroid = android.graphics.PointF()
    private var lastGestureSpan = 0f
    private var lastGestureAngle = 0f
    private var gestureMoved = false
    private var gestureDownAt = 0L
    private val gestureHandler = Handler(Looper.getMainLooper())
    private var holdRunnable: Runnable? = null
    private var holdTriggered = false
    private var colorPicking = false
    private var pendingPickedColor: RgbaColor? = null
    private var singleStart = android.graphics.PointF()
    private var singleLast = android.graphics.PointF()
    private var singleLastDocument = android.graphics.PointF()
    private var smudgeTarget: RasterLayerRuntime? = null
    private var lastSampleAt = 0L
    private var frameCount = 0
    private var fpsWindowAt = SystemClock.elapsedRealtimeNanos()
    private var fps = 0f
    var diagnosticsListener: ((CanvasDiagnostics) -> Unit)? = null
    var historyListener: ((Boolean, Boolean) -> Unit)? = null
    var layersListener: ((List<LayerSummary>, LayerId, Map<LayerId, android.graphics.Bitmap>) -> Unit)? = null
    var colorPickedListener: ((RgbaColor) -> Unit)? = null
    var visiblePaletteListener: ((List<RgbaColor>) -> Unit)? = null
    var drawnColorListener: ((RgbaColor) -> Unit)? = null
    private var paletteColorCount = 3
    var stylusButtonListener: ((StylusButton) -> Unit)? = null
    private var pressedStylusButtons = 0
    private var imageTransformMode = false
    private var selectionMode = false
    private var selectionTool = SelectionTool.LASSO
    private val selectionPoints = mutableListOf<Point>()
    private var selectionRegion: SelectionRegion? = null
    private var selectionMoveMode = false
    private var selectionMoveStart: android.graphics.PointF? = null
    var selectionListener: ((Boolean, Boolean) -> Unit)? = null

    init {
        setWillNotDraw(false); setBackgroundColor(Color.rgb(23, 24, 27)); isMotionEventSplittingEnabled = false
        rasterView = RasterCanvasView(context, layerStack)
        colorLoupe = ColorPickerLoupeView(context, rasterView)
        addView(rasterView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        liveView.textureBitmapStore = tipStrokeInkBrushes.textureStore
        addView(liveView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(colorLoupe, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Robolectric cannot load Ink's Android native library; real devices eagerly warm it.
        if (!Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) {
            tipStrokeInkBrushes.prewarm()
            liveView.eagerInit()
        }
        liveView.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                strokes.forEach { (_, inkStroke) ->
                    val pending = finishedSamples.removeFirstOrNull() ?: return@forEach
                    val stroke = pending.stroke
                    val store = pending.target.store
                    if (stroke.style.blend == BlendBehavior.PAINT) {
                        store.commitInk(stroke, inkStroke, inkRenderer)
                    } else {
                        store.commit(stroke)
                    }
                    if (pending.target.image == null && stroke.style.blend == BlendBehavior.PAINT) {
                        drawnColorListener?.invoke(stroke.style.color)
                    }
                }
                rasterView.invalidate()
                liveView.removeFinishedStrokes(strokes.keys)
                notifyHistory()
                notifyVisiblePalette()
                notifyLayers()
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (predictor == null) predictor = runCatching { MotionEventPredictor.newInstance(this) }.getOrNull()
    }

    fun undo() { layerStack.selectedStore()?.history?.let { if (it.undo()) { rasterView.invalidate(); notifyHistory(); notifyVisiblePalette(); notifyLayers() } } }
    fun redo() { layerStack.selectedStore()?.history?.let { if (it.redo()) { rasterView.invalidate(); notifyHistory(); notifyVisiblePalette(); notifyLayers() } } }
    fun resetView() = rasterView.fitCanvas()
    fun addPaintLayer() { layerStack.addRaster(); notifyLayers(); notifyHistory() }
    fun addImage(uri: android.net.Uri): Result<Unit> = layerStack.addImage(uri).map { notifyLayers(); notifyHistory() }
    fun selectLayer(id: LayerId) { layerStack.select(id); notifyLayers(); notifyHistory() }
    fun setSelectedLayerOpacity(value: Float) { layerStack.setOpacity(value); notifyLayers() }
    fun toggleLayerVisibility(id: LayerId) { layerStack.toggleVisible(id); notifyLayers() }
    fun setPaletteColorCount(count: Int) {
        val safeCount = count.coerceIn(1, 8)
        if (paletteColorCount != safeCount) paletteColorCount = safeCount
        notifyVisiblePalette()
    }
    fun setSelectedImageScale(value: Float) { layerStack.setImageScale(value); notifyLayers() }
    fun setImageTransformMode(enabled: Boolean) {
        imageTransformMode = enabled && layerStack.selectedImage() != null
        rasterView.showImageTransformBounds = imageTransformMode
    }
    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (enabled) { selectionMoveMode = false; setImageTransformMode(false) }
        selectionListener?.invoke(selectionMode, selectionRegion != null)
    }
    fun setSelectionTool(tool: SelectionTool) {
        selectionTool = tool
        selectionMoveMode = false
        selectionMode = true
        selectionListener?.invoke(true, selectionRegion != null)
    }
    fun setSelectionMoveMode(enabled: Boolean) {
        selectionMoveMode = enabled && selectionRegion != null && layerStack.selectedRaster() != null
        if (selectionMoveMode) selectionMode = false
        selectionListener?.invoke(selectionMode, selectionRegion != null)
    }
    fun clearSelection() {
        selectionRegion = null
        selectionPoints.clear()
        selectionMoveStart = null
        selectionMoveMode = false
        rasterView.selectionRegion = null
        rasterView.selectionDraftPoints = emptyList()
        rasterView.selectionPreviewOffset = Point(0f, 0f)
        selectionListener?.invoke(selectionMode, false)
    }
    fun selectAll() {
        selectionRegion = SelectionRegion.rectangle(Rect(0f, 0f, layerStack.canvasWidth.toFloat(), layerStack.canvasHeight.toFloat()))
        rasterView.selectionRegion = selectionRegion
        selectionListener?.invoke(selectionMode, true)
    }
    fun fitSelectedImage() { layerStack.fitSelectedImage(); notifyLayers() }
    fun originalSizeSelectedImage() { layerStack.originalSizeSelectedImage(); notifyLayers() }
    fun moveSelectedLayer(towardFront: Boolean) { layerStack.moveSelected(towardFront); notifyLayers() }
    fun deleteSelectedLayer() { if (layerStack.deleteSelected()) { notifyLayers(); notifyHistory() } }
    fun publishLayers() { notifyLayers(); notifyHistory(); notifyVisiblePalette() }
    fun configureBlank(widthPx: Int, heightPx: Int) {
        require(widthPx in 64..8192 && heightPx in 64..8192)
        layerStack = createLayerStack(widthPx, heightPx)
        clearSelection()
        rasterView.layerStack = layerStack
        rasterView.fitCanvas()
        publishLayers()
    }

    fun loadProject(library: DrawingLibrary, id: String, onComplete: (Result<Unit>) -> Unit) {
        ProjectPersistence.executor.execute {
            val loaded = ProjectPersistence.load(library.projectDirectory(id))
            ProjectPersistence.mainHandler.post {
                val result = loaded.map { project ->
                    val replacement = createLayerStack(project.widthPx, project.heightPx)
                    layerStack = replacement
                    clearSelection()
                    rasterView.layerStack = replacement
                    replacement.replaceWith(project)
                    rasterView.fitCanvas()
                    publishLayers()
                }
                onComplete(result)
            }
        }
    }

    fun saveProject(library: DrawingLibrary, id: String, name: String, onComplete: (Result<DrawingSummary>) -> Unit = {}) {
        val snapshot = layerStack.snapshot()
        ProjectPersistence.executor.execute {
            val result = try { ProjectPersistence.save(context.contentResolver, library, id, name, snapshot) }
            finally { snapshot.recycle() }
            ProjectPersistence.mainHandler.post { onComplete(result) }
        }
    }

    fun exportDrawing(uri: android.net.Uri, format: ExportFormat, quality: Int, scale: Float, transparent: Boolean, onComplete: (Result<Unit>) -> Unit) {
        val snapshot = layerStack.snapshot()
        ProjectPersistence.executor.execute {
            val result = try { ProjectPersistence.export(context.contentResolver, uri, snapshot, format, quality, scale, transparent) }
            finally { snapshot.recycle() }
            ProjectPersistence.mainHandler.post { onComplete(result) }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        predictor?.record(event)
        if (eventHasStylus(event)) updateStylusButtons(event.buttonState)
        if (selectionMoveMode) return handleSelectionMove(event)
        if (selectionMode) return handleSelection(event)
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val toolType = event.getToolType(actionIndex)
        val stylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        return if (stylus || activeStylusId != null) handleStylus(event) else handleTouchGesture(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (eventHasStylus(event)) {
            updateStylusButtons(event.buttonState)
            if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun eventHasStylus(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { isStylus(event, it) }

    private fun updateStylusButtons(buttonState: Int) {
        val pressed = StylusButtons.pressed(buttonState)
        val newPresses = pressed and pressedStylusButtons.inv()
        pressedStylusButtons = pressed
        if (newPresses and 1 != 0) stylusButtonListener?.invoke(StylusButton.PRIMARY)
        if (newPresses and 2 != 0) stylusButtonListener?.invoke(StylusButton.SECONDARY)
    }

    private fun handleStylus(event: MotionEvent): Boolean {
        val pointerId = activeStylusId ?: event.getPointerId(event.actionIndex)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!isStylus(event, event.actionIndex)) return true
                cancelColorPick()
                val baseStyle = currentStyle(event, event.actionIndex)
                val target = when (val selected = layerStack.selected()) {
                    is RasterLayerRuntime -> StrokeTarget(selected.tiles)
                    is ImageLayerRuntime -> if (baseStyle.blend == BlendBehavior.ERASE) StrokeTarget(selected.mask, selected) else return true
                }
                requestUnbufferedDispatch(event)
                activeStylusId = pointerId
                pendingTargets[pointerId] = target
                pendingSamples[pointerId] = mutableListOf(sample(event, event.actionIndex).forTarget(target))
                val style = styleForTarget(baseStyle, target)
                if (target.image != null || style.blend == BlendBehavior.ERASE) {
                    customPreviewStyle = style
                    target.store.beginLiveStroke()
                } else if (style.brush.engine == BrushEngine.AIRBRUSH) {
                    airbrushPreviewStyle = style
                    rasterView.previewStroke = CompletedStroke(pendingSamples.getValue(pointerId).toList(), style)
                } else {
                    liveView.startStroke(event, pointerId, createInkBrush(), rasterView.viewToDocumentMatrix(), Matrix())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    val list = pendingSamples[pointerId] ?: return true
                    val additions = buildList {
                        for (historyIndex in 0 until event.historySize) add(sample(event, index, historyIndex).forTarget(pendingTargets.getValue(pointerId)))
                        add(sample(event, index).forTarget(pendingTargets.getValue(pointerId)))
                    }
                    appendSamples(pointerId, list, additions)
                    if (customPreviewStyle != null || airbrushPreviewStyle != null) {
                        Unit
                    } else {
                        val prediction = predictor?.predict()
                        try { liveView.addToStroke(event, pointerId, prediction) } finally { prediction?.recycle() }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.findPointerIndex(pointerId)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && event.flags and MotionEvent.FLAG_CANCELED != 0) {
                    cancelActiveStroke(event, pointerId)
                    return true
                }
                if (index >= 0) pendingSamples[pointerId]?.let { list ->
                    pendingTargets[pointerId]?.let { target -> appendSamples(pointerId, list, listOf(sample(event, index).forTarget(target))) }
                }
                val target = pendingTargets.remove(pointerId)
                pendingSamples.remove(pointerId)?.let { samples ->
                    val style = customPreviewStyle ?: airbrushPreviewStyle ?: currentStyle(event, index.coerceAtLeast(0))
                    if (target != null) {
                        if (customPreviewStyle != null) {
                            if (samples.size == 1) invalidateTarget(target, target.store.appendLiveStroke(CompletedStroke(samples, style)))
                            target.store.finishLiveStroke(CompletedStroke(samples, style))
                            rasterView.invalidate()
                            notifyHistory()
                            notifyVisiblePalette()
                        } else if (airbrushPreviewStyle != null) {
                            target.store.commit(CompletedStroke(samples, style))
                            rasterView.previewStroke = null
                            rasterView.invalidate()
                            notifyHistory()
                            drawnColorListener?.invoke(style.color)
                            notifyVisiblePalette()
                        } else finishedSamples += PendingCommit(CompletedStroke(samples, style), target)
                    }
                }
                if (customPreviewStyle != null) {
                    customPreviewStyle = null
                } else if (airbrushPreviewStyle != null) {
                    airbrushPreviewStyle = null
                } else liveView.finishStroke(event, pointerId)
                activeStylusId = null
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelActiveStroke(event, pointerId)
            }
        }
        emitDiagnostics(event, event.actionIndex.coerceIn(0, event.pointerCount - 1))
        return true
    }

    private fun handleTouchGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownAt = SystemClock.uptimeMillis()
                gestureMoved = false
                holdTriggered = false
                singleStart = android.graphics.PointF(event.x, event.y)
                singleLast = android.graphics.PointF(event.x, event.y)
                singleLastDocument = rasterView.screenToDocument(event.x, event.y)
                if (!isTransformingImage() && settings.gestures.oneFingerDrag == FingerAction.SMUDGE) {
                    smudgeTarget = layerStack.selectedRaster()?.also { it.tiles.beginSmudge() }
                }
                if (!isTransformingImage()) scheduleHold(singleLastDocument)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelHold()
                cancelColorPick()
                smudgeTarget?.tiles?.finishSmudge(); smudgeTarget = null
                if (event.pointerCount >= 2) beginGesture(event)
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                updateGesture(event)
            } else {
                val distance = hypot(event.x - singleStart.x, event.y - singleStart.y)
                if (distance > ViewConfiguration.get(context).scaledTouchSlop) { gestureMoved = true; cancelHold() }
                val document = rasterView.screenToDocument(event.x, event.y)
                if (colorPicking) {
                    updateColorPick(event.x, event.y, document)
                } else if (isTransformingImage() && gestureMoved) {
                    layerStack.transformSelectedImage(document.x - singleLastDocument.x, document.y - singleLastDocument.y)
                } else when (settings.gestures.oneFingerDrag) {
                    FingerAction.NAVIGATE -> if (gestureMoved) {
                        val old = rasterView.transform
                        rasterView.updateTransform(old.panX + event.x - singleLast.x, old.panY + event.y - singleLast.y, old.scale, old.rotationDegrees)
                    }
                    FingerAction.SMUDGE -> if (gestureMoved) {
                        smudgeTarget?.tiles?.smudge(
                            singleLastDocument.x, singleLastDocument.y, document.x, document.y,
                            settings.sizePx * .7f, settings.gestures.smudgeStrength,
                        )
                        rasterView.invalidate()
                    }
                    FingerAction.PICK_COLOR -> if (gestureMoved) beginOrUpdateColorPick(event.x, event.y, document)
                    else -> Unit
                }
                singleLast = android.graphics.PointF(event.x, event.y)
                singleLastDocument = document
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                cancelHold()
                smudgeTarget?.tiles?.finishSmudge(); smudgeTarget = null
                if (event.actionMasked == MotionEvent.ACTION_UP && colorPicking) {
                    updateColorPick(event.x, event.y, rasterView.screenToDocument(event.x, event.y))
                    commitColorPick()
                    gestureStart = emptyMap()
                    notifyHistory()
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && isTransformingImage()) {
                    rebaseToRemainingPointer(event)
                    notifyLayers()
                    notifyHistory()
                    return true
                }
                if (isTransformingImage()) {
                    notifyLayers()
                } else if (gestureStart.size >= 2 && !gestureMoved && SystemClock.uptimeMillis() - gestureDownAt < 300) {
                    performFingerAction(if (gestureStart.size >= 3) settings.gestures.threeFingerTap else settings.gestures.twoFingerTap, null)
                } else if (event.actionMasked == MotionEvent.ACTION_UP && gestureMoved && !holdTriggered) {
                    when (settings.gestures.oneFingerDrag) {
                        FingerAction.UNDO, FingerAction.REDO -> performFingerAction(settings.gestures.oneFingerDrag, singleLastDocument)
                        else -> Unit
                    }
                }
                // Consume the tap once; subsequent pointer-up events belong to the same gesture.
                gestureStart = emptyMap()
                notifyHistory()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelHold(); cancelColorPick(); smudgeTarget?.tiles?.finishSmudge(); smudgeTarget = null; gestureStart = emptyMap()
            }
        }
        return true
    }

    private fun handleSelection(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = rasterView.screenToDocument(event.x, event.y)
                selectionPoints.clear()
                selectionPoints += Point(point.x, point.y)
                selectionRegion = null
                rasterView.selectionRegion = null
                rasterView.selectionDraftPoints = selectionPoints.toList()
            }
            MotionEvent.ACTION_MOVE -> selectionPoints.firstOrNull()?.let { start ->
                val point = rasterView.screenToDocument(event.x, event.y)
                if (selectionTool == SelectionTool.RECTANGLE) {
                    val bounds = Rect(start.x, start.y, point.x, point.y).normalized()
                    if (bounds.right - bounds.left >= 2f && bounds.bottom - bounds.top >= 2f) {
                        selectionRegion = SelectionRegion.rectangle(bounds)
                        rasterView.selectionRegion = selectionRegion
                    }
                } else {
                    val candidate = Point(point.x, point.y)
                    val previous = selectionPoints.last()
                    val minimum = 2f / rasterView.transform.scale.coerceAtLeast(.08f)
                    if (hypot(candidate.x - previous.x, candidate.y - previous.y) >= minimum) selectionPoints += candidate
                    rasterView.selectionDraftPoints = selectionPoints.toList()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (selectionTool == SelectionTool.LASSO) {
                    val point = rasterView.screenToDocument(event.x, event.y)
                    selectionPoints += Point(point.x, point.y)
                    selectionRegion = selectionPoints.takeIf { it.size >= 3 }?.let(::SelectionRegion)
                }
                rasterView.selectionDraftPoints = emptyList()
                rasterView.selectionRegion = selectionRegion
                selectionPoints.clear()
                if (selectionRegion == null) clearSelection() else selectionListener?.invoke(true, true)
            }
            MotionEvent.ACTION_CANCEL -> clearSelection()
        }
        return true
    }

    private fun handleSelectionMove(event: MotionEvent): Boolean {
        val selection = selectionRegion ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = rasterView.screenToDocument(event.x, event.y)
                selectionMoveStart = point.takeIf { selection.contains(Point(it.x, it.y)) }
            }
            MotionEvent.ACTION_MOVE -> selectionMoveStart?.let { start ->
                val point = rasterView.screenToDocument(event.x, event.y)
                rasterView.selectionPreviewOffset = Point(point.x - start.x, point.y - start.y)
            }
            MotionEvent.ACTION_UP -> selectionMoveStart?.let { start ->
                val point = rasterView.screenToDocument(event.x, event.y)
                val deltaX = point.x - start.x
                val deltaY = point.y - start.y
                layerStack.selectedRaster()?.tiles?.let { store ->
                    rasterView.invalidateTiles(store.moveSelection(selection, deltaX, deltaY))
                    selectionRegion = selection.translated(deltaX, deltaY)
                    rasterView.selectionRegion = selectionRegion
                    notifyHistory()
                    notifyLayers()
                }
                rasterView.selectionPreviewOffset = Point(0f, 0f)
                selectionMoveStart = null
            }
            MotionEvent.ACTION_CANCEL -> {
                rasterView.selectionPreviewOffset = Point(0f, 0f)
                selectionMoveStart = null
            }
        }
        return true
    }

    private fun scheduleHold(documentPoint: android.graphics.PointF) {
        val action = settings.gestures.oneFingerHold
        if (action == FingerAction.DISABLED) return
        holdRunnable = Runnable {
            if (!gestureMoved) {
                holdTriggered = true
                if (action == FingerAction.PICK_COLOR) {
                    beginOrUpdateColorPick(singleLast.x, singleLast.y, rasterView.screenToDocument(singleLast.x, singleLast.y))
                } else performFingerAction(action, documentPoint)
            }
        }.also { gestureHandler.postDelayed(it, settings.gestures.holdDelayMillis) }
    }

    private fun cancelHold() { holdRunnable?.let(gestureHandler::removeCallbacks); holdRunnable = null }

    private fun performFingerAction(action: FingerAction, point: android.graphics.PointF?) {
        when (action) {
            FingerAction.UNDO -> undo()
            FingerAction.REDO -> redo()
            FingerAction.PICK_COLOR -> Unit
            else -> Unit
        }
    }

    private fun beginOrUpdateColorPick(screenX: Float, screenY: Float, document: android.graphics.PointF) {
        colorPicking = true
        updateColorPick(screenX, screenY, document)
    }

    private fun updateColorPick(screenX: Float, screenY: Float, document: android.graphics.PointF) {
        val picked = layerStack.colorAt(document.x, document.y)
        pendingPickedColor = picked
        colorLoupe.showAt(screenX, screenY, picked)
    }

    private fun commitColorPick() {
        pendingPickedColor?.let { picked ->
            settings.color = picked
            colorPickedListener?.invoke(picked)
        }
        cancelColorPick()
    }

    private fun cancelColorPick() {
        colorPicking = false
        pendingPickedColor = null
        colorLoupe.dismiss()
    }

    private fun beginGesture(event: MotionEvent) {
        gestureStart = (0 until event.pointerCount).associate { event.getPointerId(it) to android.graphics.PointF(event.getX(it), event.getY(it)) }
        lastGestureCentroid = centroid(event); lastGestureSpan = span(event); lastGestureAngle = angle(event)
        gestureMoved = false; gestureDownAt = SystemClock.uptimeMillis()
    }

    private fun updateGesture(event: MotionEvent) {
        val center = centroid(event); val newSpan = span(event); val newAngle = angle(event)
        val dx = center.x - lastGestureCentroid.x; val dy = center.y - lastGestureCentroid.y
        val scaleFactor = if (lastGestureSpan > 0f) newSpan / lastGestureSpan else 1f
        val rawAngleDelta = normalizedAngle(newAngle - lastGestureAngle)
        val angleDelta = if (settings.gestures.rotationLocked) 0f else rawAngleDelta
        if (isTransformingImage()) {
            val previousDocument = rasterView.screenToDocument(lastGestureCentroid.x, lastGestureCentroid.y)
            val currentDocument = rasterView.screenToDocument(center.x, center.y)
            layerStack.transformSelectedImage(
                currentDocument.x - previousDocument.x,
                currentDocument.y - previousDocument.y,
                scaleFactor,
                Math.toDegrees(rawAngleDelta.toDouble()).toFloat(),
            )
        } else {
            val old = rasterView.transform
            rasterView.updateTransform(old.panX + dx, old.panY + dy, (old.scale * scaleFactor).coerceIn(.08f, 12f), old.rotationDegrees + Math.toDegrees(angleDelta.toDouble()).toFloat())
            liveView.motionEventToViewTransform = Matrix()
        }
        gestureMoved = gestureMoved || hypot(dx, dy) > 4f || abs(scaleFactor - 1f) > .015f || abs(angleDelta) > .02f
        lastGestureCentroid = center; lastGestureSpan = newSpan; lastGestureAngle = newAngle
        emitDiagnostics(event, 0)
    }

    private fun isTransformingImage() = imageTransformMode && layerStack.selectedImage() != null

    private fun appendSamples(pointerId: Int, samples: MutableList<StrokeSample>, additions: List<StrokeSample>) {
        if (additions.isEmpty()) return
        val previous = samples.lastOrNull()
        samples += additions
        airbrushPreviewStyle?.let { style ->
            rasterView.previewStroke = CompletedStroke(samples.toList(), style)
            return
        }
        val style = customPreviewStyle ?: return
        val target = pendingTargets[pointerId] ?: return
        if (previous != null) {
            val segment = ArrayList<StrokeSample>(additions.size + 1).apply {
                add(previous)
                addAll(additions)
            }
            invalidateTarget(target, target.store.appendLiveStroke(CompletedStroke(segment, style)))
        }
    }

    private fun rebaseToRemainingPointer(event: MotionEvent) {
        val remainingIndex = (0 until event.pointerCount).firstOrNull { it != event.actionIndex } ?: return
        val x = event.getX(remainingIndex)
        val y = event.getY(remainingIndex)
        singleStart = android.graphics.PointF(x, y)
        singleLast = android.graphics.PointF(x, y)
        singleLastDocument = rasterView.screenToDocument(x, y)
        gestureMoved = false
    }

    private fun cancelActiveStroke(event: MotionEvent, pointerId: Int) {
        if (customPreviewStyle == null && airbrushPreviewStyle == null) {
            liveView.cancelStroke(event, pointerId)
        } else if (customPreviewStyle != null) {
            pendingTargets[pointerId]?.let { target -> invalidateTarget(target, target.store.cancelLiveStroke()) }
        }
        customPreviewStyle = null
        airbrushPreviewStyle = null
        rasterView.previewStroke = null
        pendingSamples.remove(pointerId)
        pendingTargets.remove(pointerId)
        activeStylusId = null
    }

    private fun currentStyle(event: MotionEvent, index: Int): StrokeStyle {
        val isHardwareEraser = index in 0 until event.pointerCount && event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        val erasing = settings.erasing || isHardwareEraser
        val brush = if (erasing) settings.brush.copy(hardness = settings.eraserHardness) else settings.brush
        return StrokeStyle(brush, settings.sizePx, settings.opacity, settings.color,
            if (erasing) BlendBehavior.ERASE else BlendBehavior.PAINT, selectionRegion)
    }

    private fun styleForTarget(style: StrokeStyle, target: StrokeTarget): StrokeStyle {
        val image = target.image ?: return style
        val localSelection = style.selection?.let { selection -> imageLocalSelection(image, selection) }
        return style.copy(
            brush = style.brush.copy(engine = BrushEngine.AIRBRUSH),
            sizePx = style.sizePx / image.transform.scale.coerceAtLeast(.02f),
            opacity = 1f,
            color = RgbaColor(1f, 1f, 1f),
            blend = BlendBehavior.PAINT,
            selection = localSelection,
        )
    }

    private fun StrokeSample.forTarget(target: StrokeTarget): StrokeSample {
        val image = target.image ?: return this
        val local = layerStack.imagePointFromDocument(image, android.graphics.PointF(position.x, position.y))
        return copy(position = Point(local.x, local.y))
    }

    private fun imageLocalSelection(image: ImageLayerRuntime, selection: SelectionRegion) = SelectionRegion(selection.points.map { point ->
        val local = layerStack.imagePointFromDocument(image, android.graphics.PointF(point.x, point.y))
        Point(local.x, local.y)
    })

    private fun invalidateTarget(target: StrokeTarget, dirty: Set<dev.tipstroke.core.geometry.TileCoordinate>) {
        if (target.image == null) rasterView.invalidateTiles(dirty) else rasterView.invalidate()
    }

    private fun createInkBrush(): Brush {
        val c = settings.color
        val alpha = (settings.opacity * c.alpha * 255).roundToInt().coerceIn(1, 255)
        val argb = Color.argb(alpha, (c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())
        val family = tipStrokeInkBrushes.familyFor(settings.brush)
        val liveColor = if (settings.erasing) Color.WHITE else argb
        val epsilon = when (settings.brush.engine) {
            // At 1200% zoom these remain below one screen pixel, while avoiding
            // needlessly dense meshes that cannot add detail to the raster canvas.
            BrushEngine.PENCIL -> .04f
            BrushEngine.AIRBRUSH -> .08f
            BrushEngine.INK -> .1f
        }
        return Brush.createWithColorIntArgb(family, liveColor, settings.sizePx.coerceAtLeast(1f), epsilon)
    }

    private fun sample(event: MotionEvent, index: Int, historyIndex: Int? = null): StrokeSample {
        val x = historyIndex?.let { event.getHistoricalX(index, it) } ?: event.getX(index)
        val y = historyIndex?.let { event.getHistoricalY(index, it) } ?: event.getY(index)
        val point = rasterView.screenToDocument(x, y)
        val pressure = historyIndex?.let { event.getHistoricalPressure(index, it) } ?: event.getPressure(index)
        return StrokeSample(event.getPointerId(index), Point(point.x, point.y), pressure.coerceIn(.01f, 1f),
            event.getAxisValue(MotionEvent.AXIS_TILT, index), event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index),
            (((historyIndex?.let { event.getHistoricalEventTime(it) } ?: event.eventTime) - event.downTime) * 1_000_000L).coerceAtLeast(0),
            event.buttonState, pointerKind(event.getToolType(index)))
    }

    private fun isStylus(event: MotionEvent, index: Int) = event.getToolType(index) in intArrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)
    private fun pointerKind(type: Int) = when (type) { MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS; MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER_STYLUS; MotionEvent.TOOL_TYPE_FINGER -> PointerKind.FINGER; MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE; else -> PointerKind.UNKNOWN }
    private fun centroid(e: MotionEvent) = android.graphics.PointF((0 until e.pointerCount).sumOf { e.getX(it).toDouble() }.toFloat() / e.pointerCount, (0 until e.pointerCount).sumOf { e.getY(it).toDouble() }.toFloat() / e.pointerCount)
    private fun span(e: MotionEvent): Float { val a = 0; val b = 1; return hypot(e.getX(b) - e.getX(a), e.getY(b) - e.getY(a)) }
    private fun angle(e: MotionEvent) = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
    private fun normalizedAngle(value: Float): Float { var v = value; while (v > Math.PI) v -= (2 * Math.PI).toFloat(); while (v < -Math.PI) v += (2 * Math.PI).toFloat(); return v }

    private fun notifyHistory() {
        val history = layerStack.selectedStore()?.history
        historyListener?.invoke(history?.canUndo() == true, history?.canRedo() == true)
    }
    private fun notifyLayers() {
        layersListener?.invoke(layerStack.summariesFrontToBack(), layerStack.selectedId, layerStack.previewsFrontToBack(96))
    }
    private fun notifyVisiblePalette() {
        visiblePaletteListener?.invoke(layerStack.visiblePalette(paletteColorCount))
    }
    private fun emitDiagnostics(event: MotionEvent, index: Int) {
        val now = SystemClock.elapsedRealtimeNanos(); frameCount++
        if (now - fpsWindowAt > 500_000_000L) { fps = frameCount * 1_000_000_000f / (now - fpsWindowAt); frameCount = 0; fpsWindowAt = now }
        val delta = now - lastSampleAt; lastSampleAt = now
        diagnosticsListener?.invoke(CanvasDiagnostics(fps, event.getPressure(index), event.getAxisValue(MotionEvent.AXIS_TILT, index),
            pointerKind(event.getToolType(index)).name, if (delta > 0) 1_000_000_000f / delta else 0f,
            rasterView.transform.scale, layerStack.allocatedTiles(), layerStack.lastDirtyTiles(), layerStack.undoBytes()))
    }
}
