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
import dev.tipstroke.core.geometry.QuickShape
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.geometry.Rect
import dev.tipstroke.core.geometry.SelectionRegion
import dev.tipstroke.core.geometry.SelectionTransform
import dev.tipstroke.core.geometry.transformed
import dev.tipstroke.core.model.*
import kotlin.math.*

enum class SelectionTool { RECTANGLE, LASSO, ELLIPSE, POLYGON }

internal fun galleryQuarterTurns(rotationDegrees: Float): Int = (rotationDegrees / 90f).roundToInt().mod(4)

@OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)
class DrawingSurface @JvmOverloads constructor(context: Context, attrs: android.util.AttributeSet? = null) : FrameLayout(context, attrs) {
    val settings = CanvasSettings()
    private var processRecorder: ProcessRecorder? = null
    private var processReadError: String? = null

    fun processRecordingStatus(): String = processReadError
        ?: processRecorder?.stoppedReason ?: "Recording drawing progress"

    fun getProcessRecording(onComplete: (Result<ProcessRecording>) -> Unit) {
        val recorder = processRecorder
        val error = processReadError
        val captured = recorder?.captureSnapshot()
        ProcessEncoding.executor.execute {
            val result = runCatching {
                check(error == null) { error.orEmpty() }
                requireNotNull(captured) { "No process recording" }.get().also {
                    require(it.actionCount > 0) { "No drawing progress recorded yet" }
                    it.hasRecoveryGap // Inspect the archive on the worker, not during composition.
                }
            }
            ProjectPersistence.mainHandler.post { onComplete(result) }
        }
    }

    fun exportProcess(uri: android.net.Uri, format: AnimationExportFormat, onComplete: (Result<Unit>) -> Unit) {
        getProcessRecording { captured ->
            captured.fold(onSuccess = { recording ->
                ExportWork.start(context, work = { progress ->
                    runCatching {
                        ProcessReplay(recording, context.cacheDir).use { replay ->
                            val frames = List(recording.actionCount) { AnimationFrame(FrameId("process-$it")) }
                            val timeline = SavedAnimationSnapshot(frames, 0, 12, PlaybackMode.ONCE, 0, 0, 0f, emptySet(), emptyMap(), emptyMap())
                            AnimationExporter.export(context.contentResolver, uri, timeline, format, onProgress = progress) { replay.frame(it) }
                        }
                    }
                }, onComplete = onComplete)
            }, onFailure = { onComplete(Result.failure(it)) })
        }
    }
    private lateinit var rasterView: RasterCanvasView
    private var resizeOverlay: CanvasResizeOverlay? = null
    private var transformBeforeResize: dev.tipstroke.core.geometry.CanvasTransform? = null
    private lateinit var colorLoupe: ColorPickerLoupeView
    private var layerStack: LayerStack = createLayerStack(2048, 2048)
    private var animation: AnimationRuntime? = null
    private var videoView: MediaVideoView? = null
    private var resumeAfterMediaSeek = false
    private var mediaSeeking = false
    private var mediaCelLoading: FrameId? = null
    private var requestedVideoTimeUs: Long? = null
    private var lastMediaUiAt = 0L
    private var mediaPlaybackOffsetUs = 0L
    private var animationPlaying = false
    private var pendingAnimationFrame: FrameId? = null
    private val animationThumbnails = linkedMapOf<FrameId, android.graphics.Bitmap>()
    private val pendingThumbnails = mutableSetOf<FrameId>()
    var animationErrorListener: ((String) -> Unit)? = null
    private fun animationNeighbourIndices(active: AnimationRuntime): Set<Int> =
        ((active.selectedIndex - active.onionBefore)..(active.selectedIndex + active.onionAfter))
            .filterTo(mutableSetOf()) { it in active.frames.indices }.apply {
                if (active.media != null) active.frames.indexOfFirst { (it.sourceTimeUs ?: 0) > active.mediaPositionUs }
                    .takeIf { it >= 0 }?.let(::add)
            }
    private fun warmAnimationNeighbours() {
        updateAnimationStoragePins()
        val active = animation ?: return
        val keep = animationNeighbourIndices(active)
        active.prepareFrames(keep) { result ->
            if (animation === active) {
                result.exceptionOrNull()?.let { animationErrorListener?.invoke(it.message ?: "Could not load frame") }
                if (strokeRecording == null && motionRecording == null) active.trimCleanFrames(animationNeighbourIndices(active))
                rasterView.invalidate()
            }
        }
    }
    private var thumbnailRevision = 0
    private var switchingAnimationFrame = false
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
            if (active.media != null) {
                // GIFs use their persistent decoder. Videos run on MediaPlayer's own clock.
                if (videoView == null) {
                    val time = mediaPlaybackOffsetUs + (SystemClock.uptimeMillis() - playbackStartedAt) * 1000L
                    if (time >= active.media!!.durationUs) { stopAnimationPlayback(); seekMedia(active.media!!.durationUs - 1); return }
                    active.mediaPreview?.prepare(time, playback = true) { result ->
                        if (animation === active && animationPlaying) {
                            result.onSuccess {
                                presentMediaTime(active.mediaPreview?.timeUs ?: time, true)
                                rasterView.invalidate()
                            }.onFailure { stopAnimationPlayback(); animationErrorListener?.invoke(it.message ?: "Cannot play clip") }
                        }
                    }
                    gestureHandler.postDelayed(this, 33L)
                }
                return
            }
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
        if (!imageGestureInProgress) { notifyLayers(); notifyVisiblePalette() }
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
    private var multiEraseTargets = emptyList<Pair<StrokeTarget, StrokeStyle>>()
    private val multiEraseSamples = mutableListOf<StrokeSample>()
    private val pendingTargets = mutableMapOf<Int, StrokeTarget>()
    private var rasterSamplesRendered = 0
    private var rasterFramePending = false
    private val rasterFrame = Runnable { flushRasterSamples() }
    private var customPreviewStyle: StrokeStyle? = null
    private var gpuStampPreview: GpuStampPreview? = null
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
    private var shapeHold: Runnable? = null
    private var shapeEvent: MotionEvent? = null
    private var shapeAnchor: Point? = null
    private var shapeSnapped = false
    private var shapeStyle: StrokeStyle? = null
    private var fittedShape: QuickShape.Shape? = null
    private var shapeGrab: Point? = null
    private var fittedSamples: List<StrokeSample> = emptyList()
    private var shapeAdjustmentEvent: MotionEvent? = null
    private val shapeAdjustmentFrame = Runnable {
        val event = shapeAdjustmentEvent
        shapeAdjustmentEvent = null
        if (event != null) {
            try { activeDrawingPointerId?.let { if (shapeSnapped) adjustShape(event, it) } }
            finally { event.recycle() }
        }
    }
    private var selectionPointerId: Int? = null
    private var colorPicking = false
    private var pendingPickedColor: RgbaColor? = null
    private var singleStart = android.graphics.PointF()
    private var singleLast = android.graphics.PointF()
    private var singleLastDocument = android.graphics.PointF()
    private var smudgeTarget: RasterLayerRuntime? = null
    private var toolSmudgePointer: Int? = null
    private var toolSmudgeLast = android.graphics.PointF()
    private var pendingSmudgePoint: android.graphics.PointF? = null
    private var smudgeFrameScheduled = false
    private val smudgeFrame = Runnable {
        smudgeFrameScheduled = false
        applyPendingSmudge()
    }
    private fun applyPendingSmudge() {
        val point = pendingSmudgePoint ?: return
        pendingSmudgePoint = null
        if (point != toolSmudgeLast && settings.opacity > 0f) {
            smudgeTarget?.tiles?.smudge(toolSmudgeLast.x, toolSmudgeLast.y, point.x, point.y,
                smudgeRadius(), settings.gestures.smudgeStrength * settings.opacity)
        }
        toolSmudgeLast = point
        rasterView.invalidate()
    }
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
    var layersListener: ((List<LayerSummary>, LayerId, Set<LayerId>, Map<LayerId, android.graphics.Bitmap>, Int, Float) -> Unit)? = null
    var animationListener: ((AnimationUiState?) -> Unit)? = null
        set(value) { field = value; value?.invoke(animation?.state(animationPlaying, recordingKind(), thumbnailRevision)?.copy(mediaPositionUs = requestedVideoTimeUs ?: animation?.mediaPositionUs ?: 0L, loading = importingClip || pendingAnimationFrame != null || mediaSeeking || mediaCelLoading != null, hasMedia = animation?.media != null)) }
    var colorPickedListener: ((RgbaColor) -> Unit)? = null
    var visiblePaletteListener: ((List<RgbaColor>) -> Unit)? = null
    var drawnColorListener: ((RgbaColor) -> Unit)? = null
    private var paletteColorCount = 3
    private var originalImageColors = false
    var stylusButtonListener: ((StylusButton) -> Unit)? = null
    var chromeOcclusionListener: ((Boolean) -> Unit)? = null
    private var chromeOccludedByStroke = false
    private var chromeLeftInsetPx = 0f
    private var chromeRightInsetPx = 0f
    private var chromeTopInsetPx = 0f
    private var chromeBottomInsetPx = 0f
    private var chromeApproachMarginPx = 0f
    private var pressedStylusButtons = 0
    private var imageTransformMode = false
    var photoEditTool = PhotoEditTool.MOVE
        set(value) { field = value; updateTransformGrid() }
    var photoSnapping = false
    /** Brush radius as a fraction of image height. */
    var photoWarpRadius = .25f
    var transformMeshDivisions = 4
        set(value) { require(value in listOf(2,4,8)); field = value; updateTransformGrid() }
    var selectionEditTool = PhotoEditTool.MOVE
        set(value) { field = value; updateTransformGrid() }
    private var photoSessionOriginal: Pair<LayerId, ImageTransform>? = null
    private var meshControl: Pair<Int, Int>? = null
    private var selectionDragPoint: Point? = null
    private var selectionDragBefore: SelectionTransform? = null

    private fun updateTransformGrid() {
        val tool = if (transformSelectionOriginal != null) selectionEditTool else photoEditTool
        rasterView.transformMeshDivisions = if (tool == PhotoEditTool.WARP) transformMeshDivisions else 1
        rasterView.transformHandles = transformSelectionOriginal != null
        rasterView.invalidate()
    }
    private fun nearestControl(point: Point, divisions: Int, map: (Float, Float) -> Point): Pair<Int, Int>? {
        val nearest = (0..divisions).flatMap { row -> (0..divisions).map { col -> col to row } }
            .minBy { (col,row) -> val p=map(col.toFloat()/divisions,row.toFloat()/divisions); hypot(p.x-point.x,p.y-point.y) }
        val p=map(nearest.first.toFloat()/divisions,nearest.second.toFloat()/divisions)
        return nearest.takeIf { hypot(p.x-point.x,p.y-point.y)*rasterView.transform.scale < 36f*resources.displayMetrics.density }
    }

    fun photoTransformAction(action: String) {
        finishPhotoEdit(); beginPhotoEdit()
        val image = layerStack.selectedImage() ?: return
        val t=image.transform
        image.transform = when(action) {
            "flipX" -> t.copy(stretchX=-t.stretchX)
            "flipY" -> t.copy(stretchY=-t.stretchY)
            "rotate" -> t.copy(rotationDegrees=t.rotationDegrees+45f)
            "filter" -> t.copy(filtered=!t.filtered)
            "reset" -> photoSessionOriginal?.takeIf { it.first == image.id }?.second ?: t
            "fit" -> {
                val bounds=t.photoBounds(image.source.width,image.source.height)
                val factor=minOf(canvasWidthPx/bounds.width(),canvasHeightPx/bounds.height())
                t.copy(scale=t.scale*factor,centerX=canvasWidthPx/2f+(t.centerX-bounds.centerX())*factor,
                    centerY=canvasHeightPx/2f+(t.centerY-bounds.centerY())*factor)
            }
            else -> t
        }
        finishPhotoEdit()
    }
    fun photoBilinear() = layerStack.selectedImage()?.transform?.filtered ?: true

    fun selectionTransformAction(action: String) {
        val t=pendingSelectionTransform ?: return
        val bounds=requireNotNull(t.sourceBounds)
        val next = when(action) {
            "reset" -> SelectionTransform(t.pivot, sourceBounds=bounds)
            "fit" -> {
                val points=List(17*17) { i -> t.map(Point(bounds.left+(bounds.right-bounds.left)*(i%17)/16f,
                    bounds.top+(bounds.bottom-bounds.top)*(i/17)/16f)) }
                val left=points.minOf { it.x }; val right=points.maxOf { it.x }
                val top=points.minOf { it.y }; val bottom=points.maxOf { it.y }
                val factor=minOf(canvasWidthPx/(right-left),canvasHeightPx/(bottom-top),8f/abs(t.scaleX),8f/abs(t.scaleY))
                t.copy(scaleX=t.scaleX*factor,scaleY=t.scaleY*factor,
                    translation=Point(canvasWidthPx/2f-t.pivot.x+(t.pivot.x+t.translation.x-(left+right)/2f)*factor,
                        canvasHeightPx/2f-t.pivot.y+(t.pivot.y+t.translation.y-(top+bottom)/2f)*factor))
            }
            "flipX" -> t.copy(scaleX=-t.scaleX)
            "flipY" -> t.copy(scaleY=-t.scaleY)
            "rotate" -> t.copy(rotationDegrees=t.rotationDegrees+45f)
            else -> t
        }
        showSelectionTransform(next)
    }
    private fun showSelectionTransform(transform: SelectionTransform) {
        pendingSelectionTransform=transform
        rasterView.selectionMeshTransform=transform
        rasterView.selectionPreviewMatrix=null
        updateTransformGrid()
    }
    private fun handleSelectionTransform(event: MotionEvent): Boolean {
        val t=pendingSelectionTransform ?: return true
        val bounds=requireNotNull(t.sourceBounds)
        val p=rasterView.screenToDocument(event.x,event.y).let { Point(it.x,it.y) }
        val divisions=if(selectionEditTool==PhotoEditTool.WARP) transformMeshDivisions else 1
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectionDragBefore=t; selectionDragPoint=p
                meshControl=nearestControl(p,divisions) { u,v -> t.map(Point(bounds.left+(bounds.right-bounds.left)*u,bounds.top+(bounds.bottom-bounds.top)*v)) }
            }
            MotionEvent.ACTION_MOVE -> selectionDragPoint?.let { previous ->
                if(event.pointerCount != 1) return true
                val dx=p.x-previous.x; val dy=p.y-previous.y
                val radians=Math.toRadians(-t.rotationDegrees.toDouble())
                val local=Point((dx*cos(radians)-dy*sin(radians)).toFloat()/((bounds.right-bounds.left)*t.scaleX),
                    (dx*sin(radians)+dy*cos(radians)).toFloat()/((bounds.bottom-bounds.top)*t.scaleY))
                val control=meshControl
                val next=when(selectionEditTool) {
                    PhotoEditTool.MOVE, PhotoEditTool.STRETCH -> if(control==null) t.copy(translation=Point(t.translation.x+dx,t.translation.y+dy)) else {
                        val x=exp(local.x*(if(control.first==0) -2f else 2f))
                        val y=exp(local.y*(if(control.second==0) -2f else 2f))
                        val uniform=if(abs(local.x)>abs(local.y)) x else y
                        fun scaled(value: Float,factor: Float)= kotlin.math.sign(value)*(abs(value)*factor).coerceIn(.01f,8f)
                        t.copy(scaleX=scaled(t.scaleX,if(selectionEditTool==PhotoEditTool.MOVE) uniform else x),
                            scaleY=scaled(t.scaleY,if(selectionEditTool==PhotoEditTool.MOVE) uniform else y))
                    }
                    PhotoEditTool.WARP, PhotoEditTool.DISTORT -> if(control==null) t else t.copy(warp=if(selectionEditTool==PhotoEditTool.DISTORT) (t.warp ?: PhotoWarp.identity()).distortCorner(control.first,control.second,local)
                        else (t.warp ?: PhotoWarp.identity()).moveControl(control.first,control.second,divisions,local))
                    else -> {
                        val inverse=t.copy(warp=null).toAndroidMatrix(); val inv=android.graphics.Matrix(); inverse.invert(inv)
                        val xy=floatArrayOf(previous.x,previous.y); inv.mapPoints(xy)
                        val center=Point((xy[0]-bounds.left)/(bounds.right-bounds.left),(xy[1]-bounds.top)/(bounds.bottom-bounds.top))
                        t.copy(warp=(t.warp ?: PhotoWarp.identity()).deform(selectionEditTool,center,local,photoWarpRadius,
                            abs((bounds.right-bounds.left)*t.scaleX/((bounds.bottom-bounds.top)*t.scaleY))))
                    }
                }
                showSelectionTransform(next); selectionDragPoint=p
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                selectionDragBefore?.let(::showSelectionTransform); selectionDragPoint=null
            }
            MotionEvent.ACTION_UP -> { selectionDragPoint=null; selectionDragBefore=null }
        }
        return true
    }

    private var photoBefore: Pair<ImageLayerRuntime, ImageTransform>? = null
    private var photoUnsnapped: ImageTransform? = null
    private var photoCenter = Point(.5f,.5f)

    private fun beginPhotoEdit() {
        val image = layerStack.selectedImage() ?: return
        updateTransformGrid()
        meshControl=nearestControl(Point(singleLastDocument.x,singleLastDocument.y),
            if(photoEditTool==PhotoEditTool.WARP) transformMeshDivisions else 1) { u,v -> image.transform.documentPoint(u,v,image.source.width,image.source.height) }
        photoBefore = image to image.transform
        photoUnsnapped = image.transform
        photoCenter = image.transform.copy(warp=null).sourcePoint(
            singleLastDocument.x,singleLastDocument.y,image.source.width,image.source.height)!!
    }

    private fun finishPhotoEdit(cancel: Boolean = false) {
        val (image,before) = photoBefore ?: return
        photoBefore = null; photoUnsnapped = null
        rasterView.photoBrushArea = null
        val after = image.transform
        if (cancel) image.transform = before
        else if (before != after) image.mask.history.push(object : UndoTransaction {
            override val estimatedBytes = 128L + ((before.warp?.points?.size ?: 0)+(after.warp?.points?.size ?: 0))*32L
            override fun undo() { image.transform = before }
            override fun redo() { image.transform = after }
        })
        rasterView.invalidate(); notifyHistory(); notifyLayers()
    }

    fun resetPhotoShape() {
        finishPhotoEdit()
        beginPhotoEdit()
        layerStack.selectedImage()?.let { it.transform = it.transform.copy(stretchX=1f,stretchY=1f,warp=null) }
        finishPhotoEdit()
    }

    private fun editPhoto(dx: Float, dy: Float, scale: Float = 1f, angle: Float = 0f, navigation: Boolean = false) {
        val image = layerStack.selectedImage() ?: return
        val old = photoUnsnapped ?: image.transform
        val radians = Math.toRadians(-old.rotationDegrees.toDouble())
        val localX = (dx*cos(radians)-dy*sin(radians)).toFloat()/(image.source.width*old.scale*old.stretchX)
        val localY = (dx*sin(radians)+dy*cos(radians)).toFloat()/(image.source.height*old.scale*old.stretchY)
        val tool = if(navigation) PhotoEditTool.MOVE else photoEditTool
        val next = when(tool) {
            PhotoEditTool.MOVE -> if (!navigation && meshControl != null) {
                val (col,row)=meshControl!!
                val factor=exp(if(abs(localX)>abs(localY)) localX*(if(col==0) -2f else 2f) else localY*(if(row==0) -2f else 2f))
                old.changedBy(0f,0f,factor)
            } else old.changedBy(dx,dy,scale,angle)
            PhotoEditTool.STRETCH -> old.copy(
                stretchX=kotlin.math.sign(old.stretchX)*(abs(old.stretchX)*exp(localX*(if(photoCenter.x<.5f) -2f else 2f))).coerceIn(.02f,50f),
                stretchY=kotlin.math.sign(old.stretchY)*(abs(old.stretchY)*exp(localY*(if(photoCenter.y<.5f) -2f else 2f))).coerceIn(.02f,50f))
            PhotoEditTool.WARP, PhotoEditTool.DISTORT -> meshControl?.let { (col,row) ->
                old.copy(warp=if(tool==PhotoEditTool.DISTORT) (old.warp ?: PhotoWarp.identity()).distortCorner(col,row,Point(localX,localY))
                    else (old.warp ?: PhotoWarp.identity()).moveControl(col,row,transformMeshDivisions,Point(localX,localY)))
            } ?: old
            else -> {
                val center = if(tool == PhotoEditTool.PUSH) {
                    old.copy(warp=null).sourcePoint(singleLastDocument.x,singleLastDocument.y,image.source.width,image.source.height)!!
                } else photoCenter
                old.copy(warp=(old.warp ?: PhotoWarp.identity()).deform(tool,center,Point(localX,localY),photoWarpRadius,
                    abs(image.source.width*old.stretchX/(image.source.height*old.stretchY))))
            }
        }
        rasterView.photoBrushArea = if(tool in listOf(PhotoEditTool.PUSH,PhotoEditTool.BULGE,PhotoEditTool.PINCH)) {
            val center = if(tool == PhotoEditTool.PUSH) Point(singleLastDocument.x,singleLastDocument.y)
                else old.copy(warp=null).documentPoint(photoCenter.x,photoCenter.y,image.source.width,image.source.height)
            center to (photoWarpRadius*image.source.height*old.scale*abs(old.stretchY))
        } else null
        photoUnsnapped = next
        image.transform = if(photoSnapping && tool==PhotoEditTool.MOVE) snapPhoto(image,next) else next
        rasterView.invalidate()
    }

    private fun snapPhoto(image: ImageLayerRuntime, proposed: ImageTransform): ImageTransform {
        val step = settings.gestures.imageSnapAngle.toFloat()
        val strength = settings.gestures.imageSnapStrength
        val candidates = listOf((proposed.rotationDegrees/step).roundToInt()*step) +
            layerStack.layers.filterIsInstance<ImageLayerRuntime>().filter { it.id != image.id && it.visible }.map {
                it.transform.rotationDegrees + ((proposed.rotationDegrees-it.transform.rotationDegrees)/360f).roundToInt()*360f
            }
        val angle=candidates.minBy { abs(it-proposed.rotationDegrees) }
        val rotated=if(abs(angle-proposed.rotationDegrees)<minOf(step * .3f, .5f + 5f * strength)) proposed.copy(rotationDegrees=angle) else proposed
        val bounds=rotated.photoBounds(image.source.width,image.source.height)
        val targets=mutableListOf(android.graphics.RectF(0f,0f,layerStack.canvasWidth.toFloat(),layerStack.canvasHeight.toFloat()))
        layerStack.layers.filterIsInstance<ImageLayerRuntime>().filter { it.id != image.id && it.visible }.forEach {
            targets += it.transform.photoBounds(it.source.width,it.source.height)
        }
        val tolerance=(2f + 16f * strength)*resources.displayMetrics.density/rasterView.transform.scale
        fun closest(values: List<Float>, anchors: List<Float>): Float = values.flatMap { v -> anchors.map { it-v } }
            .filter { abs(it)<tolerance }.minByOrNull { abs(it) } ?: 0f
        val x=closest(listOf(bounds.left,bounds.centerX(),bounds.right),targets.flatMap { listOf(it.left,it.centerX(),it.right) })
        val y=closest(listOf(bounds.top,bounds.centerY(),bounds.bottom),targets.flatMap { listOf(it.top,it.centerY(),it.bottom) })
        return rotated.changedBy(x,y)
    }

    private var imageGestureInProgress = false
    private var opacityAdjustmentInProgress = false
    private var referenceLayerId: LayerId? = null
    var referenceStrokesListener: ((LayerId?) -> Unit)? = null
    private var selectionOperation = dev.tipstroke.core.geometry.SelectionOperation.REPLACE
    private var previousSelection: SelectionRegion? = null
    fun setSelectionOperation(value: dev.tipstroke.core.geometry.SelectionOperation) { selectionOperation = value }
    fun setReferenceStrokes(id: LayerId?) {
        if (activeDrawingPointerId != null) return
        cancelFill()
        referenceLayerId = id?.takeIf { candidate -> layerStack.layers.any { it.id == candidate && it is RasterLayerRuntime } }
        referenceStrokesListener?.invoke(referenceLayerId)
        if (referenceLayerId != null) setSelectionMode(false)
    }
    fun invertSelection() {
        val all = SelectionRegion.rectangle(Rect(0f, 0f, layerStack.canvasWidth.toFloat(), layerStack.canvasHeight.toFloat()))
        selectionRegion = selectionRegion?.let { all.combined(it, dev.tipstroke.core.geometry.SelectionOperation.SUBTRACT) } ?: all
        rasterView.selectionRegion = selectionRegion
        selectionListener?.invoke(selectionMode, true)
    }
    fun deleteSelection() {
        val selection = selectionRegion ?: return
        val edits = layerStack.selectedRasters().mapNotNull { layer ->
            val dirty = layer.tiles.cutSelection(selection)
            rasterView.invalidateTiles(dirty)
            if (dirty.isEmpty()) null else layer.tiles.history.takeLatestUndoTransaction()
        }
        commitCompoundEdit(edits)
        notifyLayers(); notifyHistory(); notifyVisiblePalette()
    }
    private var selectionMode = false
    private var selectionTool = SelectionTool.LASSO
    private val selectionPoints = mutableListOf<Point>()
    private var selectionRegion: SelectionRegion? = null
    private var lastSelection: SelectionRegion? = null
    private var selectionMoveMode = false
    private var transformSelectionOriginal: SelectionRegion? = null
    private var pendingSelectionTransform: SelectionTransform? = null
    private var selectionTransformFiltered = true
    private var selectionMoveStart: android.graphics.PointF? = null
    var selectionListener: ((Boolean, Boolean) -> Unit)? = null

    init {
        setWillNotDraw(false); setBackgroundColor(Color.rgb(23, 24, 27)); isMotionEventSplittingEnabled = false
        rasterView = RasterCanvasView(context, layerStack)
        rasterView.transformChangedListener = { transform ->
            videoView?.updateTransform(rasterView.transformMatrix, canvasWidthPx, canvasHeightPx)
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
            NativeEraser.warmUp()
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
                        val rasterStroke = if (stroke.style.brush.ink == null && pressureBehaviorEnabled(stroke.style.brush.pressureToOpacity)) {
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
        animation?.storageEnabled = true
        if (predictor == null) predictor = runCatching { MotionEventPredictor.newInstance(this) }.getOrNull()
        animation?.takeIf { it.media != null }?.let { configureMediaPlayback() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) { resumeAfterMediaSeek = false; stopAnimationPlayback() }
    }

    override fun onDetachedFromWindow() {
        flushRasterSamples()
        animation?.storageEnabled = false
        if (toolSmudgePointer != null) finishSmudgeTool()
        clearShapeHold()
        if (gpuStampPreview != null) {
            pendingSamples.remove(activeDrawingPointerId)
            pendingTargets.remove(activeDrawingPointerId)
            activeDrawingPointerId = null
            shapeStyle = null
            clearGpuStampPreview()
        }
        cancelFill()
        cancelMultiErase()
        clearMovePreview()
        stopAnimationPlayback()
        animation?.mediaPreview?.clear()
        videoView?.let { it.release(); removeView(it) }; videoView = null
        rasterView.hardwareVideo = false
        pendingAnimationFrame = null
        gestureHandler.removeCallbacks(hideBrushPreview)
        rasterView.stylusHoverPreview = null
        super.onDetachedFromWindow()
    }

    var fillListener: ((Boolean, String?) -> Unit)? = null
    private class FillRequest(
        val target: RasterLayerRuntime, val store: TileStore, val revision: Long,
        val x: Int, val y: Int, val color: Int, val selection: SelectionRegion?,
        @Volatile var released: Boolean,
    ) {
        @Volatile var cancelled = false
        @Volatile var progressPending = false
        var dropX = x
        var dropY = y
        // Main-thread continuation: either resume a paused worker or accept a ready result.
        var continuation: (() -> Unit)? = null
        fun resume() { val next = continuation; continuation = null; next?.invoke() }
    }
    private var fillRequest: FillRequest? = null
    private var revealingStore: TileStore? = null
    private val fillStep = object : Runnable {
        override fun run() {
            val store = revealingStore ?: return
            val reveal = store.fillReveal ?: return
            if (reveal.update(SystemClock.uptimeMillis())) {
                cancelFill()
            } else {
                rasterView.invalidateTiles(reveal.masks.keys)
                gestureHandler.postDelayed(this, 16L)
            }
        }
    }

    private var hoverAttempted = false
    private var hoverPoint: android.graphics.PointF? = null
    private var hoverJob: Runnable? = null
    private val fillFeedbackStep = object : Runnable {
        override fun run() {
            if (fillRequest?.released != true) return
            rasterView.invalidateFillFeedback()
            gestureHandler.postDelayed(this, 32L)
        }
    }

    private fun fillPoint(windowX: Float, windowY: Float): android.graphics.PointF {
        val location = IntArray(2)
        getLocationInWindow(location)
        return rasterView.screenToDocument(windowX - location[0], windowY - location[1])
    }

    private fun fillArgb(color: RgbaColor) = Color.argb((color.alpha * 255).roundToInt(),
        (color.red * 255).roundToInt(), (color.green * 255).roundToInt(), (color.blue * 255).roundToInt())

    /** One attempt per drag, after 125 ms within a 12-screen-pixel neighborhood. */
    fun hoverFillColor(windowX: Float, windowY: Float, color: RgbaColor) {
        if (!isEnabled || pendingAnimationFrame != null || !mediaFrameReady() || fillRequest?.released == true || revealingStore != null || activeDrawingPointerId != null) return
        val point = fillPoint(windowX, windowY)
        val target = layerStack.selectedRaster()?.takeIf { it.visible && it.opacity > 0f }
        if (target == null || point.x < 0 || point.y < 0 || point.x >= canvasWidthPx || point.y >= canvasHeightPx) {
            clearFillHover(resetAttempt = false)
            return
        }
        val previous = hoverPoint
        if (previous != null && hypot(point.x - previous.x, point.y - previous.y) * rasterView.transform.scale <= 12f) return
        clearFillHover(resetAttempt = false)
        if (hoverAttempted) return
        hoverPoint = point
        hoverJob = Runnable {
            hoverJob = null
            if (fillRequest == null && revealingStore == null && layerStack.selectedRaster() === target &&
                activeDrawingPointerId == null && pendingSamples.isEmpty() && finishedSamples.isEmpty()) {
                hoverAttempted = true
                prepareFill(target, point.x.toInt(), point.y.toInt(), fillArgb(color), selectionRegion, released = false)
            }
        }.also { gestureHandler.postDelayed(it, 125L) }
    }

    private fun clearFillHover(resetAttempt: Boolean) {
        hoverJob?.let(gestureHandler::removeCallbacks)
        hoverJob = null
        hoverPoint = null
        if (fillRequest?.released == false) {
            val request = fillRequest!!
            fillRequest = null
            request.cancelled = true
            request.resume()
        }
        if (resetAttempt) hoverAttempted = false
    }

    fun cancelFillHover() = clearFillHover(resetAttempt = true)

    private fun beginFillFeedback(x: Float, y: Float, color: Int) {
        rasterView.showFillFeedback(x, y, color)
        gestureHandler.removeCallbacks(fillFeedbackStep)
        gestureHandler.post(fillFeedbackStep)
    }

    /** Drop in window coordinates; the reveal clock is independent of zoom and pixel count. */
    fun dropColor(windowX: Float, windowY: Float, color: RgbaColor) {
        hoverJob?.let(gestureHandler::removeCallbacks)
        hoverJob = null
        hoverAttempted = false
        if (!isEnabled || pendingAnimationFrame != null || !mediaFrameReady() || activeDrawingPointerId != null || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()) {
            cancelFillHover(); return
        }
        val target = layerStack.selectedRaster()?.takeIf { it.visible && it.opacity > 0f }
        if (target == null) {
            cancelFillHover()
            fillListener?.invoke(false, "Choose a visible paint layer to fill.")
            return
        }
        val point = fillPoint(windowX, windowY)
        if (point.x < 0 || point.y < 0 || point.x >= canvasWidthPx || point.y >= canvasHeightPx) {
            cancelFillHover(); return
        }
        val argb = fillArgb(color)
        val prepared = fillRequest?.takeIf { !it.released && it.target === target && it.store === target.tiles &&
            it.revision == target.tiles.revision && it.color == argb && it.selection == selectionRegion &&
            hypot(point.x - it.x, point.y - it.y) * rasterView.transform.scale <= 14f }
        stopAnimationPlayback()
        if (prepared != null) {
            prepared.dropX = point.x.toInt(); prepared.dropY = point.y.toInt()
            prepared.released = true
            hoverPoint = null
            fillListener?.invoke(true, null)
            beginFillFeedback(point.x, point.y, argb)
            prepared.resume()
        } else {
            cancelFill()
            prepareFill(target, point.x.toInt(), point.y.toInt(), argb, selectionRegion)
        }
    }

    private fun prepareFill(target: RasterLayerRuntime, x: Int, y: Int, color: Int, selection: SelectionRegion?, released: Boolean = true) {
        if (referenceLayerId != null) {
            fillListener?.invoke(false, "Turn off Reference strokes to use Color Drop.")
            return
        }
        val store = target.tiles
        val source = try { store.fillSnapshot(gestureHandler) } catch (error: IllegalStateException) {
            fillListener?.invoke(false, error.message)
            return
        }
        val request = FillRequest(target, store, store.revision, x, y, color, selection, released)
        fillRequest = request
        if (released) {
            fillListener?.invoke(true, null)
            beginFillFeedback(x.toFloat(), y.toFloat(), color)
        }
        FillPreparation.executor.execute(object : Runnable {
            var fill: AnimatedFill? = null
            var lastProgressAt = 0L
            override fun run() {
                var masks = emptyMap<dev.tipstroke.core.geometry.TileCoordinate, android.graphics.Bitmap>()
                val result = runCatching {
                    if (!request.cancelled) {
                        val active = fill ?: AnimatedFill(source, x, y, color, selection, prepareReveal = true).also { fill = it }
                        val speculativeDeadline = System.nanoTime() + 150_000_000L
                        while (!active.complete && !request.cancelled) {
                            active.advance()
                            val now = SystemClock.uptimeMillis()
                            if (request.released && !request.cancelled && !request.progressPending &&
                                !active.complete && now - lastProgressAt >= 80L) {
                                lastProgressAt = now
                                request.progressPending = true
                                val preview = active.preparationPreview()
                                gestureHandler.post {
                                    request.progressPending = false
                                    if (!request.cancelled && fillRequest === request && store.revision == request.revision) {
                                        rasterView.updateFillPreparation(preview, active.progressCellSize)
                                    } else preview.recycle()
                                }
                            }
                            if (!request.released && !request.cancelled && !active.complete && System.nanoTime() >= speculativeDeadline) {
                                // Keep partial work, but spend no more CPU until release.
                                val worker = this
                                gestureHandler.post {
                                    if (request.cancelled || request.released) FillPreparation.executor.execute(worker)
                                    else request.continuation = { FillPreparation.executor.execute(worker) }
                                }
                                return
                            }
                        }
                        if (!request.cancelled) masks = active.takeRevealMasks()
                    }
                }
                source.discard()
                gestureHandler.post(object : Runnable {
                    fun dispose() { fill?.discard(); masks.values.forEach(android.graphics.Bitmap::recycle) }
                    override fun run() {
                        if (request.cancelled || fillRequest !== request) { dispose(); return }
                        if (result.isFailure) {
                            dispose(); fillRequest = null
                            rasterView.clearFillFeedback()
                            if (request.released) fillListener?.invoke(false, result.exceptionOrNull()?.message ?: "Fill could not be prepared.")
                            return
                        }
                        if (!request.released) { request.continuation = { run() }; return }
                        if (layerStack.layers.none { it === target } || target.tiles !== store) {
                            dispose(); cancelFill(); return
                        }
                        if (activeDrawingPointerId != null || smudgeTarget != null || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()) {
                            gestureHandler.postDelayed(this, 16L)
                            return
                        }
                        val active = requireNotNull(fill)
                        val movedSeed = request.dropX != x || request.dropY != y
                        if (store.revision != request.revision || (movedSeed &&
                                !active.containsDrop(request.dropX, request.dropY, store.colorAt(request.dropX, request.dropY)))) {
                            dispose(); fillRequest = null
                            prepareFill(target, request.dropX, request.dropY, color, selection)
                            return
                        }
                        fillRequest = null
                        rasterView.clearFillFeedback()
                        if (active.pixelCount == 0L) { dispose(); fillListener?.invoke(false, null); return }
                        val reveal = FillReveal(masks, SystemClock.uptimeMillis(), settings.gestures.fillAnimationMillis)
                        reveal.update(SystemClock.uptimeMillis())
                        store.commitFill(active.preview, color, active.pixelCount, reveal)
                        active.discard()
                        revealingStore = store
                        drawnColorListener?.invoke(RgbaColor(Color.red(color) / 255f, Color.green(color) / 255f,
                            Color.blue(color) / 255f, Color.alpha(color) / 255f))
                        notifyHistory(); notifyLayers(); notifyVisiblePalette()
                        reveal.start(SystemClock.uptimeMillis())
                        gestureHandler.post(fillStep)
                    }
                })
            }
        })
    }

    /** Stop preparation, or finish an already committed reveal without undoing newer strokes. */
    fun cancelFill() {
        gestureHandler.removeCallbacks(fillStep)
        gestureHandler.removeCallbacks(fillFeedbackStep)
        rasterView.clearFillFeedback()
        clearFillHover(resetAttempt = true)
        val pending = fillRequest
        fillRequest = null
        pending?.cancelled = true
        pending?.resume()
        val wasRevealing = revealingStore != null
        revealingStore?.let { store -> store.fillReveal?.discard(); store.fillReveal = null }
        revealingStore = null
        rasterView.invalidate()
        fillListener?.invoke(false, null)
        if (wasRevealing) notifyLayers()
    }

    fun undo() { if (reducingResolution) return; if (fillRequest != null) { cancelFill(); return }; cancelFill(); layerStack.selectedStore()?.history?.let { if (it.undo()) { rasterView.invalidate(); notifyHistory(); notifyVisiblePalette(); notifyLayers() } } }
    fun redo() { if (reducingResolution) return; cancelFill(); layerStack.selectedStore()?.history?.let { if (it.redo()) { rasterView.invalidate(); notifyHistory(); notifyVisiblePalette(); notifyLayers() } } }
    fun resetView() = rasterView.fitCanvas()
    val canvasWidthPx: Int get() = layerStack.canvasWidth
    val canvasHeightPx: Int get() = layerStack.canvasHeight
    val hasCanvasResizeChanges: Boolean get() = resizeOverlay?.hasChanges == true

    fun beginCanvasResize(onChange: (Int, Int) -> Unit): Boolean {
        if (resizeOverlay != null) return true
        cancelFill()
        if (activeDrawingPointerId != null || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()) return false
        transformBeforeResize = rasterView.transform
        rasterView.fitCanvas(rasterView.transform.rotationDegrees)
        rasterView.transform.let { fitted ->
            rasterView.updateTransform(fitted.panX, fitted.panY, fitted.scale * .67f, fitted.rotationDegrees)
        }
        CanvasResizeOverlay(context, rasterView, canvasWidthPx, canvasHeightPx, onChange).also {
            resizeOverlay = it
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            onChange(it.resizedWidth, it.resizedHeight)
        }
        return true
    }

    fun resetCanvasResizePreview() { resizeOverlay?.reset() }

    fun cancelCanvasResize(restoreView: Boolean = true) {
        resizeOverlay?.let(::removeView)
        resizeOverlay = null
        transformBeforeResize?.let { previous ->
            if (restoreView) rasterView.updateTransform(previous.panX, previous.panY, previous.scale, previous.rotationDegrees)
        }
        transformBeforeResize = null
    }

    fun applyCanvasResize(onComplete: (Boolean) -> Unit) {
        val active = animation
        if (active == null) { onComplete(applyCanvasResize()); return }
        active.prepareFrames(active.frames.indices.toList()) { result ->
            if (animation === active && result.isSuccess) onComplete(applyCanvasResize())
            else { result.exceptionOrNull()?.let { animationErrorListener?.invoke(it.message ?: "Could not load frames") }; onComplete(false) }
        }
    }

    fun applyCanvasResize(): Boolean {
        val preview = resizeOverlay ?: return false
        if (!preview.hasChanges) return false
        val left = preview.leftDelta; val top = preview.topDelta; val right = preview.rightDelta; val bottom = preview.bottomDelta
        val resized = resizeCanvas(left, top, right, bottom)
        if (resized) cancelCanvasResize(restoreView = false)
        return resized
    }

    /** Edge deltas are in document pixels: positive adds paper, negative crops it. */
    fun resizeCanvas(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        cancelFill()
        val width = layerStack.canvasWidth.toLong() + left + right
        val height = layerStack.canvasHeight.toLong() + top + bottom
        if (width !in 16L..8192L || height !in 16L..8192L || activeDrawingPointerId != null ||
            pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty() || motionRecording != null || strokeRecording != null
        ) return false
        val active = animation
        if (active != null && !active.framesResident(active.frames.indices.toList())) return false
        stopAnimationPlayback()
        clearSelection()
        lastSelection = null
        layerStack.resizeCanvas(width.toInt(), height.toInt(), left, top, animation)
        rasterView.fitCanvas(rasterView.transform.rotationDegrees)
        notifyHistory()
        notifyLayers()
        notifyVisiblePalette()
        publishAnimation()
        return true
    }
    private var reducingResolution = false

    /** Uniformly resample every raster cel without decoding the entire timeline. */
    fun reduceResolution(factor: Float, onComplete: (Result<Unit>) -> Unit) {
        cancelFill()
        if (reducingResolution || !isEnabled || (factor != .5f && factor != .25f) ||
            kotlin.math.ceil(canvasWidthPx * factor) < 16 || kotlin.math.ceil(canvasHeightPx * factor) < 16 ||
            activeDrawingPointerId != null || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty() ||
            motionRecording != null || strokeRecording != null || imageGestureInProgress) {
            onComplete(Result.failure(IllegalStateException("Finish editing before reducing resolution; each edge must remain at least 16 pixels.")))
            return
        }
        stopAnimationPlayback()
        val original = layerStack
        val activeAnimation = animation
        val operation = ResolutionReduction(original, activeAnimation, factor, CelStorage.forFilesDirectory(context.filesDir))
        reducingResolution = true
        isEnabled = false
        ProjectPersistence.executor.execute {
            val prepared = runCatching { operation.prepare() }
            ProjectPersistence.mainHandler.post {
                val result = prepared.mapCatching {
                    check(layerStack === original && animation === activeAnimation) { "Drawing changed while reducing resolution" }
                    operation.commit()
                    clearSelection(); lastSelection = null
                    cancelCanvasResize(restoreView = false)
                    animationThumbnails.clear(); pendingThumbnails.clear(); pendingAnimationFrame = null
                    processRecorder?.stop("Recording stopped: canvas resolution changed.")
                    processRecorder?.detach()
                    configureMediaPlayback()
                    rasterView.fitCanvas(rasterView.transform.rotationDegrees)
                    warmAnimationNeighbours()
                    notifyHistory(); notifyLayers(); notifyVisiblePalette(); publishAnimation()
                }
                operation.release()
                reducingResolution = false
                isEnabled = true
                onComplete(result)
            }
        }
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
            sizePx = resolvedBrushSize(),
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
    fun setChromeOcclusionInsets(topPx: Float, bottomPx: Float, approachMarginPx: Float, leftPx: Float = 0f, rightPx: Float = 0f) {
        chromeLeftInsetPx = leftPx.coerceAtLeast(0f)
        chromeRightInsetPx = rightPx.coerceAtLeast(0f)
        chromeTopInsetPx = topPx.coerceAtLeast(0f)
        chromeBottomInsetPx = bottomPx.coerceAtLeast(0f)
        chromeApproachMarginPx = approachMarginPx.coerceAtLeast(0f)
    }
    val maximumLayers: Int get() = LayerCapacity.maximum(context, layerStack.canvasWidth, layerStack.canvasHeight)
    var memoryPressureListener: ((MemoryPressure) -> Unit)? = null
    var capacityWarningListener: ((String) -> Unit)? = null
    private fun canAddLayers(count: Int = 1): Boolean {
        if (layerStack.layers.size + count <= maximumLayers) return true
        capacityWarningListener?.invoke("Layer limit reached (${layerStack.layers.size}/$maximumLayers). Delete unused layers, or use a smaller canvas.")
        return false
    }
    fun addPaintLayer() { if (!canAddLayers()) return; layerStack.addRaster(); animation?.register(layerStack.selected()); notifyLayers(); notifyHistory(); publishAnimation() }
    fun duplicateSelectedLayers() {
        if (!canAddLayers(layerStack.selectedLayerIds().size)) return
        if (layerStack.duplicateSelected().isNotEmpty()) {
            layerStack.layers.forEach { animation?.register(it) }
            notifyLayers(); notifyHistory(); notifyVisiblePalette(); publishAnimation()
        }
    }
    fun addImage(uri: android.net.Uri): Result<Unit> = if (!canAddLayers()) {
        Result.failure(IllegalStateException("Layer limit reached. Delete an unused layer before importing a photo."))
    } else layerStack.addImage(uri).map {
        animation?.register(layerStack.selected()); notifyLayers(); notifyHistory(); publishAnimation()
    }
    fun selectLayerGroup(groupId: String) { layerStack.selectGroup(groupId); notifyLayers(); notifyHistory() }
    fun selectLayer(id: LayerId) { layerStack.select(id); notifyLayers(); notifyHistory() }
    fun toggleLayerSelection(id: LayerId) { layerStack.toggleAdditionalSelection(id); notifyLayers(); notifyHistory() }
    fun beginOpacityAdjustment() { opacityAdjustmentInProgress = true }
    fun finishOpacityAdjustment() {
        if (!opacityAdjustmentInProgress) return
        opacityAdjustmentInProgress = false
        if (!imageGestureInProgress) processRecorder?.sync(layerStack, animation != null, animation = animation)
    }
    fun setSelectedLayerClip(sourceId: LayerId?) {
        if (layerStack.setClipSource(sourceId)) { notifyLayers(); notifyVisiblePalette(); notifyHistory() }
    }
    fun setSelectedLayerBlendMode(mode: LayerBlendMode) { layerStack.setBlendMode(mode); notifyLayers(); notifyVisiblePalette(); notifyHistory() }
    fun setSelectedLayerOpacity(value: Float) { layerStack.setOpacity(value); notifyLayers() }
    fun setCanvasBackgroundColor(color: Int) { layerStack.setBackground(color = color); notifyLayers() }
    fun setCanvasBackgroundOpacity(opacity: Float) { layerStack.setBackground(opacity = opacity); notifyLayers() }
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
        if (!enabled) finishPhotoEdit()
        if (enabled && !imageTransformMode) layerStack.selectedImage()?.let { photoSessionOriginal=it.id to it.transform }
        imageTransformMode = enabled && layerStack.selectedImage() != null
        if (imageTransformMode) { selectionMode = false; selectionMoveMode = false; clearMovePreview() }
        rasterView.showImageTransformBounds = imageTransformMode
        rasterView.invalidate()
    }
    fun setSelectionMode(enabled: Boolean) {
        if (!enabled && selectionTool == SelectionTool.POLYGON) cancelPolygon()
        selectionMode = enabled
        if (enabled) { selectionMoveMode = false; setImageTransformMode(false) }
        selectionListener?.invoke(selectionMode, selectionRegion != null)
    }
    fun setSelectionTool(tool: SelectionTool) {
        if (tool != selectionTool && selectionTool == SelectionTool.POLYGON) cancelPolygon()
        selectionTool = tool
        selectionMoveMode = false
        selectionMode = true
        selectionListener?.invoke(true, selectionRegion != null)
    }
    fun setSelectionMoveMode(enabled: Boolean) {
        // Compose can deliver the previous move toggle after a transform session starts.
        if (transformSelectionOriginal != null) return
        if (enabled && selectionRegion == null) selectAll()
        if (!enabled) clearMovePreview()
        selectionMoveMode = enabled && selectionRegion != null &&
            (layerStack.selectedRasters().isNotEmpty() || layerStack.selectedImages().isNotEmpty())
        if (selectionMoveMode) selectionMode = false
        selectionListener?.invoke(selectionMode, selectionRegion != null)
    }
    fun beginSelectionTransform(): Boolean {
        val selection = selectionRegion ?: return false
        if (activeDrawingPointerId != null || pendingAnimationFrame != null || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()) return false
        val ids = layerStack.selectedLayerIds()
        val targets = layerStack.layers.filter { it.id in ids && it.activeAtFrame }
        if (targets.isEmpty() || targets.any { it !is RasterLayerRuntime }) return false
        cancelFill(); clearMovePreview()
        selectionMoveMode = false
        selectionMode = false
        transformSelectionOriginal = selection
        targets.filterIsInstance<RasterLayerRuntime>().forEach { rasterView.selectionSources[it.id]=it.tiles.duplicate(selection) }
        rasterView.movingLayerIds = ids
        rasterView.movingSelectionPath = selection.toAndroidPath()
        previewSelectionTransform(1f, 1f, 0f, true)
        return true
    }
    fun previewSelectionTransform(scaleX: Float, scaleY: Float, rotation: Float, filtered: Boolean) {
        val selection = transformSelectionOriginal ?: return
        val bounds = selection.bounds
        val transform = SelectionTransform(Point((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f),
            scaleX, scaleY, rotation, pendingSelectionTransform?.translation ?: Point(0f,0f),
            pendingSelectionTransform?.warp, bounds)
        showSelectionTransform(transform)
        selectionTransformFiltered = filtered
        rasterView.selectionPreviewFiltered = filtered
        rasterView.invalidate()
    }
    fun selectionTransformScale(): Pair<Float, Float> = pendingSelectionTransform?.let { abs(it.scaleX) to abs(it.scaleY) } ?: (1f to 1f)
    fun setSelectionTransformScale(x: Float, y: Float) {
        pendingSelectionTransform?.let { showSelectionTransform(it.copy(scaleX=kotlin.math.sign(it.scaleX)*x,scaleY=kotlin.math.sign(it.scaleY)*y)) }
    }
    fun setSelectionTransformRotation(degrees: Float) {
        pendingSelectionTransform?.let { showSelectionTransform(it.copy(rotationDegrees=degrees)) }
    }
    fun setSelectionTransformFilter(filtered: Boolean) {
        selectionTransformFiltered=filtered
        rasterView.selectionPreviewFiltered=filtered
        rasterView.invalidate()
    }
    fun cancelSelectionTransform() {
        transformSelectionOriginal = null
        pendingSelectionTransform = null
        clearMovePreview()
        selectionMode = true
        selectionListener?.invoke(selectionMode, selectionRegion != null)
    }
    fun applySelectionTransform(): Boolean {
        val selection = transformSelectionOriginal ?: return false
        val transform = pendingSelectionTransform ?: return false
        val ids = rasterView.movingLayerIds
        val edits = mutableListOf<UndoTransaction>()
        layerStack.layers.filterIsInstance<RasterLayerRuntime>().filter { it.id in ids && it.activeAtFrame }.forEach { layer ->
            if (layer.tiles.transformSelection(selection, transform, selectionTransformFiltered).isNotEmpty()) {
                layer.tiles.history.takeLatestUndoTransaction()?.let(edits::add)
            }
        }
        val transformed = selection.transformed(transform)
        if (edits.isNotEmpty()) {
            edits += object : UndoTransaction {
                private fun regionBytes(region: SelectionRegion?): Long = if (region == null) 0L else
                    64L + region.points.size * 8L + regionBytes(region.left) + regionBytes(region.right)
                override val estimatedBytes = regionBytes(selection) + regionBytes(transformed)
                private fun show(region: SelectionRegion) {
                    selectionRegion = region; rasterView.selectionRegion = region
                    selectionListener?.invoke(selectionMode, true)
                }
                override fun undo() = show(selection)
                override fun redo() = show(transformed)
            }
            commitCompoundEdit(edits)
            selectionRegion = transformed
            rasterView.selectionRegion = transformed
        }
        cancelSelectionTransform()
        rasterView.invalidate(); notifyHistory(); notifyLayers(); notifyVisiblePalette()
        return true
    }

    fun restoreSelection(): Boolean {
        val region = selectionRegion ?: lastSelection ?: return false
        clearMovePreview()
        selectionMoveMode = false
        selectionRegion = region
        rasterView.selectionRegion = region
        selectionListener?.invoke(selectionMode, true)
        rasterView.invalidate()
        return true
    }
    fun clearSelection() {
        selectionRegion?.let { lastSelection = it }
        transformSelectionOriginal = null; pendingSelectionTransform = null
        selectionPointerId = null
        previousSelection = null
        rasterView.previousSelectionOutline = null
        clearMovePreview()
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
        if (!canAddLayers(layerStack.selectedRasters().size)) return
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
        if (!canAddLayers(layerStack.selectedRasters().size)) return
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
    fun reorderLayer(sourceId: LayerId, targetId: LayerId, afterTarget: Boolean) {
        if (layerStack.reorder(sourceId, targetId, afterTarget)) notifyLayers()
    }
    fun groupLayer(sourceId: LayerId, targetId: LayerId) {
        if (layerStack.group(sourceId, targetId)) notifyLayers()
    }
    fun ungroupLayers(groupId: String) {
        if (layerStack.ungroup(groupId)) notifyLayers()
    }
    fun deleteSelectedLayer() { if (layerStack.deleteSelected()) { animation?.unregisterMissingLayers(); notifyLayers(); notifyHistory(); publishAnimation() } }
    fun publishLayers() { notifyLayers(); notifyHistory(); notifyVisiblePalette() }
    private var importingClip = false

    /** Enable a single-track edit without copying decoded video or raster cels. */
    fun enableVideoEditing() {
        if (importingClip || activeDrawingPointerId != null || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()) return
        stopAnimationPlayback()
        if (animation == null) enableAnimation(true)
        val active = animation ?: return
        active.editingSource()?.let { source ->
            if (active.media?.edit == null) { active.replaceMedia(source, false); configureMediaPlayback(); publishAnimation() }
        }
    }

    fun addTimelineMedia(uri: android.net.Uri) {
        if (importingClip || activeDrawingPointerId != null || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()) return
        stopAnimationPlayback()
        if (animation == null) enableAnimation(true)
        val active = animation ?: return
        importingClip = true; publishAnimation()
        ProjectPersistence.executor.execute {
            val result = runCatching { importClip(context, uri) }
            ProjectPersistence.mainHandler.post {
                importingClip = false
                if (animation !== active || !isAttachedToWindow) { result.getOrNull()?.second?.file?.delete(); return@post }
                result.onSuccess { (name, asset) ->
                    runCatching {
                        val previous = active.editingSource()
                        val id = java.util.UUID.randomUUID().toString()
                        val clip = dev.tipstroke.core.model.MediaClip(id, id, name, 0, asset.durationUs)
                        val edit = dev.tipstroke.core.model.VideoEdit(previous?.edit?.clips.orEmpty() + clip)
                        val source = MediaSource(asset.file, "sequence", previous?.width ?: canvasWidthPx,
                            previous?.height ?: canvasHeightPx, edit.durationUs, maxOf(previous?.frameRate ?: 30.0, asset.frameRate),
                            previous?.canvasScale ?: 1f, edit, previous?.assets.orEmpty() + (id to asset))
                        active.replaceMedia(source)
                        active.showMediaTime(edit.startUs(edit.clips.lastIndex))
                        configureMediaPlayback(); publishAnimation()
                    }.onFailure { asset.file.delete(); animationErrorListener?.invoke(it.message ?: "Cannot add clip") }
                }.onFailure { animationErrorListener?.invoke(it.message ?: "Cannot import clip") }
                publishAnimation()
            }
        }
    }

    fun updateVideoEdit(edit: dev.tipstroke.core.model.VideoEdit, seekUs: Long? = null) {
        val active = animation ?: return
        val source = active.editingSource() ?: return
        if (activeDrawingPointerId != null || importingClip || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()) return
        stopAnimationPlayback()
        runCatching {
            active.replaceMedia(source.copy(durationUs = edit.durationUs, edit = edit))
            seekUs?.let(active::showMediaTime)
            configureMediaPlayback(); publishAnimation()
        }.onFailure { animationErrorListener?.invoke(it.message ?: "Cannot edit timeline") }
    }

    fun undoVideoEdit(redo: Boolean = false) {
        if (importingClip || activeDrawingPointerId != null || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()) return
        stopAnimationPlayback(); animation?.undoVideoEdit(redo); configureMediaPlayback(); publishAnimation()
    }

    private fun configureMediaPlayback() {
        videoView?.let { it.release(); removeView(it) }; videoView = null
        rasterView.hardwareVideo = false
        mediaSeeking = false; requestedVideoTimeUs = null; mediaCelLoading = null; resumeAfterMediaSeek = false
        val active = animation ?: return
        val source = active.media ?: return
        if (source.kind == "video" && isAttachedToWindow) {
            videoView = MediaVideoView(context, source, { time, playing, seeking ->
                if (animation === active) {
                    mediaSeeking = seeking
                    if (!seeking) { requestedVideoTimeUs = null; presentMediaTime(time, playing); resumeMediaIfRequested() }
                    else publishAnimation()
                }
            }, { error ->
                mediaSeeking = false; requestedVideoTimeUs = null; stopAnimationPlayback(); animationErrorListener?.invoke(error); publishAnimation()
            }).also {
                addView(it, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                it.updateTransform(rasterView.transformMatrix, canvasWidthPx, canvasHeightPx)
                rasterView.hardwareVideo = true
            }
        }
        seekMedia(active.mediaPositionUs)
    }

    /** Free scrubbing changes only the source clock. No new drawing cels are created here. */
    fun seekMedia(timeUs: Long) {
        val active = animation ?: return
        val source = active.media ?: return
        if (activeDrawingPointerId != null || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()) return
        resumeAfterMediaSeek = false
        stopAnimationPlayback(); cancelFill(); clearSelection()
        val time = timeUs.coerceIn(0, source.durationUs - 1)
        mediaSeeking = true
        val video = videoView
        if (video != null) {
            // Keep the current overlay until the player acknowledges the latest seek.
            requestedVideoTimeUs = time
            video.seek(time)
            return
        }
        presentMediaTime(time, false)
        active.mediaPreview?.prepare(time) { result ->
            if (animation === active && active.mediaPositionUs == time) {
                mediaSeeking = false
                result.onFailure { animationErrorListener?.invoke(it.message ?: "Cannot seek media") }
                rasterView.invalidate(); publishAnimation(); resumeMediaIfRequested()
            }
        } ?: run { mediaSeeking = false; publishAnimation() }
    }

    private fun resumeMediaIfRequested() {
        if (resumeAfterMediaSeek && !mediaSeeking) {
            resumeAfterMediaSeek = false
            toggleAnimationPlayback()
        }
    }

    private fun presentMediaTime(timeUs: Long, playing: Boolean) {
        val active = animation ?: return
        animationPlaying = playing
        rasterView.animationPlaying = playing
        val changed = active.showMediaTime(timeUs)
        val index = active.mediaIndexAt(active.mediaPositionUs)
        if (index >= 0 && !active.framesResident(listOf(index))) {
            val id = active.frames[index].id
            if (mediaCelLoading != id) {
                mediaCelLoading = id
                active.prepareFrames(listOf(index)) { result ->
                    if (animation === active && mediaCelLoading == id) {
                        mediaCelLoading = null
                        result.onFailure { animationErrorListener?.invoke(it.message ?: "Cannot load drawing") }
                        rasterView.invalidate(); publishAnimation()
                    }
                }
            }
        } else mediaCelLoading = null
        if (changed) {
            rasterView.invalidate()
            if (!playing) {
                switchingAnimationFrame = true
                try { notifyLayers(); notifyHistory() } finally { switchingAnimationFrame = false }
            }
            // Only authored drawing cels are decoded, independent of the video frame rate.
            val next = active.frames.indexOfFirst { (it.sourceTimeUs ?: 0) > active.mediaPositionUs }
            val keep = animationNeighbourIndices(active) + listOfNotNull(next.takeIf { it >= 0 })
            active.prepareFrames(keep) {
                if (animation === active) {
                    active.trimCleanFrames(animationNeighbourIndices(active) +
                        listOfNotNull(active.frames.indexOfFirst { (it.sourceTimeUs ?: 0) > active.mediaPositionUs }.takeIf { it >= 0 }))
                    if (!animationPlaying) rasterView.invalidate()
                }
            }
        }
        val now = SystemClock.uptimeMillis()
        if (!playing || now - lastMediaUiAt >= 100L) { lastMediaUiAt = now; publishAnimation() }
    }

    fun enableAnimation(existingAsBackground: Boolean) {
        if (animation != null || activeDrawingPointerId != null) return
        animation = AnimationRuntime(layerStack, existingAsBackground, storage = CelStorage.forFilesDirectory(context.filesDir),
            storageError = { animationErrorListener?.invoke("Could not store inactive drawings: ${it.message}") })
        rasterView.animation = animation
        rasterView.invalidate()
        publishAnimation()
    }
    fun selectAnimationFrame(index: Int) = selectAnimationFrame(index, fromPlayback = false)
    private fun selectAnimationFrame(index: Int, fromPlayback: Boolean) {
        if (fromPlayback && pendingAnimationFrame != null) return
        cancelFill()
        val active = animation ?: return
        if (activeDrawingPointerId != null) return
        if (!fromPlayback) stopAnimationPlayback()
        if (index !in active.frames.indices) return
        if (active.media != null) { seekMedia(active.frames[index].sourceTimeUs ?: 0L); return }
        val frameId = active.frames[index].id
        if (!active.framesResident(listOf(index))) {
            if (pendingAnimationFrame == frameId) return
            pendingAnimationFrame = frameId
            publishAnimation()
            active.prepareFrames(listOf(index)) { result ->
                if (animation === active && pendingAnimationFrame == frameId) {
                    pendingAnimationFrame = null
                    result.fold(onSuccess = {
                        val target = active.frames.indexOfFirst { it.id == frameId }
                        if (target >= 0) selectAnimationFrame(target, fromPlayback)
                    }, onFailure = { animationErrorListener?.invoke(it.message ?: "Could not load frame") })
                    publishAnimation()
                }
            }
            return
        }
        pendingAnimationFrame = null
        active.select(index)
        warmAnimationNeighbours()
        clearSelection()
        rasterView.invalidate()
        switchingAnimationFrame = true
        try { notifyLayers() } finally { switchingAnimationFrame = false }
        notifyHistory(); notifyVisiblePalette(); publishAnimation()
    }
    fun addAnimationFrame() {
        val active = animation ?: return
        if (active.media != null) {
            stopAnimationPlayback()
            if (mediaSeeking || activeDrawingPointerId != null || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()) return
            runCatching { active.addMediaDrawing() }.onFailure { animationErrorListener?.invoke(it.message ?: "Cannot add drawing") }
            seekMedia(active.mediaPositionUs)
            notifyLayers(); notifyHistory(); publishAnimation()
            return
        }
        stopAnimationPlayback()
        active.addBlankFrame()
        warmAnimationNeighbours()
        rasterView.invalidate(); notifyLayers(); notifyHistory(); publishAnimation()
    }
    fun duplicateAnimationFrame() {
        val active = animation ?: return
        if (active.media != null) return
        stopAnimationPlayback()
        active.duplicateFrame()
        warmAnimationNeighbours()
        rasterView.invalidate(); notifyLayers(); notifyHistory(); publishAnimation()
    }
    fun deleteAnimationFrame() {
        val active = animation ?: return
        if (active.media != null) {
            if (mediaSeeking || !active.mediaDrawingActive || activeDrawingPointerId != null) return
            stopAnimationPlayback()
            val time = active.mediaPositionUs
            if (active.frames.size == 1) { animationErrorListener?.invoke("Erase this drawing, or add another drawing before deleting it."); return }
            active.deleteFrame(); seekMedia(time); notifyLayers(); notifyHistory(); publishAnimation(); return
        }
        stopAnimationPlayback()
        val deleting = active.currentFrame.id
        val target = if (active.selectedIndex < active.frames.lastIndex) active.selectedIndex + 1 else active.selectedIndex - 1
        active.prepareFrames(listOf(target)) { result ->
            if (animation === active && result.isSuccess && activeDrawingPointerId == null && active.currentFrame.id == deleting) {
                active.deleteFrame()
                selectAnimationFrame(active.selectedIndex)
                warmAnimationNeighbours()
                rasterView.invalidate(); notifyLayers(); notifyHistory(); publishAnimation()
            }
        }
    }
    fun moveAnimationFrame(delta: Int) {
        val active = animation ?: return
        if (active.media != null) return
        stopAnimationPlayback()
        active.moveFrame(active.selectedIndex, active.selectedIndex + delta)
        rasterView.invalidate(); publishAnimation()
    }
    fun reorderAnimationFrame(source: Int, target: Int) {
        val active = animation ?: return
        if (active.media != null) return
        if (source !in active.frames.indices || target !in active.frames.indices || source == target) return
        active.prepareFrames(listOf(source)) { result ->
            if (animation === active && result.isSuccess && activeDrawingPointerId == null) {
                active.moveFrame(source, target)
                selectAnimationFrame(target)
                rasterView.invalidate(); publishAnimation()
            }
        }
    }
    fun animationFrameThumbnail(frameId: FrameId, sizePx: Int = 96): android.graphics.Bitmap? {
        animationThumbnails[frameId]?.takeIf { it.width == sizePx || it.height == sizePx }?.let { return it }
        val active = animation ?: return null
        val index = active.frames.indexOfFirst { it.id == frameId }
        if (index < 0) return null
        if (active.media != null) return null
        if (!active.framesResident(listOf(index))) {
            if (pendingThumbnails.add(frameId)) active.prepareFrames(listOf(index)) { result ->
                pendingThumbnails.remove(frameId)
                if (animation === active && result.isSuccess) {
                    active.thumbnail(frameId, sizePx)?.let { animationThumbnails[frameId] = it }
                    while (animationThumbnails.size > 16) animationThumbnails.remove(animationThumbnails.keys.first())
                    if (strokeRecording == null && motionRecording == null) active.trimCleanFrames(animationNeighbourIndices(active))
                    thumbnailRevision++; publishAnimation()
                }
            }
            return null
        }
        return active.thumbnail(frameId, sizePx)?.also {
            animationThumbnails[frameId] = it
            while (animationThumbnails.size > 16) animationThumbnails.remove(animationThumbnails.keys.first())
        }
    }
    fun setAnimationExposure(value: Int) {
        animation?.let {
            if (it.media != null) { it.setMediaDurationTicks(value); presentMediaTime(it.mediaPositionUs, animationPlaying) }
            else it.setExposure(value)
        }
        rasterView.invalidate(); publishAnimation()
    }
    fun setAnimationFps(value: Int) {
        val active = animation ?: return
        val nextFps = value.coerceIn(1, 60)
        if (active.media != null) { active.fps = nextFps; publishAnimation(); return }
        if (strokeRecording != null) stopAnimationPlayback()
        else if (animationPlaying) {
            val now = SystemClock.uptimeMillis()
            val tick = (now - playbackStartedAt) * active.fps / 1000L
            playbackStartedAt = now - tick * 1000L / nextFps
        }
        active.fps = nextFps
        publishAnimation()
    }
    fun setAnimationPlaybackMode(mode: PlaybackMode) { animation?.playbackMode = mode; publishAnimation() }
    fun setOnionSkin(before: Int, after: Int, opacity: Float) {
        animation?.apply {
            onionBefore = before.coerceIn(0, 6)
            onionAfter = after.coerceIn(0, 6)
            onionOpacity = opacity.coerceIn(0f, 1f)
        }
        warmAnimationNeighbours()
        rasterView.invalidate(); publishAnimation()
    }
    fun setAnimationBackground(layerId: LayerId, background: Boolean) {
        animation?.setBackground(layerId, background)
        rasterView.invalidate(); notifyLayers(); publishAnimation()
    }
    fun copyCelToNewLayer(move: Boolean) {
        if (!canAddLayers()) return
        if (animation?.copySelectedRasterCelToNewLayer(move) == null) return
        rasterView.invalidate(); notifyLayers(); notifyHistory(); publishAnimation()
    }
    fun toggleAnimationPlayback() {
        val active = animation ?: return
        if (animationPlaying) { stopAnimationPlayback(); return }
        if (active.media != null) {
            if (mediaSeeking || activeDrawingPointerId != null || pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()) return
            if (active.mediaPositionUs >= active.media!!.durationUs - 2_000) {
                seekMedia(0); resumeAfterMediaSeek = true; resumeMediaIfRequested(); return
            }
            animationPlaying = true; rasterView.animationPlaying = true
            videoView?.play() ?: run {
                mediaPlaybackOffsetUs = active.mediaPositionUs
                playbackStartedAt = SystemClock.uptimeMillis(); gestureHandler.post(playbackStep)
            }
            publishAnimation(); return
        }
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
        if (active.media != null) return false
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
        if (!canAddLayers()) return false
        val active = animation ?: return false
        if (active.media != null) return false
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
        videoView?.pause()
        animation?.mediaPreview?.cancelPending()
        pendingAnimationFrame = null
        strokeRecording = null
        rasterView.animationPlaying = false
        gestureHandler.removeCallbacks(playbackStep)
        publishAnimation()
    }
    private fun updateAnimationStoragePins() {
        animation?.trimmingSuspended = strokeRecording != null || motionRecording != null ||
            pendingSamples.isNotEmpty() || finishedSamples.isNotEmpty()
    }
    private fun publishAnimation() {
        updateAnimationStoragePins()
        if (!animationPlaying || strokeRecording != null) {
            processRecorder?.sync(layerStack, animation != null, animation = animation)
        }
        publishAnimationUi()
    }
    private fun publishAnimationUi() {
        val coverageAnimation = animation
        if (!animationPlaying) coverageAnimation?.refreshCoverage { if (animation === coverageAnimation) publishAnimationUi() }
        animationListener?.invoke(animation?.state(animationPlaying, recordingKind(), thumbnailRevision)?.copy(mediaPositionUs = requestedVideoTimeUs ?: animation?.mediaPositionUs ?: 0L, loading = importingClip || pendingAnimationFrame != null || mediaSeeking || mediaCelLoading != null, hasMedia = animation?.media != null))
    }
    fun publishDiagnostics() {
        updateAnimationStoragePins()
        if (strokeRecording == null && motionRecording == null) animation?.let { it.trimCleanFrames(animationNeighbourIndices(it)) }
        val memory = memoryBudgetAdvisor.snapshot(
            layerStack.canvasWidth,
            layerStack.canvasHeight,
            layerStack.estimatedDocumentBytes() + (animation?.inactiveTileBytes() ?: 0L),
        )
        memoryPressureListener?.invoke(if (memory.documentBytes == 0L) MemoryPressure.NORMAL else memory.pressure)
        if (settings.debug) publishDiagnostics(lastDiagnostics.withMemory(memory))
    }
    fun configureBlank(widthPx: Int, heightPx: Int) {
        stopAnimationPlayback(); animation?.mediaPreview?.clear(); cancelColorPick()
        videoView?.let { it.release(); removeView(it) }; videoView = null
        rasterView.hardwareVideo = false; mediaSeeking = false; requestedVideoTimeUs = null; mediaCelLoading = null
        cancelFill()
        require(widthPx in 16..8192 && heightPx in 16..8192)
        opacityAdjustmentInProgress = false
        processRecorder?.detach()
        processRecorder = null
        layerStack = createLayerStack(widthPx, heightPx)
        processRecorder = ProcessRecorder(widthPx, heightPx)
        processReadError = null
        animation?.storageEnabled = false
        animation = null
        rasterView.animation = null
        clearSelection()
        lastSelection = null
        rasterView.layerStack = layerStack
        rasterView.fitCanvas()
        publishLayers()
        publishAnimation()
    }

    fun setPixelTool(tool: PixelTool?) {
        settings.pixelTool = tool
        rasterView.pixelToolActive = tool != null
    }

    var loadingProgressListener: ((String) -> Unit)? = null

    fun loadProject(library: DrawingLibrary, id: String, onComplete: (Result<Unit>) -> Unit) {
        cancelFill()
        openProject(library, id, createIfMissing = false, onComplete = onComplete)
    }

    /** Resolve existence on the same queue as saves, including saves from the previous activity. */
    fun openProject(
        library: DrawingLibrary, id: String, createIfMissing: Boolean,
        widthPx: Int = 2048, heightPx: Int = 2048,
        name: String = "Untitled drawing",
        onComplete: (Result<Unit>) -> Unit,
    ) {
        isEnabled = false
        loadingProgressListener?.invoke("Waiting for local project operations…")
        LocalDiagnostics.record("project open requested id=$id createIfMissing=$createIfMissing")
        ProjectPersistence.executor.execute {
            val loaded = runCatching {
                val directory = library.projectDirectory(id)
                ProjectPersistence.recoverInterruptedSave(directory)
                if (!directory.exists() && createIfMissing) {
                    require(widthPx in 16..8192 && heightPx in 16..8192)
                    val layerId = LayerId(java.util.UUID.randomUUID().toString())
                    val blank = DrawingSnapshot(widthPx, heightPx, layerId,
                        listOf(SavedRasterSnapshot(layerId, "Paint 1", true, 1f, emptyMap())))
                    ProjectPersistence.save(context.contentResolver, library, id, name, blank).getOrThrow()
                }
                ProjectPersistence.load(directory, lazyAnimation = true) { stage ->
                    ProjectPersistence.mainHandler.post { loadingProgressListener?.invoke(stage) }
                }.getOrThrow()
            }
            ProjectPersistence.mainHandler.post {
                val result = loaded.mapCatching { project ->
                    opacityAdjustmentInProgress = false
                    processRecorder?.detach()
                    processRecorder = null
                    val replacement = createLayerStack(project.widthPx, project.heightPx)
                    layerStack = replacement
                    clearSelection()
                    lastSelection = null
                    rasterView.layerStack = replacement
                    replacement.replaceWith(project)
                    animation?.mediaPreview?.clear()
                    animation?.storageEnabled = false
                    animation = project.animation?.let { saved ->
                        AnimationRuntime(replacement, true, saved.frames, saved.fps, saved.playbackMode,
                            CelStorage.forFilesDirectory(context.filesDir),
                            { animationErrorListener?.invoke("Could not store inactive drawings: ${it.message}") }).apply { restore(saved) }
                    }
                    animationThumbnails.clear(); pendingThumbnails.clear(); pendingAnimationFrame = null
                    warmAnimationNeighbours()
                    processReadError = project.recordingError
                    project.recordingError?.let { LocalDiagnostics.record("project recording unreadable id=$id: $it") }
                    processRecorder = if (project.recordingError == null) ProcessRecorder(project.widthPx, project.heightPx, project.processRecording) else null
                    processRecorder?.sync(replacement, animation != null, restoring = project.processRecording != null, animation = animation)
                    rasterView.animation = animation
                    configureMediaPlayback()
                    rasterView.fitCanvas(project.galleryRotationQuarterTurns * 90f)
                    publishLayers()
                    publishAnimation()
                }
                isEnabled = result.isSuccess
                LocalDiagnostics.record("project open ${if (result.isSuccess) "complete" else "failed"} id=$id",
                    result.exceptionOrNull())
                onComplete(result)
            }
        }
    }

    fun saveProject(library: DrawingLibrary, id: String, name: String, onComplete: (Result<DrawingSummary>) -> Unit = {}) {
        if (!isEnabled) {
            onComplete(Result.failure(IllegalStateException("Drawing has not finished opening")))
            return
        }
        val captured = runCatching { layerStack.snapshot(galleryQuarterTurns(rasterView.transform.rotationDegrees), animation) }
        val snapshot = captured.getOrElse {
            LocalDiagnostics.record("project snapshot failed id=$id", it)
            onComplete(Result.failure(it))
            return
        }
        val recordingSnapshot = processRecorder?.captureSnapshot()
        val rasterTiles = snapshot.layers.filterIsInstance<SavedRasterSnapshot>().sumOf { it.tiles.size }
        LocalDiagnostics.record("project save queued id=$id layers=${snapshot.layers.size} rasterTiles=$rasterTiles")
        ProjectPersistence.executor.execute {
            val result = try {
                runCatching {
                    ProjectPersistence.save(context.contentResolver, library, id, name,
                        snapshot.copy(processRecording = recordingSnapshot?.get())).getOrThrow()
                }
            }
            finally { snapshot.recycle() }
            LocalDiagnostics.record("project save ${if (result.isSuccess) "complete" else "failed"} id=$id",
                result.exceptionOrNull())
            ProjectPersistence.mainHandler.post { onComplete(result) }
        }
    }

    fun exportDrawing(uri: android.net.Uri, format: ExportFormat, quality: Int, scale: Float, transparent: Boolean, onComplete: (Result<Unit>) -> Unit) {
        val snapshot = layerStack.snapshot(galleryQuarterTurns(rasterView.transform.rotationDegrees), animation)
        val accepted = ExportWork.start(context, work = { progress ->
            val result = try { ProjectPersistence.export(context.contentResolver, uri, snapshot, format, quality, scale, transparent) }
            finally { snapshot.recycle() }
            result
        }, onComplete = onComplete)
        if (!accepted) snapshot.recycle()
    }

    fun exportAnimation(uri: android.net.Uri, format: AnimationExportFormat, scale: Float, timing: dev.tipstroke.core.model.MediaExportTiming = dev.tipstroke.core.model.MediaExportTiming.SMOOTH_VIDEO, onComplete: (Result<Unit>) -> Unit) {
        val snapshot = layerStack.snapshot(galleryQuarterTurns(rasterView.transform.rotationDegrees), animation)
        val accepted = ExportWork.start(context, work = { progress ->
            val result = try { ProjectPersistence.exportAnimation(context.contentResolver, uri, snapshot, format, scale, timing, progress) }
            finally { snapshot.recycle() }
            result
        }, onComplete = onComplete)
        if (!accepted) snapshot.recycle()
    }

    private fun mediaFrameReady(): Boolean {
        if (importingClip) return false
        val active = animation ?: return true
        if (active.media == null) return true
        return !mediaSeeking && mediaCelLoading == null && active.mediaDrawingActive
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || importingClip || pendingAnimationFrame != null) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !mediaSeeking && mediaCelLoading == null &&
            !selectionMode && !selectionMoveMode && transformSelectionOriginal == null && !isTransformingImage() && !settings.erasing && !settings.smudging &&
            event.pointerCount == 1 && isDrawingPointer(event, event.actionIndex) && animation?.mediaDrawingActive == false) {
            stopAnimationPlayback()
            val result = runCatching { animation?.ensureMediaDrawingAtCursor() }
            if (result.isFailure) { animationErrorListener?.invoke(result.exceptionOrNull()?.message ?: "Cannot add drawing"); return true }
            rasterView.invalidate(); notifyLayers(); notifyHistory(); publishAnimation()
        }
        if (!mediaFrameReady()) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) stopAnimationPlayback()
            val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
            if (handleMousePan(event, index)) return true
            if (event.pointerCount > 1 || !isDrawingPointer(event, index)) return handleTouchGesture(event)
            return true
        }
        resizeOverlay?.let { return it.dispatchTouchEvent(event) }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stopAnimationPlayback()
            gestureHandler.removeCallbacks(hideBrushPreview)
            rasterView.adjustmentPreviewStroke = null
            rasterView.stylusHoverPreview = null
        }
        if (eventHasStylus(event)) updateStylusButtons(event.buttonState)
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        if (toolSmudgePointer != null) return handleSmudgeTool(event)
        if (handleMousePan(event, actionIndex)) return true
        if (transformSelectionOriginal != null) return handleSelectionTransform(event)
        if (selectionMoveMode) return handleSelectionMove(event)
        if (selectionMode) return handleSelection(event)
        if (isTransformingImage()) return handleTouchGesture(event)
        if (settings.smudging && !settings.erasing && event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.getToolType(actionIndex) != MotionEvent.TOOL_TYPE_ERASER && isDrawingPointer(event, actionIndex)) {
            return handleSmudgeTool(event)
        }
        // Raster smudge consumes its own stream and must not enter native Ink prediction.
        if (!settings.smudging) predictor?.record(event)

        // The emulator maps a host click to a finger. If a simulated second finger is
        // added for pinch, cancel the provisional mark and hand the full event to the
        // normal multi-touch navigation path.
        if ((settings.emulateMouseWithTouch || settings.gestures.oneFingerDrag == FingerAction.DRAW) &&
            activeDrawingPointerId != null &&
            event.findPointerIndex(activeDrawingPointerId!!).let { it >= 0 && !isStylus(event, it) } &&
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
        val pencilTip = if (!settings.smudging && style.blend == BlendBehavior.PAINT && style.brush.engine == BrushEngine.PENCIL) {
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
        val startsMultiErase = event.actionMasked == MotionEvent.ACTION_DOWN &&
            layerStack.selectedLayerIds().size > 1 && currentStyle(event, event.actionIndex).blend == BlendBehavior.ERASE
        if (multiEraseTargets.isNotEmpty() || startsMultiErase) return handleMultiErase(event)
        val pointerId = activeDrawingPointerId ?: event.getPointerId(event.actionIndex)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (shapeSnapped && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
                    event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_FINGER && fittedShape?.kind == QuickShape.Kind.LINE) {
                    addShapeArrowhead(event, pointerId)
                    return true
                }
                if (activeDrawingPointerId != null || !isDrawingPointer(event, event.actionIndex)) return true
                cancelColorPick()
                val baseStyle = currentStyle(event, event.actionIndex)
                val target = when (val selected = layerStack.selected()) {
                    is RasterLayerRuntime -> StrokeTarget(selected.tiles)
                    is ImageLayerRuntime -> if (baseStyle.blend == BlendBehavior.ERASE) StrokeTarget(selected.mask, selected) else return true
                }
                if (target.image != null && referenceLayerId != null) return true
                requestUnbufferedDispatch(event)
                activeDrawingPointerId = pointerId
                rasterSamplesRendered = 0
                strokeDownAt = event.downTime
                pendingTargets[pointerId] = target
                pendingSamples[pointerId] = mutableListOf(sample(event, event.actionIndex).forTarget(target))
                updateChromeOcclusion(event, event.actionIndex)
                val style = styleForTarget(baseStyle, target)
                shapeStyle = style
                shapeSnapped = false
                if (Build.VERSION.SDK_INT >= 34 && !Build.FINGERPRINT.contains("robolectric", true) &&
                    style.brush.stamp != null && style.blend == BlendBehavior.PAINT && style.pixelTool == null &&
                    style.referenceStrokes == null && strokeRecording == null) {
                    gpuStampPreview = GpuStampPreview(style, target.store.canvasWidth, target.store.canvasHeight).also {
                        rasterView.gpuStampPreview = it; rasterView.gpuStampLayerId = layerStack.selectedId
                        rasterView.invalidateTiles(it.append(pendingSamples.getValue(pointerId)))
                    }
                } else if ((style.brush.ink != null || style.brush.engine == BrushEngine.INK) && style.blend == BlendBehavior.PAINT && style.pixelTool == null &&
                    (style.referenceStrokes != null || layerStack.selected().clipSourceId != null || layerStack.selected().blendMode != LayerBlendMode.NORMAL)) {
                    rasterPreviewStyle = style
                    rasterView.previewStroke = CompletedStroke(pendingSamples.getValue(pointerId), style)
                } else if ((style.brush.stamp != null && style.pixelTool == null) || target.image != null || style.blend == BlendBehavior.ERASE ||
                    ((style.referenceStrokes != null || layerStack.selected().clipSourceId != null) && style.pixelTool != PixelTool.LINE)) {
                    customPreviewStyle = style
                    target.store.beginLiveStroke()
                    if (style.brush.stamp != null && style.blend == BlendBehavior.PAINT && style.pixelTool == null) {
                        invalidateTarget(target, target.store.appendLiveStroke(CompletedStroke(pendingSamples.getValue(pointerId), style)))
                    }
                } else if (style.pixelTool != null || style.brush.engine == BrushEngine.AIRBRUSH) {
                    rasterPreviewStyle = style
                    rasterView.previewStroke = CompletedStroke(pendingSamples.getValue(pointerId).toList(), style)
                } else if (style.brush.engine == BrushEngine.PENCIL) {
                    pencilPreviewStyle = style
                    startPencilPreview(CompletedStroke(pendingSamples.getValue(pointerId).toList(), style))
                    rasterSamplesRendered = 1
                    // Ink still authors the stroke, but its alpha/tilt preview is unreliable in
                    // 1.1.0-alpha08. Keep that mesh effectively transparent; the native live
                    // pixels become the permanent raster at pen-up, so the appearance cannot jump.
                    liveView.startStroke(event, pointerId, createInkBrush(style, previewAlpha = 1), rasterView.viewToDocumentMatrix(), Matrix())
                } else {
                    liveView.startStroke(event, pointerId, createInkBrush(style), rasterView.viewToDocumentMatrix(), Matrix())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (shapeSnapped) {
                    if (shapeAdjustmentEvent == null) gestureHandler.postDelayed(shapeAdjustmentFrame, 16L)
                    shapeAdjustmentEvent?.recycle()
                    shapeAdjustmentEvent = MotionEvent.obtainNoHistory(event)
                    return true
                }
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    val list = pendingSamples[pointerId] ?: return true
                    val additions = buildList {
                        for (historyIndex in 0 until event.historySize) add(sample(event, index, historyIndex).forTarget(pendingTargets.getValue(pointerId)))
                        add(sample(event, index).forTarget(pendingTargets.getValue(pointerId)))
                    }
                    appendSamples(pointerId, list, additions)
                    updateChromeOcclusion(event, index)
                    if (gpuStampPreview != null || customPreviewStyle != null || rasterPreviewStyle != null) {
                        Unit
                    } else {
                        val prediction = if (settings.smudging) null else predictor?.predict()
                        try { liveView.addToStroke(event, pointerId, prediction) } finally { prediction?.recycle() }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != pointerId) return true
                if (shapeSnapped && event.flags and MotionEvent.FLAG_CANCELED == 0) adjustShape(event, pointerId)
                clearShapeHold()
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
                if (index >= 0 && !shapeSnapped) pendingSamples[pointerId]?.let { list ->
                    pendingTargets[pointerId]?.let { target ->
                        appendSamples(pointerId, list, listOf(sample(event, index).copy(opacityPressure = terminalPressure).forTarget(target)))
                    }
                }
                flushRasterSamples()
                val target = pendingTargets.remove(pointerId)
                pendingSamples.remove(pointerId)?.let { rawSamples ->
                    val samples = stabilizeLiftOffOpacity(rawSamples)
                    val style = customPreviewStyle ?: rasterPreviewStyle ?: pencilPreviewStyle ?: shapeStyle ?: currentStyle(event, index.coerceAtLeast(0))
                    val reveal = strokeRecording?.takeIf { target?.image == null &&
                        layerStack.selectedId == it.layerId && style.blend == BlendBehavior.PAINT }
                        ?.copy(downAt = strokeDownAt)
                    if (target != null) {
                        if (Build.VERSION.SDK_INT >= 34 && gpuStampPreview != null) {
                            val preview = requireNotNull(gpuStampPreview)
                            target.store.commitGpuStamp(CompletedStroke(samples, style), preview)
                            clearGpuStampPreview()
                            rasterView.invalidate(); notifyHistory(); notifyVisiblePalette(); notifyLayers()
                            drawnColorListener?.invoke(style.color)
                        } else if (customPreviewStyle != null) {
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
                if (shapeStyle?.brush?.stamp != null && customPreviewStyle == null && rasterPreviewStyle == null) {
                    clearGpuStampPreview()
                } else if (customPreviewStyle != null) {
                    customPreviewStyle = null
                } else if (rasterPreviewStyle != null) {
                    rasterPreviewStyle = null
                } else {
                    liveView.finishStroke(event, pointerId)
                    pencilPreviewStyle = null
                }
                activeDrawingPointerId = null
                shapeSnapped = false
                fittedShape = null
                shapeGrab = null
                fittedSamples = emptyList()
                shapeStyle = null
                setChromeOccludedByStroke(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelActiveStroke(event, pointerId)
            }
        }
        if (activeDrawingPointerId != null && !shapeSnapped) updateShapeHold(event, pointerId)
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

    /** Selected Smudge uses native pointer samples; finger gesture bindings remain independent. */
    private fun handleSmudgeTool(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (referenceLayerId != null) return true
            val target = layerStack.selectedRaster() ?: return true
            cancelHold(); cancelColorPick()
            pendingSmudgePoint = null
            toolSmudgePointer = event.getPointerId(event.actionIndex)
            activeDrawingPointerId = toolSmudgePointer
            toolSmudgeLast = rasterView.screenToDocument(event.getX(event.actionIndex), event.getY(event.actionIndex))
            smudgeTarget = target.also { it.tiles.beginSmudge() }
            gestureDownAt = SystemClock.uptimeMillis()
            gestureMoved = false
            updateChromeOcclusion(event, event.actionIndex)
            return true
        }
        val pointer = toolSmudgePointer ?: return true
        val index = event.findPointerIndex(pointer)
        // An additional finger navigates; a palm alongside a pen cannot take over its stroke.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && index >= 0 && !isStylus(event, index)) {
            finishSmudgeTool()
            return handleTouchGesture(event)
        }
        val ending = event.actionMasked in intArrayOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP) &&
            event.getPointerId(event.actionIndex) == pointer
        if (index >= 0 && (event.actionMasked == MotionEvent.ACTION_MOVE || ending)) {
            // Pen batches can contain dozens of historical samples. Keep one latest
            // position and perform at most one bounded patch exchange per display frame.
            pendingSmudgePoint = rasterView.screenToDocument(event.getX(index), event.getY(index))
            if (!ending && !smudgeFrameScheduled) {
                smudgeFrameScheduled = true
                postOnAnimation(smudgeFrame)
            }
            updateChromeOcclusion(event, index)
            rasterView.invalidate()
        }
        if (ending || event.actionMasked == MotionEvent.ACTION_CANCEL || index < 0) finishSmudgeTool()
        return true
    }

    private fun finishSmudgeTool() {
        removeCallbacks(smudgeFrame)
        smudgeFrameScheduled = false
        applyPendingSmudge()
        smudgeTarget?.tiles?.finishSmudge()
        smudgeTarget = null
        toolSmudgePointer = null
        activeDrawingPointerId = null
        setChromeOccludedByStroke(false)
        notifyHistory(); notifyLayers(); notifyVisiblePalette()
    }

    private fun handleTouchGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                imageGestureInProgress = isTransformingImage()
                gestureDownAt = SystemClock.uptimeMillis()
                gestureMoved = false
                holdTriggered = false
                singleStart = android.graphics.PointF(event.x, event.y)
                singleLast = android.graphics.PointF(event.x, event.y)
                singleLastDocument = rasterView.screenToDocument(event.x, event.y)
                if (isTransformingImage()) beginPhotoEdit()
                if (!isTransformingImage() && settings.gestures.oneFingerDrag == FingerAction.SMUDGE && referenceLayerId == null) {
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
                    editPhoto(document.x - singleLastDocument.x, document.y - singleLastDocument.y)
                } else when (settings.gestures.oneFingerDrag) {
                    FingerAction.NAVIGATE -> if (gestureMoved) {
                        val old = rasterView.transform
                        rasterView.updateTransform(old.panX + event.x - singleLast.x, old.panY + event.y - singleLast.y, old.scale, old.rotationDegrees)
                    }
                    FingerAction.SMUDGE -> if (gestureMoved) {
                        smudgeTarget?.tiles?.smudge(
                            singleLastDocument.x, singleLastDocument.y, document.x, document.y,
                            smudgeRadius(), settings.gestures.smudgeStrength,
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
                    finishPhotoEdit()
                    imageGestureInProgress = false
                    notifyLayers(); notifyVisiblePalette()
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
                if (imageGestureInProgress) { finishPhotoEdit(cancel = true); imageGestureInProgress = false; notifyLayers(); notifyVisiblePalette() }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && isTransformingImage()) recordImageSample(event.eventTime)
        return true
    }

    private fun cancelPolygon() {
        selectionPoints.clear()
        previousSelection = null
        selectionPointerId = null
        rasterView.selectionDraftPoints = emptyList()
        rasterView.selectionDraftVertices = false
    }

    private fun handlePolygon(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { cancelPolygon(); return true }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) selectionPointerId = event.getPointerId(0)
        val index = selectionPointerId?.let(event::findPointerIndex) ?: -1
        if (index < 0) return true
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            selectionPointerId = null
            if (Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0) { cancelPolygon(); return true }
            val position = rasterView.screenToDocument(event.getX(index), event.getY(index))
            val point = Point(position.x, position.y)
            val first = selectionPoints.firstOrNull()
            val closeRadius = 18f * resources.displayMetrics.density / rasterView.transform.scale.coerceAtLeast(.08f)
            if (selectionPoints.size >= 3 && first != null && hypot(point.x - first.x, point.y - first.y) <= closeRadius) {
                val draft = SelectionRegion(selectionPoints.toList())
                selectionRegion = selectionRegion?.combined(draft, selectionOperation) ?: draft
                cancelPolygon()
                rasterView.selectionRegion = selectionRegion
                selectionListener?.invoke(true, selectionRegion != null)
            } else {
                selectionPoints += point
                rasterView.selectionDraftVertices = true
                rasterView.selectionDraftPoints = selectionPoints.toList()
            }
        }
        return true
    }

    private fun handleSelection(event: MotionEvent): Boolean {
        if (selectionTool == SelectionTool.POLYGON) return handlePolygon(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) selectionPointerId = event.getPointerId(event.actionIndex)
        val index = selectionPointerId?.let(event::findPointerIndex) ?: -1
        if (index < 0) return true
        val primaryUp = event.actionMasked in intArrayOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP) &&
            event.getPointerId(event.actionIndex) == selectionPointerId
        val constrained = (0 until event.pointerCount).any {
            it != index && event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER &&
                !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && it == event.actionIndex)
        }
        val canceled = event.actionMasked == MotionEvent.ACTION_CANCEL || (primaryUp &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && event.flags and MotionEvent.FLAG_CANCELED != 0)
        val action = if (canceled) MotionEvent.ACTION_CANCEL else if (primaryUp) MotionEvent.ACTION_UP else if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP) MotionEvent.ACTION_MOVE else event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val point = rasterView.screenToDocument(event.getX(index), event.getY(index))
                previousSelection = selectionRegion
                rasterView.previousSelectionOutline = previousSelection.takeIf { selectionOperation != dev.tipstroke.core.geometry.SelectionOperation.REPLACE }
                selectionPoints.clear()
                selectionPoints += Point(point.x, point.y)
                selectionRegion = null
                rasterView.selectionRegion = null
                rasterView.selectionDraftPoints = selectionPoints.toList()
            }
            MotionEvent.ACTION_MOVE -> selectionPoints.firstOrNull()?.let { start ->
                val point = rasterView.screenToDocument(event.getX(index), event.getY(index))
                if (selectionTool != SelectionTool.LASSO) {
                    val bounds = QuickShape.selectionBounds(start, Point(point.x, point.y), constrained)
                    selectionRegion = if (bounds.right - bounds.left < 2f || bounds.bottom - bounds.top < 2f) null
                        else if (selectionTool == SelectionTool.ELLIPSE) SelectionRegion.ellipse(bounds) else SelectionRegion.rectangle(bounds)
                    rasterView.selectionRegion = selectionRegion
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
                    val point = rasterView.screenToDocument(event.getX(index), event.getY(index))
                    selectionPoints += Point(point.x, point.y)
                    selectionRegion = selectionPoints.takeIf { it.size >= 3 }?.let { SelectionRegion(it.toList()) }
                }
                if (selectionTool != SelectionTool.LASSO) selectionPoints.firstOrNull()?.let { start ->
                    val point = rasterView.screenToDocument(event.getX(index), event.getY(index))
                    val bounds = QuickShape.selectionBounds(start, Point(point.x, point.y), constrained)
                    selectionRegion = if (bounds.right - bounds.left < 2f || bounds.bottom - bounds.top < 2f) null
                        else if (selectionTool == SelectionTool.ELLIPSE) SelectionRegion.ellipse(bounds) else SelectionRegion.rectangle(bounds)
                }
                selectionRegion = selectionRegion?.let { draft -> previousSelection?.combined(draft, selectionOperation) ?: draft } ?: previousSelection
                selectionPointerId = null
                previousSelection = null
                rasterView.previousSelectionOutline = null
                rasterView.selectionDraftPoints = emptyList()
                rasterView.selectionRegion = selectionRegion
                selectionPoints.clear()
                if (selectionRegion == null) clearSelection() else selectionListener?.invoke(true, true)
            }
            MotionEvent.ACTION_CANCEL -> {
                selectionRegion = previousSelection
                selectionPointerId = null
                previousSelection = null
                rasterView.previousSelectionOutline = null
                selectionPoints.clear()
                rasterView.selectionDraftPoints = emptyList()
                rasterView.selectionRegion = selectionRegion
                selectionListener?.invoke(selectionMode, selectionRegion != null)
            }
        }
        return true
    }

    private fun clearMovePreview() {
        selectionMoveStart = null
        rasterView.selectionSources.values.forEach { it.discard() }
        rasterView.selectionSources.clear()
        rasterView.selectionMeshTransform=null
        rasterView.transformHandles=false
        rasterView.movingLayerIds = emptySet()
        rasterView.movingSelectionPath = null
        rasterView.selectionPreviewOffset = Point(0f, 0f)
        rasterView.selectionPreviewMatrix = null
    }

    private fun handleSelectionMove(event: MotionEvent): Boolean {
        val selection = selectionRegion ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = rasterView.screenToDocument(event.x, event.y)
                selectionMoveStart = point.takeIf { selection.contains(Point(it.x, it.y)) }
                if (selectionMoveStart != null) {
                    requestUnbufferedDispatch(event)
                    rasterView.movingLayerIds = layerStack.selectedLayerIds()
                    rasterView.movingSelectionPath = selection.toAndroidPath()
                }
            }
            MotionEvent.ACTION_MOVE -> selectionMoveStart?.let { start ->
                val point = rasterView.screenToDocument(event.x, event.y)
                rasterView.selectionPreviewOffset = Point(point.x - start.x, point.y - start.y)
            }
            MotionEvent.ACTION_UP -> selectionMoveStart?.let { start ->
                if (Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0) {
                    clearMovePreview()
                    return true
                }
                val point = rasterView.screenToDocument(event.x, event.y)
                val deltaX = point.x - start.x
                val deltaY = point.y - start.y
                val edits = mutableListOf<UndoTransaction>()
                val movingLayers = layerStack.layers.filter { it.id in rasterView.movingLayerIds && it.activeAtFrame }
                if (abs(deltaX) >= .01f || abs(deltaY) >= .01f) {
                    movingLayers.forEach { layer ->
                        when (layer) {
                            is RasterLayerRuntime -> {
                                if (layer.tiles.moveSelection(selection, deltaX, deltaY).isNotEmpty()) {
                                    layer.tiles.history.takeLatestUndoTransaction()?.let(edits::add)
                                }
                            }
                            is ImageLayerRuntime -> {
                                val before = layer.transform
                                val after = before.changedBy(deltaX, deltaY)
                                layer.transform = after
                                edits += object : UndoTransaction {
                                    override val estimatedBytes = 64L
                                    override fun undo() { layer.transform = before }
                                    override fun redo() { layer.transform = after }
                                }
                            }
                        }
                    }
                    commitCompoundEdit(edits)
                    selectionRegion = selection.translated(deltaX, deltaY)
                    rasterView.selectionRegion = selectionRegion
                }
                clearMovePreview()
                notifyHistory(); notifyLayers(); notifyVisiblePalette()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clearMovePreview()
        }
        return true
    }

    /** Owns the constituent snapshots once, under the primary layer's bounded history. */
    private fun commitCompoundEdit(edits: List<UndoTransaction>) {
        if (edits.isEmpty()) return
        layerStack.selectedStore()?.history?.push(object : UndoTransaction {
            override val estimatedBytes get() = edits.sumOf { it.estimatedBytes }
            override fun undo() { edits.asReversed().forEach { it.undo() } }
            override fun redo() { edits.forEach { it.redo() } }
        })
    }

    private fun handleMultiErase(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelColorPick()
                val baseStyle = currentStyle(event, event.actionIndex)
                val ids = layerStack.selectedLayerIds()
                multiEraseTargets = layerStack.layers.filter { it.id in ids && it.activeAtFrame }.mapNotNull { layer ->
                    val target = when (layer) {
                        is RasterLayerRuntime -> StrokeTarget(layer.tiles)
                        is ImageLayerRuntime -> if (referenceLayerId == null) StrokeTarget(layer.mask, layer) else null
                    }
                    if (target == null) return@mapNotNull null
                    target.store.beginLiveStroke()
                    target to styleForTarget(baseStyle, target)
                }
                if (multiEraseTargets.isEmpty()) return true
                requestUnbufferedDispatch(event)
                activeDrawingPointerId = event.getPointerId(event.actionIndex)
                multiEraseSamples.clear()
                appendMultiErase(listOf(sample(event, event.actionIndex)))
                updateChromeOcclusion(event, event.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activeDrawingPointerId ?: return true)
                if (index >= 0) appendMultiErase(buildList {
                    for (history in 0 until event.historySize) add(sample(event, index, history))
                    add(sample(event, index))
                })
                if (index >= 0) updateChromeOcclusion(event, index)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != activeDrawingPointerId) return true
                if (Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0) {
                    cancelMultiErase(); return true
                }
                appendMultiErase(listOf(sample(event, event.actionIndex)))
                val edits = multiEraseTargets.mapNotNull { (target, style) ->
                    val stroke = CompletedStroke(multiEraseSamples.map { it.forTarget(target) }, style)
                    if (target.store.finishLiveStroke(stroke).isNotEmpty()) target.store.history.takeLatestUndoTransaction() else null
                }
                commitCompoundEdit(edits)
                multiEraseTargets = emptyList(); multiEraseSamples.clear(); activeDrawingPointerId = null
                setChromeOccludedByStroke(false)
                notifyHistory(); notifyLayers(); notifyVisiblePalette()
            }
            MotionEvent.ACTION_CANCEL -> cancelMultiErase()
        }
        emitDiagnostics(event, event.actionIndex)
        return true
    }

    private fun appendMultiErase(additions: List<StrokeSample>) {
        val segment = multiEraseSamples.takeLast(1) + additions
        multiEraseSamples += additions
        multiEraseTargets.forEach { (target, style) ->
            invalidateTarget(target, target.store.appendLiveStroke(CompletedStroke(segment.map { it.forTarget(target) }, style)))
        }
    }

    private fun cancelMultiErase() {
        multiEraseTargets.forEach { (target, _) -> invalidateTarget(target, target.store.cancelLiveStroke()) }
        multiEraseTargets = emptyList(); multiEraseSamples.clear(); activeDrawingPointerId = null
        setChromeOccludedByStroke(false)
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
        if (!colorPicking) {
            animation?.videoColorPreview?.recycle()
            animation?.videoColorPreview = videoView?.colorSnapshot()
        }
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
        animation?.videoColorPreview?.recycle(); animation?.videoColorPreview = null
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
            editPhoto(
                currentDocument.x - previousDocument.x,
                currentDocument.y - previousDocument.y,
                scaleFactor,
                Math.toDegrees(rawAngleDelta.toDouble()).toFloat(),
                navigation = true,
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
        samples += additions
        gpuStampPreview?.let { preview ->
            rasterView.invalidateTiles(preview.append(if (previous != null) listOf(previous) + additions else additions))
            if (preview.capacityReached) {
                val target = pendingTargets.getValue(pointerId)
                val style = preview.style
                clearGpuStampPreview()
                customPreviewStyle = style
                target.store.beginLiveStroke()
                invalidateTarget(target, target.store.appendLiveStroke(CompletedStroke(samples, style)))
            }
            return
        }
        rasterPreviewStyle?.let { style ->
            // Input and onDraw share the UI thread. Keep the list, avoiding an O(n)
            // copy on every MotionEvent; freeze it only when completing the gesture.
            rasterView.previewStroke = CompletedStroke(samples, style)
            return
        }
        if (pencilPreviewStyle != null || customPreviewStyle?.blend == BlendBehavior.ERASE) {
            // Preserve every sample but rasterize a display frame's input together.
            // Reading/writing the same tiles at stylus sampling frequency starves input.
            if (!rasterFramePending) {
                rasterFramePending = true
                postOnAnimation(rasterFrame)
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

    private fun flushRasterSamples() {
        removeCallbacks(rasterFrame)
        rasterFramePending = false
        val pointer = activeDrawingPointerId ?: return
        val samples = pendingSamples[pointer] ?: return
        if (samples.size <= rasterSamplesRendered) return
        val target = pendingTargets[pointer] ?: return
        val pencil = pencilPreviewStyle
        if (pencil != null) {
            val preview = pencilPreviewStore ?: return
            val start = (rasterSamplesRendered - 2).coerceAtLeast(0)
            rasterView.invalidateTiles(preview.appendPencilPreview(
                CompletedStroke(samples.subList(start, samples.size).toList(), pencil),
                skipFirstSegment = rasterSamplesRendered >= 2,
            ))
        } else {
            val style = customPreviewStyle?.takeIf { it.blend == BlendBehavior.ERASE } ?: return
            val start = (rasterSamplesRendered - 1).coerceAtLeast(0)
            invalidateTarget(target, target.store.appendLiveStroke(
                CompletedStroke(samples.subList(start, samples.size).toList(), style)))
        }
        rasterSamplesRendered = samples.size
    }

    private fun clearGpuStampPreview() {
        rasterView.gpuStampPreview = null; rasterView.gpuStampLayerId = null
        gpuStampPreview?.close(); gpuStampPreview = null
    }

    private fun clearShapeHold() {
        gestureHandler.removeCallbacks(shapeAdjustmentFrame)
        shapeAdjustmentEvent?.recycle()
        shapeAdjustmentEvent = null
        shapeHold?.let(gestureHandler::removeCallbacks)
        shapeHold = null
        shapeEvent?.recycle()
        shapeEvent = null
        shapeAnchor = null
    }

    private fun updateShapeHold(event: MotionEvent, pointerId: Int) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0 || shapeStyle?.pixelTool == PixelTool.LINE || shapeStyle?.brush?.stamp != null) return
        val point = Point(event.getX(index), event.getY(index))
        val anchor = shapeAnchor
        val moved = anchor == null || hypot(point.x - anchor.x, point.y - anchor.y) > 8f * resources.displayMetrics.density
        if (moved) clearShapeHold()
        shapeEvent?.recycle()
        shapeEvent = MotionEvent.obtainNoHistory(event)
        if (!moved) return
        shapeAnchor = point
        shapeHold = Runnable {
            val samples = pendingSamples[pointerId] ?: return@Runnable
            val target = pendingTargets[pointerId] ?: return@Runnable
            val style = shapeStyle ?: return@Runnable
            val geometry = QuickShape.recognize(samples.map { it.position }) ?: return@Runnable
            // Tiny marks and dots are never promoted to shapes.
            val extent = maxOf(samples.maxOf { it.position.x } - samples.minOf { it.position.x },
                samples.maxOf { it.position.y } - samples.minOf { it.position.y })
            if (extent * rasterView.transform.scale < 24f * resources.displayMetrics.density) return@Runnable
            // Closed geometry has no beginning/end pressure taper. Use the gesture's average
            // contact so the seam cannot expose a pen-down/lift-off pressure discontinuity.
            val contact = samples[samples.size / 2].copy(
                pressure = samples.map { it.pressure }.average().toFloat(),
                opacityPressure = samples.map { it.opacityPressure }.average().toFloat(),
                tiltRadians = samples.map { it.tiltRadians }.average().toFloat(),
            )
            val fitted = geometry.points.mapIndexed { i, point ->
                val original = samples[i * samples.lastIndex / geometry.points.lastIndex]
                (if (geometry.closed) contact.copy(elapsedNanos = original.elapsedNanos) else original).copy(position = point)
            }
            fittedShape = geometry
            shapeGrab = samples.last().position
            fittedSamples = fitted
            if (customPreviewStyle != null) {
                invalidateTarget(target, target.store.cancelLiveStroke())
                target.store.beginLiveStroke()
                invalidateTarget(target, target.store.appendLiveStroke(CompletedStroke(fitted, style)))
            } else {
                if (rasterPreviewStyle == null) shapeEvent?.let { liveView.cancelStroke(it, pointerId) }
                clearPencilPreview()
                pencilPreviewStyle = null
                rasterPreviewStyle = style
                rasterView.previewStroke = CompletedStroke(fitted, style)
            }
            samples.clear()
            samples.addAll(fitted)
            shapeSnapped = true
            clearShapeHold()
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }.also { gestureHandler.postDelayed(it, 650L) }
    }

    private fun addShapeArrowhead(event: MotionEvent, pointerId: Int) {
        clearShapeHold()
        adjustShape(event, pointerId)
        val samples = pendingSamples[pointerId] ?: return
        val arrow = QuickShape.arrow(QuickShape.Shape(QuickShape.Kind.LINE, samples.map { it.position }))
        val target = pendingTargets[pointerId] ?: return
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        fittedSamples = arrow.points.mapIndexed { i, point ->
            samples[i * samples.lastIndex / arrow.points.lastIndex].copy(position = point)
        }
        fittedShape = arrow
        shapeGrab = sample(event, index).forTarget(target).position
        adjustShape(event, pointerId)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun adjustShape(event: MotionEvent, pointerId: Int) {
        val shape = fittedShape ?: return
        val grab = shapeGrab ?: return
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        val target = pendingTargets[pointerId] ?: return
        val style = shapeStyle ?: return
        val position = sample(event, index).forTarget(target).position
        val adjusted = QuickShape.adjust(shape, grab, position)
        val samples = adjusted.points.mapIndexed { i, point -> fittedSamples[i].copy(position = point) }
        pendingSamples[pointerId]?.apply { clear(); addAll(samples) }
        if (customPreviewStyle != null) {
            invalidateTarget(target, target.store.cancelLiveStroke())
            target.store.beginLiveStroke()
            invalidateTarget(target, target.store.appendLiveStroke(CompletedStroke(samples, style)))
        } else {
            rasterView.previewStroke = CompletedStroke(samples, style)
        }
        updateChromeOcclusion(event, index)
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
        removeCallbacks(rasterFrame)
        rasterFramePending = false
        clearShapeHold()
        shapeSnapped = false
        fittedShape = null
        shapeGrab = null
        fittedSamples = emptyList()
        shapeStyle = null
        if (multiEraseTargets.isNotEmpty()) { cancelMultiErase(); return }
        if (gpuStampPreview == null && customPreviewStyle == null && rasterPreviewStyle == null) {
            liveView.cancelStroke(event, pointerId)
        }
        if (customPreviewStyle != null) {
            pendingTargets[pointerId]?.let { target -> invalidateTarget(target, target.store.cancelLiveStroke()) }
        }
        clearGpuStampPreview()
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
            (chromeTopInsetPx > 0f && y <= chromeTopInsetPx + chromeApproachMarginPx) ||
                (chromeBottomInsetPx > 0f && y >= height - chromeBottomInsetPx - chromeApproachMarginPx) ||
                (chromeLeftInsetPx > 0f && event.getX(index) <= chromeLeftInsetPx + chromeApproachMarginPx) ||
                (chromeRightInsetPx > 0f && event.getX(index) >= width - chromeRightInsetPx - chromeApproachMarginPx),
        )
    }

    private fun setChromeOccludedByStroke(occluded: Boolean) {
        if (chromeOccludedByStroke == occluded) return
        chromeOccludedByStroke = occluded
        chromeOcclusionListener?.invoke(occluded)
    }

    private fun smudgeRadius() = (resolvedBrushSize() * .5f).coerceIn(1f, 96f)

    private fun resolvedBrushSize() = if (settings.smudging) settings.sizePx.coerceIn(2f, 192f) else settings.sizeUnit.documentPixels(
        settings.sizePx, rasterView.transform.scale, resources.displayMetrics.density, settings.pixelTool,
    )

    private fun currentStyle(event: MotionEvent, index: Int): StrokeStyle {
        val isHardwareEraser = index in 0 until event.pointerCount && event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        val erasing = settings.erasing || isHardwareEraser
        val brush = if (erasing) settings.brush.copy(hardness = settings.eraserHardness) else settings.brush
        return StrokeStyle(brush, resolvedBrushSize(), settings.opacity, settings.color,
            if (erasing) BlendBehavior.ERASE else BlendBehavior.PAINT, selectionRegion,
            pixelTool = settings.pixelTool,
            referenceStrokes = referenceLayerId?.let { id ->
                (layerStack.layers.firstOrNull { it.id == id } as? RasterLayerRuntime)?.let { ReferenceStrokes(it.tiles, id == layerStack.selectedId) }
            })
    }

    private fun styleForTarget(style: StrokeStyle, target: StrokeTarget): StrokeStyle {
        val image = target.image ?: return style.copy(referenceStrokes = (style.referenceStrokes as? ReferenceStrokes)?.forTarget(target.store))
        val localSelection = style.selection?.let { selection -> imageLocalSelection(image, selection) }
        return style.copy(
            brush = style.brush.copy(engine = BrushEngine.AIRBRUSH, stamp = null, ink = null),
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

    private fun imageLocalSelection(image: ImageLayerRuntime, selection: SelectionRegion): SelectionRegion = SelectionRegion(
        selection.points.map { point ->
            val local = layerStack.imagePointFromDocument(image, android.graphics.PointF(point.x, point.y))
            Point(local.x, local.y)
        },
        selection.left?.let { imageLocalSelection(image, it) },
        selection.right?.let { imageLocalSelection(image, it) },
        selection.operation,
    )

    private fun invalidateTarget(target: StrokeTarget, dirty: Set<dev.tipstroke.core.geometry.TileCoordinate>) {
        if (target.image == null) rasterView.invalidateTiles(dirty) else rasterView.invalidate()
    }

    private fun createInkBrush(style: StrokeStyle, includePressureOpacity: Boolean = true, previewAlpha: Int? = null): Brush {
        val c = style.color
        val alpha = previewAlpha ?: (style.opacity * c.alpha * (style.brush.ink?.flow ?: 1f) * 255).roundToInt().coerceIn(1, 255)
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
        val size = style.sizePx.coerceAtLeast(.001f)
        return Brush.createWithColorIntArgb(family, liveColor, size, minOf(epsilon, size * .1f))
    }

    private fun startPencilPreview(stroke: CompletedStroke) {
        clearPencilPreview()
        pencilPreviewStore = TileStore(layerStack.canvasWidth, layerStack.canvasHeight).also { preview ->
            preview.captureReplayPreview = processRecorder?.stoppedReason == null && processRecorder != null
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
        if (animation != null && !switchingAnimationFrame && !imageGestureInProgress) {
            animationThumbnails.clear()
            thumbnailRevision++
            publishAnimation()
        }
        if (referenceLayerId != null && layerStack.layers.none { it.id == referenceLayerId }) {
            referenceLayerId = null
            referenceStrokesListener?.invoke(null)
        }
        if (!imageGestureInProgress && !opacityAdjustmentInProgress && (!animationPlaying || strokeRecording != null)) processRecorder?.sync(layerStack, animation != null, animation = animation)
        layersListener?.invoke(
            layerStack.summariesFrontToBack(),
            layerStack.selectedId,
            layerStack.selectedLayerIds(),
            layerStack.previewsFrontToBack(96),
            layerStack.backgroundColor,
            layerStack.backgroundOpacity,
        )
    }
    private fun notifyVisiblePalette() {
        visiblePaletteListener?.invoke(layerStack.visiblePalette(paletteColorCount, originalImageColors))
    }
    private var lastInputDiagnosticsAt = 0L
    private fun emitDiagnostics(event: MotionEvent, index: Int) {
        if (!settings.debug) return
        val now = SystemClock.elapsedRealtimeNanos(); frameCount++
        if (now - fpsWindowAt > 500_000_000L) { fps = frameCount * 1_000_000_000f / (now - fpsWindowAt); frameCount = 0; fpsWindowAt = now }
        val delta = now - lastSampleAt; lastSampleAt = now
        val diagnosticsAt = SystemClock.uptimeMillis()
        if (diagnosticsAt - lastInputDiagnosticsAt < 100) return
        lastInputDiagnosticsAt = diagnosticsAt
        val kind = pointerKind(event.getToolType(index)).let {
            if (it == PointerKind.FINGER && settings.emulateMouseWithTouch) PointerKind.MOUSE else it
        }
        val pressure = if (kind == PointerKind.MOUSE ||
            (kind == PointerKind.FINGER && settings.gestures.oneFingerDrag == FingerAction.DRAW)
        ) 1f else event.getPressure(index)
        val input = CanvasDiagnostics(fps, pressure, event.getAxisValue(MotionEvent.AXIS_TILT, index),
            kind.name, if (delta > 0) 1_000_000_000f / delta else 0f,
            rasterView.transform.scale, layerStack.allocatedTiles(), layerStack.lastDirtyTiles(), layerStack.undoBytes())
        publishDiagnostics(input.withMemoryFrom(lastDiagnostics))
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
