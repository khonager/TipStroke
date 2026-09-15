package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.View
import dev.tipstroke.core.drawing.CompletedStroke

/** Native, non-Compose preview for brushes that Jetpack Ink cannot represent faithfully. */
internal class WetStrokePreviewView(context: Context) : View(context) {
    var documentToView = Matrix()
    var canvasWidth = 0
    var canvasHeight = 0
    var stroke: CompletedStroke? = null
        set(value) {
            field = value
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val current = stroke ?: return
        canvas.save()
        canvas.concat(documentToView)
        canvas.clipRect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
        StrokeCanvasPainter.draw(canvas, current)
        canvas.restore()
    }
}
