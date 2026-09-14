package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.os.Build
import android.view.*
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.input.motionprediction.MotionEventPredictor
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.*
import kotlin.math.*

class DrawingSurface @JvmOverloads constructor(context: Context, attrs: android.util.AttributeSet? = null) : FrameLayout(context, attrs) {
    val settings = CanvasSettings()
    private val store = TileStore(2048, 2048)
    private val rasterView = RasterCanvasView(context, store)
    private val liveView = InProgressStrokesView(context)
    private val inkRenderer by lazy { CanvasStrokeRenderer.create(liveView.textureBitmapStore) }
    private var predictor: MotionEventPredictor? = null
    private val pendingSamples = mutableMapOf<Int, MutableList<StrokeSample>>()
    private val finishedSamples = ArrayDeque<CompletedStroke>()
    private var activeStylusId: Int? = null
    private var gestureStart = emptyMap<Int, android.graphics.PointF>()
    private var lastGestureCentroid = android.graphics.PointF()
    private var lastGestureSpan = 0f
    private var lastGestureAngle = 0f
    private var gestureMoved = false
    private var gestureDownAt = 0L
    private var lastSampleAt = 0L
    private var frameCount = 0
    private var fpsWindowAt = SystemClock.elapsedRealtimeNanos()
    private var fps = 0f
    var diagnosticsListener: ((CanvasDiagnostics) -> Unit)? = null
    var historyListener: ((Boolean, Boolean) -> Unit)? = null

    init {
        setWillNotDraw(false); setBackgroundColor(Color.rgb(23, 24, 27)); isMotionEventSplittingEnabled = false
        addView(rasterView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(liveView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Robolectric cannot load Ink's Android native library; real devices eagerly warm it.
        if (!Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) liveView.eagerInit()
        liveView.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                strokes.forEach { (_, inkStroke) ->
                    val stroke = finishedSamples.removeFirstOrNull() ?: return@forEach
                    if (stroke.style.blend == BlendBehavior.PAINT && stroke.style.brush.engine != BrushEngine.AIRBRUSH) {
                        store.commitInk(stroke, inkStroke, inkRenderer)
                    } else {
                        store.commit(stroke)
                    }
                }
                rasterView.invalidate()
                liveView.removeFinishedStrokes(strokes.keys)
                notifyHistory()
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (predictor == null) predictor = runCatching { MotionEventPredictor.newInstance(this) }.getOrNull()
    }

    fun undo() { if (store.history.undo()) { rasterView.invalidate(); notifyHistory() } }
    fun redo() { if (store.history.redo()) { rasterView.invalidate(); notifyHistory() } }
    fun resetView() = rasterView.fitCanvas()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        predictor?.record(event)
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val toolType = event.getToolType(actionIndex)
        val stylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        return if (stylus || activeStylusId != null) handleStylus(event) else handleTouchGesture(event)
    }

    private fun handleStylus(event: MotionEvent): Boolean {
        val pointerId = activeStylusId ?: event.getPointerId(event.actionIndex)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!isStylus(event, event.actionIndex)) return true
                requestUnbufferedDispatch(event)
                activeStylusId = pointerId
                pendingSamples[pointerId] = mutableListOf(sample(event, event.actionIndex))
                liveView.startStroke(event, pointerId, createInkBrush(), rasterView.viewToDocumentMatrix(), Matrix())
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    val list = pendingSamples[pointerId] ?: return true
                    for (historyIndex in 0 until event.historySize) list += sample(event, index, historyIndex)
                    list += sample(event, index)
                    val prediction = predictor?.predict()
                    try { liveView.addToStroke(event, pointerId, prediction) } finally { prediction?.recycle() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.findPointerIndex(pointerId)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && event.flags and MotionEvent.FLAG_CANCELED != 0) {
                    liveView.cancelStroke(event, pointerId); pendingSamples.remove(pointerId); activeStylusId = null
                    return true
                }
                if (index >= 0) pendingSamples[pointerId]?.add(sample(event, index))
                pendingSamples.remove(pointerId)?.let { finishedSamples += CompletedStroke(it, currentStyle(event, index.coerceAtLeast(0))) }
                liveView.finishStroke(event, pointerId); activeStylusId = null
            }
            MotionEvent.ACTION_CANCEL -> {
                liveView.cancelStroke(event, pointerId); pendingSamples.remove(pointerId); activeStylusId = null
            }
        }
        emitDiagnostics(event, event.actionIndex.coerceIn(0, event.pointerCount - 1))
        return true
    }

    private fun handleTouchGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) beginGesture(event)
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) updateGesture(event)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (gestureStart.size >= 2 && !gestureMoved && SystemClock.uptimeMillis() - gestureDownAt < 260) {
                    if (gestureStart.size >= 3) redo() else undo()
                }
                // Consume the tap once; subsequent pointer-up events belong to the same gesture.
                gestureStart = emptyMap()
            }
            MotionEvent.ACTION_CANCEL -> gestureStart = emptyMap()
        }
        return true
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
        val angleDelta = normalizedAngle(newAngle - lastGestureAngle)
        val old = rasterView.transform
        rasterView.updateTransform(old.panX + dx, old.panY + dy, (old.scale * scaleFactor).coerceIn(.08f, 12f), old.rotationDegrees + Math.toDegrees(angleDelta.toDouble()).toFloat())
        liveView.motionEventToViewTransform = Matrix()
        gestureMoved = gestureMoved || hypot(dx, dy) > 4f || abs(scaleFactor - 1f) > .015f || abs(angleDelta) > .02f
        lastGestureCentroid = center; lastGestureSpan = newSpan; lastGestureAngle = newAngle
        emitDiagnostics(event, 0)
    }

    private fun currentStyle(event: MotionEvent, index: Int): StrokeStyle {
        val isHardwareEraser = index in 0 until event.pointerCount && event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        return StrokeStyle(settings.brush, settings.sizePx, settings.opacity, settings.color,
            if (settings.erasing || isHardwareEraser) BlendBehavior.ERASE else BlendBehavior.PAINT)
    }

    private fun createInkBrush(): Brush {
        val c = settings.color
        val alpha = (settings.opacity * c.alpha * 255).roundToInt().coerceIn(1, 255)
        val argb = Color.argb(alpha, (c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())
        val family = when (settings.brush.engine) {
            BrushEngine.INK, BrushEngine.PENCIL -> StockBrushes.pressurePen()
            BrushEngine.AIRBRUSH -> StockBrushes.marker()
        }
        // The v0 canvas composites its single transparent paint layer over white.
        // White wet ink therefore previews transparent erasing without a dark flash.
        val liveColor = if (settings.erasing) Color.WHITE else argb
        return Brush.createWithColorIntArgb(family, liveColor, settings.sizePx.coerceAtLeast(1f), .1f)
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

    private fun notifyHistory() = historyListener?.invoke(store.history.canUndo(), store.history.canRedo())
    private fun emitDiagnostics(event: MotionEvent, index: Int) {
        val now = SystemClock.elapsedRealtimeNanos(); frameCount++
        if (now - fpsWindowAt > 500_000_000L) { fps = frameCount * 1_000_000_000f / (now - fpsWindowAt); frameCount = 0; fpsWindowAt = now }
        val delta = now - lastSampleAt; lastSampleAt = now
        diagnosticsListener?.invoke(CanvasDiagnostics(fps, event.getPressure(index), event.getAxisValue(MotionEvent.AXIS_TILT, index),
            pointerKind(event.getToolType(index)).name, if (delta > 0) 1_000_000_000f / delta else 0f,
            rasterView.transform.scale, store.allocatedTileCount, store.lastDirtyTiles.size, store.history.estimatedBytes))
    }
}
