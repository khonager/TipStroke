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

internal fun galleryQuarterTurns(rotationDegrees: Float): Int = (rotationDegrees / 90f).roundToInt().mod(4)

@OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)
class DrawingSurface @JvmOverloads constructor(context: Context, attrs: android.util.AttributeSet? = null) : FrameLayout(context, attrs) {
    val settings = CanvasSettings()
    private lateinit var rasterView: RasterCanvasView
    private lateinit var colorLoupe: ColorPickerLoupeView
    private var layerStack: LayerStack = createLayerStack(2048, 2048)
    private var animation: AnimationRuntime? = null
    private var animationPlaying = false
    private data class MotionRecording(
        val layerId: LayerId, val start: Int, val end: Int, val timing: RecordingTiming,
        val samples: MutableList<Pair<Long, ImageTransform>> = mutableListOf(),
    )
    private data class StrokeRecording(
        val layerId: LayerId, val start: Int, val startedAt: Long,
        val downAt: Long = 0L, var advancedTo: Int = start,
    )
    private var motionRecording: MotionRecording? = null
    private var strokeRecording: StrokeRecording? = null
    private var strokeDownAt = 0L
    private var playbackStartedAt = 0L
    private val playbackStep = object : Runnable {
        override fun run() {
            val active = animation ?: return
            if (!animationPlaying) return
            val tick = ((SystemClock.uptimeMillis() - playbackStartedAt) * active.fps / 1000L)
            strokeRecording?.let { recording ->
                advanceLiveRecording(recording, recording.start + tick.toInt())
                gestureHandler.postDelayed(this, 16L)
                return
            }
            val timeline = active.timeline
            if (active.playbackMode == PlaybackMode.ONCE && tick >= timeline.totalTicks) {
                stopAnimationPlayback()
                return
            }
            val index = timeline.frameAtPlaybackTick(tick)
            if (index != active.selectedIndex) selectAnimationFrame(index, fromPlayback = true)
            gestureHandler.postDelayed(this, 16L)
        }
    }

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
    private data class PendingCommit(
        val stroke: CompletedStroke,
        val target: StrokeTarget,
        val rasterizedLive: Boolean = false,
        val reveal: StrokeRecording? = null,
    )
    private val pendingTargets = mutableMapOf<Int, StrokeTarget>()
    private var customPreviewStyle: StrokeStyle? = null
    private var rasterPreviewStyle: StrokeStyle? = null
    private var pencilPreviewStyle: StrokeStyle? = null
    private var pencilPreviewStore: TileStore? = null
    private val finishedSamples = ArrayDeque<PendingCommit>()
    private var activeDrawingPointerId: Int? = null
    private var activeMousePan = false
    private var lastMousePan = android.graphics.PointF()
    private var gestureStart = emptyMap<Int, android.graphics.PointF>()
    private var lastGestureCentroid = android.graphics.PointF()
    private var lastGestureSpan = 0f
    private var lastGestureAngle = 0f
    private var gestureMoved = false
    private var gestureDownAt = 0L
    private val gestureHandler = Handler(Looper.getMainLooper())
    private val hideBrushPreview = Runnable { rasterView.adjustmentPreviewStroke = null }
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
    private var publishedZoomPercent = 100
    private val memoryBudgetAdvisor = MemoryBudgetAdvisor(context)
    private var lastDiagnostics = CanvasDiagnostics()
    var diagnosticsListener: ((CanvasDiagnostics) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(lastDiagnostics)
        }
    var historyListener: ((Boolean, Boolean) -> Unit)? = null
    var layersListener: ((List<LayerSummary>, LayerId, Set<LayerId>, Map<LayerId, android.graphics.Bitmap>) -> Unit)? = null
    var animationListener: ((AnimationUiState?) -> Unit)? = null
        set(value) { field = value; value?.invoke(animation?.state(animationPlaying, recordingKind())) }
    var colorPickedListener: ((RgbaColor) -> Unit)? = null
    var visiblePaletteListener: ((List<RgbaColor>) -> Unit)? = null
    var drawnColorListener: ((RgbaColor) -> Unit)? = null
    private var paletteColorCount = 3
    private var originalImageColors = false
    var stylusButtonListener: ((StylusButton) -> Unit)? = null
    var chromeOcclusionListener: ((Boolean) -> Unit)? = null
    private var chromeOccludedByStroke = false
    private var chromeTopInsetPx = 0f
    private var chromeBottomInsetPx = 0f
    private var chromeApproachMarginPx = 0f
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
        rasterView.transformChangedListener = { transform ->
            val percent = (transform.scale * 100f).roundToInt()
            lastDiagnostics = lastDiagnostics.copy(zoom = transform.scale)
            if (percent != publishedZoomPercent) {
                publishedZoomPercent = percent
                diagnosticsListener?.invoke(lastDiagnostics)
            }
        }
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
                var committedAny = false
                strokes.forEach { (_, inkStroke) ->
                    val pending = finishedSamples.removeFirstOrNull() ?: return@forEach
                    if (pending.rasterizedLive) return@forEach
                    val stroke = pending.stroke
                    val store = pending.target.store
                    committedAny = true
                    if (pending.reveal != null) {
                        finishStrokeRecording(pending.reveal, stroke)
                    } else if (stroke.style.blend == BlendBehavior.PAINT) {
                        val rasterStroke = if (pressureBehaviorEnabled(stroke.style.brush.pressureToOpacity)) {
                            Stroke(createInkBrush(stroke.style, includePressureOpacity = false), inkStroke.inputs)
                        } else inkStroke
                        store.commitInk(stroke, rasterStroke, inkRenderer)
                    } else {
                        store.commit(stroke)
                    }
                    if (pending.target.image == null && stroke.style.blend == BlendBehavior.PAINT) {
                        drawnColorListener?.invoke(stroke.style.color)
                    }
                }
                rasterView.invalidate()
                liveView.removeFinishedStrokes(strokes.keys)
                if (committedAny) {
                    notifyHistory()
                    notifyVisiblePalette()
                    notifyLayers()
                }
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (predictor == null) predictor = runCatching { MotionEventPredictor.newInstance(this) }.getOrNull()
    }

    override fun onDetachedFromWindow() {
        stopAnimationPlayback()
        gestureHandler.removeCallbacks(hideBrushPreview)
        rasterView.stylusHoverPreview = null
        super.onDetachedFromWindow()
    }

    fun undo() { layerStack.selectedStore()?.history?.let { if (it.undo()) { rasterView.invalidate(); notifyHistory(); notifyVisiblePalette(); notifyLayers() } } }
    fun redo() { layerStack.selectedStore()?.history?.let { if (it.redo()) { rasterView.invalidate(); notifyHistory(); notifyVisiblePalette(); notifyLayers() } } }
    fun resetView() = rasterView.fitCanvas()
    val canvasWidthPx: Int get() = layerStack.canvasWidth
    val canvasHeightPx: Int get() = layerStack.canvasHeight

    /** Edge deltas are in document pixels: positive adds paper, negative crops it. */
    fun resizeCanvas(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val width = layerStack.canvasWidth.toLong() + left + right
        val height = layerStack.canvasHeight.toLong() + top + bottom
        if (width !in 16L..8192L || height !in 16L..8192L || activeDrawingPointerId != null ||
            pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty() || motionRecording != null || strokeRecording != null
        ) return false
        stopAnimationPlayback()
        clearSelection()
        layerStack.resizeCanvas(width.toInt(), height.toInt(), left, top, animation)
        rasterView.fitCanvas(rasterView.transform.rotationDegrees)
        notifyHistory()
        notifyLayers()
        notifyVisiblePalette()
        publishAnimation()
        return true
    }
    fun showBrushAdjustmentPreview() {
        gestureHandler.removeCallbacks(hideBrushPreview)
        if (activeDrawingPointerId != null || width == 0 || height == 0) return
        val center = rasterView.screenToDocument(width / 2f, height / 2f)
        val erasing = settings.erasing
        val previewBrush = settings.brush.copy(
            hardness = if (erasing) settings.eraserHardness else settings.brush.hardness,
        )
        val previewStyle = StrokeStyle(
            brush = previewBrush,
            sizePx = settings.sizePx,
            opacity = settings.opacity,
            color = if (erasing) RgbaColor(1f, 1f, 1f) else settings.color,
            // A neutral paint stamp keeps an eraser preview visible without touching artwork.
            blend = BlendBehavior.PAINT,
        )
        rasterView.adjustmentPreviewStroke = CompletedStroke(
            listOf(StrokeSample(0, Point(center.x, center.y), 1f, 0f, 0f, 0L, 0, PointerKind.UNKNOWN)),
            previewStyle,
        )
    }
    fun hideBrushAdjustmentPreview() {
        gestureHandler.removeCallbacks(hideBrushPreview)
        gestureHandler.postDelayed(hideBrushPreview, 300L)
    }
    fun setChromeOcclusionInsets(topPx: Float, bottomPx: Float, approachMarginPx: Float) {
        chromeTopInsetPx = topPx.coerceAtLeast(0f)
        chromeBottomInsetPx = bottomPx.coerceAtLeast(0f)
        chromeApproachMarginPx = approachMarginPx.coerceAtLeast(0f)
    }
    fun addPaintLayer() { layerStack.addRaster(); animation?.register(layerStack.selected()); notifyLayers(); notifyHistory(); publishAnimation() }
    fun duplicateSelectedLayers() {
        if (layerStack.duplicateSelected().isNotEmpty()) {
            layerStack.layers.forEach { animation?.register(it) }
            notifyLayers(); notifyHistory(); notifyVisiblePalette(); publishAnimation()
        }
    }
    fun addImage(uri: android.net.Uri): Result<Unit> = layerStack.addImage(uri).map {
        animation?.register(layerStack.selected()); notifyLayers(); notifyHistory(); publishAnimation()
    }
    fun selectLayer(id: LayerId) { layerStack.select(id); notifyLayers(); notifyHistory() }
    fun toggleLayerSelection(id: LayerId) { layerStack.toggleAdditionalSelection(id); notifyLayers(); notifyHistory() }
    fun setSelectedLayerOpacity(value: Float) { layerStack.setOpacity(value); notifyLayers() }
    fun renameSelectedLayer(name: String) { layerStack.renameSelected(name); notifyLayers() }
    fun toggleLayerVisibility(id: LayerId) { layerStack.toggleVisible(id); notifyLayers() }
    fun setPaletteColorCount(count: Int) {
        val safeCount = count.coerceIn(1, 8)
        if (paletteColorCount != safeCount) paletteColorCount = safeCount
        notifyVisiblePalette()
    }
    fun setOriginalImageColors(enabled: Boolean) {
        if (originalImageColors == enabled) return
        originalImageColors = enabled
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
        selectionMoveMode = enabled && selectionRegion != null &&
            (layerStack.selectedRasters().isNotEmpty() || layerStack.selectedImages().isNotEmpty())
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
    fun duplicateSelection() {
        val selection = selectionRegion ?: return
        if (layerStack.duplicateSelection(selection).isNotEmpty()) {
            layerStack.layers.forEach { animation?.register(it) }
            rasterView.invalidate()
            notifyLayers()
            notifyHistory()
            notifyVisiblePalette()
        }
    }
    fun moveSelectionToNewLayer() {
        val selection = selectionRegion ?: return
        val sources = layerStack.selectedRasters().map { it.tiles }
        if (sources.isEmpty()) return
        if (layerStack.duplicateSelection(selection).isEmpty()) return
        layerStack.layers.forEach { animation?.register(it) }
        sources.forEach { rasterView.invalidateTiles(it.cutSelection(selection)) }
        rasterView.invalidate(); notifyLayers(); notifyHistory(); notifyVisiblePalette(); publishAnimation()
    }
    fun fitSelectedImage() { layerStack.fitSelectedImage(); notifyLayers() }
    fun originalSizeSelectedImage() { layerStack.originalSizeSelectedImage(); notifyLayers() }
    fun moveSelectedLayer(towardFront: Boolean) { layerStack.moveSelected(towardFront); notifyLayers() }
    fun deleteSelectedLayer() { if (layerStack.deleteSelected()) { animation?.unregisterMissingLayers(); notifyLayers(); notifyHistory(); publishAnimation() } }
    fun publishLayers() { notifyLayers(); notifyHistory(); notifyVisiblePalette() }
    fun enableAnimation(existingAsBackground: Boolean) {
        if (animation != null || activeDrawingPointerId != null) return
        animation = AnimationRuntime(layerStack, existingAsBackground)
        rasterView.animation = animation
        rasterView.invalidate()
        publishAnimation()
    }
    fun selectAnimationFrame(index: Int) = selectAnimationFrame(index, fromPlayback = false)
    private fun selectAnimationFrame(index: Int, fromPlayback: Boolean) {
        val active = animation ?: return
        if (activeDrawingPointerId != null) return
        if (!fromPlayback) stopAnimationPlayback()
        if (index !in active.frames.indices) return
        active.select(index)
        clearSelection()
        rasterView.invalidate()
        notifyLayers(); notifyHistory(); notifyVisiblePalette(); publishAnimation()
    }
    fun addAnimationFrame() {
        val active = animation ?: return
        stopAnimationPlayback()
        active.addBlankFrame()
        rasterView.invalidate(); notifyLayers(); notifyHistory(); publishAnimation()
    }
    fun duplicateAnimationFrame() {
        val active = animation ?: return
        stopAnimationPlayback()
        active.duplicateFrame()
        rasterView.invalidate(); notifyLayers(); notifyHistory(); publishAnimation()
    }
    fun deleteAnimationFrame() {
        val active = animation ?: return
        stopAnimationPlayback()
        active.deleteFrame()
        rasterView.invalidate(); notifyLayers(); notifyHistory(); publishAnimation()
    }
    fun moveAnimationFrame(delta: Int) {
        val active = animation ?: return
        stopAnimationPlayback()
        active.moveFrame(active.selectedIndex, active.selectedIndex + delta)
        rasterView.invalidate(); publishAnimation()
    }
    fun setAnimationExposure(value: Int) { animation?.setExposure(value); publishAnimation() }
    fun setAnimationFps(value: Int) {
        if (strokeRecording != null) stopAnimationPlayback()
        animation?.fps = value.coerceIn(1, 60)
        publishAnimation()
    }
    fun setAnimationPlaybackMode(mode: PlaybackMode) { animation?.playbackMode = mode; publishAnimation() }
    fun setOnionSkin(before: Int, after: Int, opacity: Float) {
        animation?.apply {
            onionBefore = before.coerceIn(0, 6)
            onionAfter = after.coerceIn(0, 6)
            onionOpacity = opacity.coerceIn(0f, 1f)
        }
        rasterView.invalidate(); publishAnimation()
    }
    fun setAnimationBackground(layerId: LayerId, background: Boolean) {
        animation?.setBackground(layerId, background)
        rasterView.invalidate(); notifyLayers(); publishAnimation()
    }
    fun copyCelToNewLayer(move: Boolean) {
        if (animation?.copySelectedRasterCelToNewLayer(move) == null) return
        rasterView.invalidate(); notifyLayers(); notifyHistory(); publishAnimation()
    }
    fun toggleAnimationPlayback() {
        val active = animation ?: return
        if (animationPlaying) { stopAnimationPlayback(); return }
        if (activeDrawingPointerId != null) return
        animationPlaying = true
        rasterView.animationPlaying = true
        playbackStartedAt = SystemClock.uptimeMillis()
        gestureHandler.post(playbackStep)
        publishAnimation()
    }
    private fun advanceLiveRecording(recording: StrokeRecording, index: Int) {
        val active = animation ?: return
        if (index <= recording.advancedTo) return
        while (recording.advancedTo < index) {
            val previous = recording.advancedTo
            val next = previous + 1
            if (next > active.frames.lastIndex) active.addBlankFrame() else active.select(next)
            active.carryRecordedDrawing(recording.layerId, previous, next)
            recording.advancedTo = next
        }
        rasterView.invalidate()
        notifyLayers(); publishAnimation()
    }
    fun armImageMotionRecording(endIndex: Int, timing: RecordingTiming): Boolean {
        val active = animation ?: return false
        val image = layerStack.selectedImage() ?: return false
        if (endIndex !in (active.selectedIndex + 1)..active.frames.lastIndex || activeDrawingPointerId != null) return false
        stopAnimationPlayback()
        active.setBackground(image.id, false)
        active.makeImageCelAtCurrentFrame(image.id)
        setImageTransformMode(true)
        motionRecording = MotionRecording(image.id, active.selectedIndex, endIndex, timing)
        publishAnimation()
        return true
    }
    fun startLiveDrawingRecording(): Boolean {
        val active = animation ?: return false
        if (layerStack.selectedRaster() == null || settings.erasing || activeDrawingPointerId != null) return false
        stopAnimationPlayback()
        val start = active.selectedIndex
        val layerId = layerStack.addRaster()
        layerStack.renameSelected("Live drawing")
        active.register(layerStack.selected())
        strokeRecording = StrokeRecording(layerId, start, SystemClock.uptimeMillis())
        animationPlaying = true
        rasterView.animationPlaying = true
        playbackStartedAt = strokeRecording!!.startedAt
        gestureHandler.post(playbackStep)
        notifyLayers(); publishAnimation()
        return true
    }
    fun cancelAnimationRecording() {
        motionRecording = null
        strokeRecording = null
        stopAnimationPlayback()
        notifyLayers(); publishAnimation()
    }
    private fun recordingKind(): AnimationRecordingKind? = when {
        motionRecording != null -> AnimationRecordingKind.IMAGE_MOTION
        strokeRecording != null -> AnimationRecordingKind.LIVE_DRAWING
        else -> null
    }
    private fun recordImageSample(eventTime: Long) {
        val recording = motionRecording ?: return
        val image = layerStack.selectedImage() ?: return
        if (image.id == recording.layerId) recording.samples += eventTime to image.transform
    }
    private fun finishImageRecording(eventTime: Long) {
        val recording = motionRecording ?: return
        recordImageSample(eventTime)
        motionRecording = null
        animation?.recordImageMotion(recording.layerId, recording.start, recording.end, recording.timing, recording.samples)
        rasterView.invalidate(); notifyLayers(); publishAnimation()
    }
    private fun finishStrokeRecording(recording: StrokeRecording, stroke: CompletedStroke) {
        val playedThrough = strokeRecording?.takeIf { it.startedAt == recording.startedAt }?.advancedTo
            ?: recording.advancedTo
        animation?.recordLiveStroke(recording.layerId, recording.start, recording.startedAt,
            recording.downAt, playedThrough, stroke)
        rasterView.invalidate(); notifyLayers(); notifyHistory(); publishAnimation()
    }
    private fun stopAnimationPlayback() {
        if (!animationPlaying) return
        animationPlaying = false
        strokeRecording = null
        rasterView.animationPlaying = false
        gestureHandler.removeCallbacks(playbackStep)
        publishAnimation()
    }
    private fun publishAnimation() { animationListener?.invoke(animation?.state(animationPlaying, recordingKind())) }
    fun publishDiagnostics() {
        if (!settings.debug) return
        val memory = memoryBudgetAdvisor.snapshot(
            layerStack.canvasWidth,
            layerStack.canvasHeight,
            layerStack.estimatedDocumentBytes() + (animation?.inactiveTileBytes() ?: 0L),
        )
        publishDiagnostics(lastDiagnostics.withMemory(memory))
    }
    fun configureBlank(widthPx: Int, heightPx: Int) {
        require(widthPx in 16..8192 && heightPx in 16..8192)
        layerStack = createLayerStack(widthPx, heightPx)
        animation = null
        rasterView.animation = null
        clearSelection()
        rasterView.layerStack = layerStack
        rasterView.fitCanvas()
        publishLayers()
        publishAnimation()
    }

    fun setPixelTool(tool: PixelTool?) {
        settings.pixelTool = tool
        rasterView.pixelToolActive = tool != null
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
                    animation = project.animation?.let { saved ->
                        AnimationRuntime(replacement, true, saved.frames, saved.fps, saved.playbackMode).apply { restore(saved) }
                    }
                    rasterView.animation = animation
                    rasterView.fitCanvas(project.galleryRotationQuarterTurns * 90f)
                    publishLayers()
                    publishAnimation()
                }
                onComplete(result)
            }
        }
    }

    fun saveProject(library: DrawingLibrary, id: String, name: String, onComplete: (Result<DrawingSummary>) -> Unit = {}) {
        val snapshot = layerStack.snapshot(galleryQuarterTurns(rasterView.transform.rotationDegrees), animation)
        ProjectPersistence.executor.execute {
            val result = try { ProjectPersistence.save(context.contentResolver, library, id, name, snapshot) }
            finally { snapshot.recycle() }
            ProjectPersistence.mainHandler.post { onComplete(result) }
        }
    }

    fun exportDrawing(uri: android.net.Uri, format: ExportFormat, quality: Int, scale: Float, transparent: Boolean, onComplete: (Result<Unit>) -> Unit) {
        val snapshot = layerStack.snapshot(galleryQuarterTurns(rasterView.transform.rotationDegrees), animation)
        ProjectPersistence.executor.execute {
            val result = try { ProjectPersistence.export(context.contentResolver, uri, snapshot, format, quality, scale, transparent) }
            finally { snapshot.recycle() }
            ProjectPersistence.mainHandler.post { onComplete(result) }
        }
    }

    fun exportAnimation(uri: android.net.Uri, format: AnimationExportFormat, scale: Float, onComplete: (Result<Unit>) -> Unit) {
        val snapshot = layerStack.snapshot(galleryQuarterTurns(rasterView.transform.rotationDegrees), animation)
        ProjectPersistence.executor.execute {
            val result = try { ProjectPersistence.exportAnimation(context.contentResolver, uri, snapshot, format, scale) }
            finally { snapshot.recycle() }
            ProjectPersistence.mainHandler.post { onComplete(result) }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stopAnimationPlayback()
            gestureHandler.removeCallbacks(hideBrushPreview)
            rasterView.adjustmentPreviewStroke = null
            rasterView.stylusHoverPreview = null
        }
        predictor?.record(event)
        if (eventHasStylus(event)) updateStylusButtons(event.buttonState)
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        if (handleMousePan(event, actionIndex)) return true
        if (selectionMoveMode) return handleSelectionMove(event)
        if (selectionMode) return handleSelection(event)
        if (isTransformingImage() && !isStylus(event, actionIndex)) return handleTouchGesture(event)

        // The emulator maps a host click to a finger. If a simulated second finger is
        // added for pinch, cancel the provisional mark and hand the full event to the
        // normal multi-touch navigation path.
        if ((settings.emulateMouseWithTouch || settings.gestures.oneFingerDrag == FingerAction.DRAW) &&
            activeDrawingPointerId != null &&
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2
        ) {
            cancelActiveStroke(event, activeDrawingPointerId!!)
            return handleTouchGesture(event)
        }

        val startsDrawing = event.actionMasked in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN) &&
            isDrawingPointer(event, actionIndex)
        return if (activeDrawingPointerId != null || startsDrawing) handleStylus(event) else handleTouchGesture(event)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (handleStylusHoverEvent(event)) return true
        return super.dispatchHoverEvent(event)
    }

    internal fun handleStylusHoverEvent(event: MotionEvent): Boolean {
        val stylusIndex = (0 until event.pointerCount).firstOrNull { isStylus(event, it) }
        if (stylusIndex == null) {
            rasterView.stylusHoverPreview = null
            return false
        }
        updateStylusButtons(event.buttonState)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> updateStylusHoverPreview(event, stylusIndex)
            MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_CANCEL -> rasterView.stylusHoverPreview = null
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (eventHasStylus(event)) {
            updateStylusButtons(event.buttonState)
            if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) return true
        }
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            return handlePointerScroll(event)
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun eventHasStylus(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { isStylus(event, it) }

    private fun updateStylusHoverPreview(event: MotionEvent, index: Int) {
        if (!settings.gestures.showStylusHoverPreview || activeDrawingPointerId != null ||
            selectionMode || selectionMoveMode || isTransformingImage()
        ) {
            rasterView.stylusHoverPreview = null
            return
        }
        val hoverSample = sample(event, index).copy(pressure = 1f, opacityPressure = 1f)
        if (hoverSample.position.x !in 0f..layerStack.canvasWidth.toFloat() ||
            hoverSample.position.y !in 0f..layerStack.canvasHeight.toFloat()
        ) {
            rasterView.stylusHoverPreview = null
            return
        }
        val style = currentStyle(event, index)
        val pencilTip = if (style.blend == BlendBehavior.PAINT && style.brush.engine == BrushEngine.PENCIL) {
            StrokeCanvasPainter.pencilTipDynamics(CompletedStroke(listOf(hoverSample), style), hoverSample)
        } else null
        rasterView.stylusHoverPreview = StylusHoverPreview(
            position = hoverSample.position,
            width = pencilTip?.width ?: style.sizePx,
            height = pencilTip?.height ?: style.sizePx,
            rotationRadians = if (pencilTip == null) 0f else hoverSample.orientationRadians,
        )
    }

    private fun updateStylusButtons(buttonState: Int) {
        val pressed = StylusButtons.pressed(buttonState)
        val newPresses = pressed and pressedStylusButtons.inv()
        pressedStylusButtons = pressed
        if (newPresses and 1 != 0) stylusButtonListener?.invoke(StylusButton.PRIMARY)
        if (newPresses and 2 != 0) stylusButtonListener?.invoke(StylusButton.SECONDARY)
    }

    private fun handleStylus(event: MotionEvent): Boolean {
        val pointerId = activeDrawingPointerId ?: event.getPointerId(event.actionIndex)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!isDrawingPointer(event, event.actionIndex)) return true
                cancelColorPick()
                val baseStyle = currentStyle(event, event.actionIndex)
                val target = when (val selected = layerStack.selected()) {
                    is RasterLayerRuntime -> StrokeTarget(selected.tiles)
                    is ImageLayerRuntime -> if (baseStyle.blend == BlendBehavior.ERASE) StrokeTarget(selected.mask, selected) else return true
                }
                requestUnbufferedDispatch(event)
                activeDrawingPointerId = pointerId
                strokeDownAt = event.downTime
                pendingTargets[pointerId] = target
                pendingSamples[pointerId] = mutableListOf(sample(event, event.actionIndex).forTarget(target))
                updateChromeOcclusion(event, event.actionIndex)
                val style = styleForTarget(baseStyle, target)
                if (target.image != null || style.blend == BlendBehavior.ERASE) {
                    customPreviewStyle = style
                    target.store.beginLiveStroke()
                } else if (style.pixelTool != null || style.brush.engine == BrushEngine.AIRBRUSH) {
                    rasterPreviewStyle = style
                    rasterView.previewStroke = CompletedStroke(pendingSamples.getValue(pointerId).toList(), style)
                } else if (style.brush.engine == BrushEngine.PENCIL) {
                    pencilPreviewStyle = style
                    startPencilPreview(CompletedStroke(pendingSamples.getValue(pointerId).toList(), style))
                    // Ink still authors the stroke, but its alpha/tilt preview is unreliable in
                    // 1.1.0-alpha08. Keep that mesh effectively transparent; the native live
                    // pixels become the permanent raster at pen-up, so the appearance cannot jump.
                    liveView.startStroke(event, pointerId, createInkBrush(style, previewAlpha = 1), rasterView.viewToDocumentMatrix(), Matrix())
                } else {
                    liveView.startStroke(event, pointerId, createInkBrush(style), rasterView.viewToDocumentMatrix(), Matrix())
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
                    updateChromeOcclusion(event, index)
                    if (customPreviewStyle != null || rasterPreviewStyle != null) {
                        Unit
                    } else {
                        val prediction = predictor?.predict()
                        try { liveView.addToStroke(event, pointerId, prediction) } finally { prediction?.recycle() }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                strokeRecording?.let { recording ->
                    val tick = ((event.eventTime - recording.startedAt).coerceAtLeast(0L) * (animation?.fps ?: 1) / 1000L)
                    advanceLiveRecording(recording, recording.start + tick.toInt())
                }
                val index = event.findPointerIndex(pointerId)
                val terminalPressure = stabilizedTerminalPressure(
                    pendingSamples[pointerId]?.lastOrNull()?.pressure,
                    if (index >= 0) event.getPressure(index) else 0f,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && event.flags and MotionEvent.FLAG_CANCELED != 0) {
                    cancelActiveStroke(event, pointerId)
                    return true
                }
                if (index >= 0) pendingSamples[pointerId]?.let { list ->
                    pendingTargets[pointerId]?.let { target ->
                        appendSamples(pointerId, list, listOf(sample(event, index).copy(opacityPressure = terminalPressure).forTarget(target)))
                    }
                }
                val target = pendingTargets.remove(pointerId)
                pendingSamples.remove(pointerId)?.let { rawSamples ->
                    val samples = stabilizeLiftOffOpacity(rawSamples)
                    val style = customPreviewStyle ?: rasterPreviewStyle ?: pencilPreviewStyle ?: currentStyle(event, index.coerceAtLeast(0))
                    val reveal = strokeRecording?.takeIf { target?.image == null &&
                        layerStack.selectedId == it.layerId && style.blend == BlendBehavior.PAINT }
                        ?.copy(downAt = strokeDownAt)
                    if (target != null) {
                        if (customPreviewStyle != null) {
                            if (samples.size == 1) invalidateTarget(target, target.store.appendLiveStroke(CompletedStroke(samples, style)))
                            if (reveal != null) target.store.cancelLiveStroke()
                            else target.store.finishLiveStroke(CompletedStroke(samples, style))
                            rasterView.invalidate()
                            notifyHistory()
                            notifyVisiblePalette()
                            notifyLayers()
                            if (reveal != null) finishStrokeRecording(reveal, CompletedStroke(samples, style))
                        } else if (rasterPreviewStyle != null) {
                            if (reveal == null) target.store.commit(CompletedStroke(samples, style))
                            rasterView.previewStroke = null
                            rasterView.invalidate()
                            notifyHistory()
                            drawnColorListener?.invoke(style.color)
                            notifyVisiblePalette()
                            notifyLayers()
                            if (reveal != null) finishStrokeRecording(reveal, CompletedStroke(samples, style))
                        } else if (pencilPreviewStyle != null) {
                            val completed = CompletedStroke(samples, style)
                            pencilPreviewStore?.let { preview ->
                                samples.takeLast(2).takeIf { it.isNotEmpty() }?.let { ending ->
                                    preview.appendPencilPreview(
                                        CompletedStroke(ending, style),
                                        capEnd = true,
                                        skipFirstSegment = ending.size == 2,
                                    )
                                }
                                if (reveal == null) target.store.commitOverlay(preview, completed)
                            }
                            clearPencilPreview()
                            finishedSamples += PendingCommit(completed, target, rasterizedLive = true)
                            rasterView.invalidate()
                            notifyHistory()
                            drawnColorListener?.invoke(style.color)
                            notifyVisiblePalette()
                            notifyLayers()
                            if (reveal != null) finishStrokeRecording(reveal, completed)
                        } else finishedSamples += PendingCommit(CompletedStroke(samples, style), target, reveal = reveal)
                    }
                    publishAnimation()
                }
                if (customPreviewStyle != null) {
                    customPreviewStyle = null
                } else if (rasterPreviewStyle != null) {
                    rasterPreviewStyle = null
                } else {
                    liveView.finishStroke(event, pointerId)
                    pencilPreviewStyle = null
                }
                activeDrawingPointerId = null
                setChromeOccludedByStroke(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelActiveStroke(event, pointerId)
            }
        }
        emitDiagnostics(event, event.actionIndex.coerceIn(0, event.pointerCount - 1))
        return true
    }

    private fun handleMousePan(event: MotionEvent, index: Int): Boolean {
        val mouse = event.getToolType(index) == MotionEvent.TOOL_TYPE_MOUSE
        val panButton = event.buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY) != 0
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (mouse && panButton) {
                activeMousePan = true
                lastMousePan = android.graphics.PointF(event.getX(index), event.getY(index))
                return true
            }
            MotionEvent.ACTION_MOVE -> if (activeMousePan) {
                val x = event.getX(index)
                val y = event.getY(index)
                val old = rasterView.transform
                rasterView.updateTransform(
                    old.panX + x - lastMousePan.x,
                    old.panY + y - lastMousePan.y,
                    old.scale,
                    old.rotationDegrees,
                )
                lastMousePan.set(x, y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (activeMousePan) {
                activeMousePan = false
                return true
            }
        }
        return false
    }

    private fun handlePointerScroll(event: MotionEvent): Boolean {
        var horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        var vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val shiftPressed = event.metaState and KeyEvent.META_SHIFT_ON != 0
        val zoomModifier = event.metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON) != 0
        if (shiftPressed && horizontal == 0f) {
            horizontal = vertical
            vertical = 0f
        }
        val old = rasterView.transform
        if (zoomModifier) {
            val anchor = rasterView.screenToDocument(event.x, event.y)
            val scale = (old.scale * exp(vertical * .12f)).coerceIn(.08f, 256f)
            rasterView.updateTransformAround(anchor.x, anchor.y, event.x, event.y, scale, old.rotationDegrees)
        } else {
            val scrollPixels = 48f * resources.displayMetrics.density
            rasterView.updateTransform(
                old.panX + horizontal * scrollPixels,
                old.panY + vertical * scrollPixels,
                old.scale,
                old.rotationDegrees,
            )
        }
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
                if (isTransformingImage()) recordImageSample(event.eventTime)
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
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && (isTransformingImage() || gestureMoved)) {
                    rebaseToRemainingPointer(event)
                    if (isTransformingImage()) notifyLayers()
                    notifyHistory()
                    return true
                }
                if (isTransformingImage()) {
                    notifyLayers()
                    if (event.actionMasked == MotionEvent.ACTION_UP) finishImageRecording(event.eventTime)
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
                motionRecording = null; publishAnimation()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && isTransformingImage()) recordImageSample(event.eventTime)
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
                    selectionRegion = selectionPoints.takeIf { it.size >= 3 }?.let { SelectionRegion(it.toList()) }
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
                val stores = layerStack.selectedRasters().map { it.tiles }
                val images = layerStack.selectedImages()
                if (stores.isNotEmpty() || images.isNotEmpty()) {
                    val dirty = stores.flatMapTo(mutableSetOf()) { it.moveSelection(selection, deltaX, deltaY) }
                    rasterView.invalidateTiles(dirty)
                    layerStack.translateSelectedImages(deltaX, deltaY)
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
        val picked = layerStack.colorAt(document.x, document.y, originalImageColors)
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
            val anchor = rasterView.screenToDocument(lastGestureCentroid.x, lastGestureCentroid.y)
            rasterView.updateTransformAround(
                anchor.x,
                anchor.y,
                center.x,
                center.y,
                (old.scale * scaleFactor).coerceIn(.08f, 256f),
                old.rotationDegrees + Math.toDegrees(angleDelta.toDouble()).toFloat(),
            )
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
        val pencilContext = if (pencilPreviewStyle != null) samples.takeLast(2) else emptyList()
        samples += additions
        rasterPreviewStyle?.let { style ->
            rasterView.previewStroke = CompletedStroke(samples.toList(), style)
            return
        }
        pencilPreviewStyle?.let { style ->
            val preview = pencilPreviewStore ?: return
            if (previous != null) {
                val segment = ArrayList<StrokeSample>(additions.size + pencilContext.size).apply {
                    addAll(pencilContext)
                    addAll(additions)
                }
                rasterView.invalidateTiles(preview.appendPencilPreview(
                    CompletedStroke(segment, style),
                    skipFirstSegment = pencilContext.size == 2,
                ))
            }
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
        gestureStart = emptyMap()
        gestureMoved = false
    }

    private fun cancelActiveStroke(event: MotionEvent, pointerId: Int) {
        if (customPreviewStyle == null && rasterPreviewStyle == null) {
            liveView.cancelStroke(event, pointerId)
        }
        if (customPreviewStyle != null) {
            pendingTargets[pointerId]?.let { target -> invalidateTarget(target, target.store.cancelLiveStroke()) }
        }
        customPreviewStyle = null
        rasterPreviewStyle = null
        pencilPreviewStyle = null
        clearPencilPreview()
        rasterView.previewStroke = null
        pendingSamples.remove(pointerId)
        pendingTargets.remove(pointerId)
        activeDrawingPointerId = null
        setChromeOccludedByStroke(false)
        if (strokeRecording != null) cancelAnimationRecording()
    }

    private fun updateChromeOcclusion(event: MotionEvent, index: Int) {
        if (chromeOcclusionListener == null || index !in 0 until event.pointerCount || height <= 0) return
        val y = event.getY(index)
        setChromeOccludedByStroke(
            y <= chromeTopInsetPx + chromeApproachMarginPx ||
                y >= height - chromeBottomInsetPx - chromeApproachMarginPx,
        )
    }

    private fun setChromeOccludedByStroke(occluded: Boolean) {
        if (chromeOccludedByStroke == occluded) return
        chromeOccludedByStroke = occluded
        chromeOcclusionListener?.invoke(occluded)
    }

    private fun currentStyle(event: MotionEvent, index: Int): StrokeStyle {
        val isHardwareEraser = index in 0 until event.pointerCount && event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        val erasing = settings.erasing || isHardwareEraser
        val brush = if (erasing) settings.brush.copy(hardness = settings.eraserHardness) else settings.brush
        return StrokeStyle(brush, settings.sizePx, settings.opacity, settings.color,
            if (erasing) BlendBehavior.ERASE else BlendBehavior.PAINT, selectionRegion,
            pixelTool = settings.pixelTool)
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

    private fun createInkBrush(style: StrokeStyle, includePressureOpacity: Boolean = true, previewAlpha: Int? = null): Brush {
        val c = style.color
        val alpha = previewAlpha ?: (style.opacity * c.alpha * 255).roundToInt().coerceIn(1, 255)
        val argb = Color.argb(alpha, (c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())
        val brushPreset = if (includePressureOpacity) style.brush else {
            style.brush.copy(pressureToOpacity = PressureCurve(1f, 1f, 1f))
        }
        val family = tipStrokeInkBrushes.familyFor(brushPreset)
        val liveColor = if (style.blend == BlendBehavior.ERASE) Color.WHITE else argb
        val epsilon = when (style.brush.engine) {
            // At 1200% zoom these remain below one screen pixel, while avoiding
            // needlessly dense meshes that cannot add detail to the raster canvas.
            BrushEngine.PENCIL -> .04f
            BrushEngine.AIRBRUSH -> .08f
            BrushEngine.INK -> .1f
        }
        return Brush.createWithColorIntArgb(family, liveColor, style.sizePx.coerceAtLeast(1f), epsilon)
    }

    private fun startPencilPreview(stroke: CompletedStroke) {
        clearPencilPreview()
        pencilPreviewStore = TileStore(layerStack.canvasWidth, layerStack.canvasHeight).also { preview ->
            preview.appendPencilPreview(stroke, capStart = true)
            rasterView.setPencilPreview(preview, layerStack.selectedId)
        }
    }

    private fun clearPencilPreview() {
        pencilPreviewStore?.discard()
        pencilPreviewStore = null
        rasterView.setPencilPreview(null, null)
    }

    private fun sample(event: MotionEvent, index: Int, historyIndex: Int? = null): StrokeSample {
        val x = historyIndex?.let { event.getHistoricalX(index, it) } ?: event.getX(index)
        val y = historyIndex?.let { event.getHistoricalY(index, it) } ?: event.getY(index)
        val point = rasterView.screenToDocument(x, y)
        val kind = pointerKind(event.getToolType(index)).let {
            if (it == PointerKind.FINGER && settings.emulateMouseWithTouch) PointerKind.MOUSE else it
        }
        val reportedPressure = historyIndex?.let { event.getHistoricalPressure(index, it) } ?: event.getPressure(index)
        val pressure = if (kind == PointerKind.MOUSE ||
            (kind == PointerKind.FINGER && settings.gestures.oneFingerDrag == FingerAction.DRAW)
        ) 1f else reportedPressure
        val tilt = (historyIndex?.let { event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, it) }
            ?: event.getAxisValue(MotionEvent.AXIS_TILT, index)).coerceIn(0f, (Math.PI / 2).toFloat())
        val orientation = historyIndex?.let { event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, it) }
            ?: event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index)
        return StrokeSample(event.getPointerId(index), Point(point.x, point.y), pressure.coerceIn(.01f, 1f),
            tilt, orientation,
            (((historyIndex?.let { event.getHistoricalEventTime(it) } ?: event.eventTime) - event.downTime) * 1_000_000L).coerceAtLeast(0),
            event.buttonState, kind)
    }

    private fun isStylus(event: MotionEvent, index: Int) = event.getToolType(index) in intArrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)
    private fun isDrawingPointer(event: MotionEvent, index: Int): Boolean = when (event.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
        MotionEvent.TOOL_TYPE_MOUSE -> event.buttonState and MotionEvent.BUTTON_PRIMARY != 0
        MotionEvent.TOOL_TYPE_FINGER ->
            (settings.emulateMouseWithTouch || settings.gestures.oneFingerDrag == FingerAction.DRAW) && event.pointerCount == 1
        else -> false
    }
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
        layersListener?.invoke(
            layerStack.summariesFrontToBack(),
            layerStack.selectedId,
            layerStack.selectedLayerIds(),
            layerStack.previewsFrontToBack(96),
        )
    }
    private fun notifyVisiblePalette() {
        visiblePaletteListener?.invoke(layerStack.visiblePalette(paletteColorCount, originalImageColors))
    }
    private fun emitDiagnostics(event: MotionEvent, index: Int) {
        val now = SystemClock.elapsedRealtimeNanos(); frameCount++
        if (now - fpsWindowAt > 500_000_000L) { fps = frameCount * 1_000_000_000f / (now - fpsWindowAt); frameCount = 0; fpsWindowAt = now }
        val delta = now - lastSampleAt; lastSampleAt = now
        val kind = pointerKind(event.getToolType(index)).let {
            if (it == PointerKind.FINGER && settings.emulateMouseWithTouch) PointerKind.MOUSE else it
        }
        val pressure = if (kind == PointerKind.MOUSE ||
            (kind == PointerKind.FINGER && settings.gestures.oneFingerDrag == FingerAction.DRAW)
        ) 1f else event.getPressure(index)
        val input = CanvasDiagnostics(fps, pressure, event.getAxisValue(MotionEvent.AXIS_TILT, index),
            kind.name, if (delta > 0) 1_000_000_000f / delta else 0f,
            rasterView.transform.scale, layerStack.allocatedTiles(), layerStack.lastDirtyTiles(), layerStack.undoBytes())
        publishDiagnostics(if (settings.debug) input.withMemoryFrom(lastDiagnostics) else input)
    }

    private fun publishDiagnostics(diagnostics: CanvasDiagnostics) {
        lastDiagnostics = diagnostics
        diagnosticsListener?.invoke(diagnostics)
    }

    private fun CanvasDiagnostics.withMemory(memory: MemoryBudgetSnapshot) = copy(
        processBytes = memory.processBytes,
        processBudgetBytes = memory.processBudgetBytes,
        deviceAvailableBytes = memory.deviceAvailableBytes,
        documentBytes = memory.documentBytes,
        fullLayerBytes = memory.fullLayerBytes,
        fullLayersRemaining = memory.fullLayersRemaining,
        memoryPressure = memory.pressure,
    )

    private fun CanvasDiagnostics.withMemoryFrom(previous: CanvasDiagnostics) = copy(
        processBytes = previous.processBytes,
        processBudgetBytes = previous.processBudgetBytes,
        deviceAvailableBytes = previous.deviceAvailableBytes,
        documentBytes = previous.documentBytes,
        fullLayerBytes = previous.fullLayerBytes,
        fullLayersRemaining = previous.fullLayersRemaining,
        memoryPressure = previous.memoryPressure,
    )
}

/** ACTION_UP pressure describes loss of contact, not an intentional final paint sample. */
internal fun stabilizedTerminalPressure(previousPressure: Float?, reportedUpPressure: Float): Float =
    (previousPressure ?: reportedUpPressure).coerceIn(.01f, 1f)

/**
 * Stabilizes only opacity when pressure rapidly collapses as the pen leaves the digitizer.
 * The raw pressure remains available for a natural size taper at the end of the stroke.
 */
internal fun stabilizeLiftOffOpacity(samples: List<StrokeSample>): List<StrokeSample> {
    if (samples.size < 3) return samples
    val windowStart = samples.last().elapsedNanos - 120_000_000L
    val firstInWindow = samples.indexOfFirst { it.elapsedNanos >= windowStart }.coerceAtLeast(0)
    val peakIndex = (firstInWindow..samples.lastIndex).maxBy { samples[it].opacityPressure }
    val peak = samples[peakIndex].opacityPressure
    val terminal = samples.last().opacityPressure
    if (terminal > .35f || peak - terminal < .25f || peakIndex == samples.lastIndex) return samples

    val upwardSteps = samples.subList(peakIndex, samples.size).zipWithNext().count { (before, after) ->
        after.opacityPressure > before.opacityPressure + .06f
    }
    if (upwardSteps > 1) return samples

    return samples.mapIndexed { index, sample ->
        if (index > peakIndex) sample.copy(opacityPressure = peak) else sample
    }
}
