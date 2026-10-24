package dev.tipstroke.drawing.android

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

/** Isolate destination and coverage so missing sparse source tiles clear the whole overlap. */
internal fun drawWithLayerClip(canvas: Canvas, coverage: (() -> Unit)?, content: () -> Unit) {
    if (coverage == null) { content(); return }
    val bounds = RectF(canvas.clipBounds)
    val destination = canvas.saveLayer(bounds, null)
    content()
    val mask = canvas.saveLayer(bounds, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
    coverage()
    canvas.restoreToCount(mask)
    canvas.restoreToCount(destination)
}
