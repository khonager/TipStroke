package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.widget.FrameLayout
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.*
import kotlin.math.sin

/** Uses the same native Ink authoring and tile completion as the document canvas. */
class InkBrushDrawingPad(context: Context) : FrameLayout(context) {
    private val surface = DrawingSurface(context)
    private var preset = BrushPreset.BlankInk
    private var documentWidth = 0
    private var documentHeight = 0
    init {
        contentDescription = "Ink drawing pad"
        setBackgroundColor(Color.rgb(250, 250, 250))
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surface.settings.gestures = GestureSettings(oneFingerDrag = FingerAction.DRAW)
        surface.isEnabled = Build.VERSION.SDK_INT >= 34
    }
    fun setBrush(value: BrushPreset) {
        if (value == preset) return
        preset = value
        configure()
        clear()
    }
    private fun configure() {
        surface.settings.brush = preset
        surface.settings.sizePx = preset.baseSizePx
        surface.settings.opacity = preset.opacity
        surface.settings.color = RgbaColor(.22f, .3f, .72f)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        documentWidth = w.coerceIn(16, 1600); documentHeight = h.coerceIn(16, 600)
        configure(); clear()
    }
    fun clear() {
        if (documentWidth == 0 || documentHeight == 0) return
        surface.configureBlank(documentWidth, documentHeight)
    }
    fun showSample() {
        clear()
        if (documentWidth == 0 || Build.VERSION.SDK_INT < 34 || Build.FINGERPRINT.contains("robolectric", true)) return
        val samples = List(80) { i ->
            val t = i / 79f
            StrokeSample(0, Point(30f + t * (documentWidth - 60), documentHeight * (.5f + .12f * sin(t * 6.28f))),
                .2f + .8f * sin(t * 3.14f), t * 1.2f, 0f, i * 10_000_000L, 0, PointerKind.STYLUS)
        }
        val raster = surface.getChildAt(0) as RasterCanvasView
        raster.layerStack.selectedStore()?.commit(CompletedStroke(samples,
            StrokeStyle(preset, preset.baseSizePx, preset.opacity, surface.settings.color, BlendBehavior.PAINT)))
        raster.invalidate()
    }
}
