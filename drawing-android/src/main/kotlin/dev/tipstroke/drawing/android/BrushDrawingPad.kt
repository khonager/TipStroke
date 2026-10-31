package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.*

/** A bounded native test surface. Pointer samples never pass through Compose state. */
class BrushDrawingPad(context: Context) : View(context) {
    private var brush = BrushPreset.Blank
    private var bitmap: Bitmap? = null
    private var session: StampBrushRenderer? = null
    private val strokes = ArrayDeque<List<StrokeSample>>()
    private var current = mutableListOf<StrokeSample>()
    private var downTime = 0L
    private var activePointer = -1
    private val color = RgbaColor(.22f, .3f, .72f)

    init { contentDescription = "Brush drawing pad"; setBackgroundColor(Color.rgb(250, 250, 250)) }
    fun setBrush(value: BrushPreset) {
        if (brush == value) return
        brush = value
        redraw()
    }
    fun clear() { strokes.clear(); current.clear(); session?.close(); session = null; bitmap?.eraseColor(Color.TRANSPARENT); invalidate() }
    fun showSample() {
        val target = bitmap ?: return
        clear()
        strokes.addLast(List(100) { i ->
            val t = i / 99f
            StrokeSample(0, Point(20f + t * (target.width - 40f), target.height * (.5f + .15f * kotlin.math.sin(t * 6.28f))),
                .15f + .85f * kotlin.math.sin(t * 3.14f), t * 1.2f, 0f, i * 10_000_000L, 0, PointerKind.STYLUS)
        })
        redraw()
    }
    private fun style() = StrokeStyle(brush, brush.baseSizePx, brush.opacity, color, BlendBehavior.PAINT)
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bitmap?.recycle()
        bitmap = if (w > 0 && h > 0) Bitmap.createBitmap(w.coerceAtMost(1600), h.coerceAtMost(600), Bitmap.Config.ARGB_8888) else null
        if (strokes.isEmpty() && current.isEmpty()) showSample() else redraw()
    }
    private fun redraw() {
        session?.close(); session = null
        val target = bitmap ?: return
        target.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(target)
        strokes.forEach { if (it.isNotEmpty()) StrokeCanvasPainter.draw(canvas, CompletedStroke(it, style())) }
        if (current.isNotEmpty()) {
            if (brush.stamp != null) session = StampBrushRenderer(style()).also { it.draw(canvas, it.path.append(current)) }
            else StrokeCanvasPainter.draw(canvas, CompletedStroke(current, style()))
        }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) { bitmap?.let { canvas.drawBitmap(it, null, Rect(0, 0, width, height), null) } }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = bitmap ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            activePointer = event.getPointerId(0)
            downTime = event.eventTime
            current = mutableListOf()
            if (strokes.size >= 16) { strokes.removeFirst(); redraw() }
            session?.close(); session = if (brush.stamp != null) StampBrushRenderer(style()) else null
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val pointer = event.findPointerIndex(activePointer)
        if (pointer < 0) return true
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            current.clear(); activePointer = -1; redraw(); parent?.requestDisallowInterceptTouchEvent(false); return true
        }
        if (event.actionMasked !in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) return true
        fun sample(history: Int?): StrokeSample {
            val x = if (history == null) event.getX(pointer) else event.getHistoricalX(pointer, history)
            val y = if (history == null) event.getY(pointer) else event.getHistoricalY(pointer, history)
            val pressure = if (history == null) event.getPressure(pointer) else event.getHistoricalPressure(pointer, history)
            val time = if (history == null) event.eventTime else event.getHistoricalEventTime(history)
            val stylus = event.getToolType(pointer) == MotionEvent.TOOL_TYPE_STYLUS
            val tilt = if (history == null) event.getAxisValue(MotionEvent.AXIS_TILT, pointer) else event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointer, history)
            return StrokeSample(activePointer, Point(x * target.width / width, y * target.height / height),
                if (stylus) pressure.coerceIn(.01f, 1f) else 1f, tilt.coerceIn(0f, 1.5f), 0f,
                (time - downTime).coerceAtLeast(0) * 1_000_000L, 0, if (stylus) PointerKind.STYLUS else PointerKind.FINGER)
        }
        val additions = buildList {
            for (i in 0 until event.historySize) if (size + current.size < 2048) add(sample(i))
            if (size + current.size < 2048) add(sample(null))
        }
        current.addAll(additions)
        if (session != null) session!!.draw(Canvas(target), session!!.path.append(additions)) else redraw()
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (current.isNotEmpty()) strokes.addLast(current.toList())
            current.clear(); session?.close(); session = null; activePointer = -1
            parent?.requestDisallowInterceptTouchEvent(false)
            performClick()
        }
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { session?.close(); session = null; bitmap?.recycle(); bitmap = null; super.onDetachedFromWindow() }
}

/** Draw an original tip or grain mask on a small native surface. */
class BrushSourcePad(context: Context) : View(context) {
    private val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 18f; strokeCap = Paint.Cap.ROUND }
    private var lastX = 0f
    private var lastY = 0f
    var erasing = false
    var sizePx = 18f
    fun setTexture(texture: BrushTexture?) {
        clear()
        if (texture == null) return
        val source = Bitmap.createBitmap(texture.width, texture.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(texture.width * texture.height) { i -> Color.argb(texture.coverage(i % texture.width, i / texture.width), 255, 255, 255) }
        source.setPixels(pixels, 0, texture.width, 0, 0, texture.width, texture.height)
        Canvas(bitmap).drawBitmap(source, null, Rect(0, 0, 256, 256), Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle(); invalidate()
    }
    init { contentDescription = "Draw brush source"; setBackgroundColor(Color.BLACK) }
    fun clear() { bitmap.eraseColor(Color.TRANSPARENT); invalidate() }
    fun texture(): BrushTexture {
        val pixels = IntArray(256 * 256)
        bitmap.getPixels(pixels, 0, 256, 0, 0, 256, 256)
        return BrushTexture(256, 256, ByteArray(pixels.size) { Color.alpha(pixels[it]).toByte() })
    }
    override fun onDraw(canvas: Canvas) { canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), null) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x / width * 256f; val y = event.y / height * 256f
        paint.strokeWidth = sizePx
        paint.xfermode = if (erasing) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); Canvas(bitmap).drawCircle(x, y, sizePx / 2f, paint) }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> Canvas(bitmap).drawLine(lastX, lastY, x, y, paint)
        }
        lastX = x; lastY = y
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false); performClick()
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
