package dev.tipstroke.core.drawing

/** A UI preference, resolved to document pixels at touch-down; never stored in stroke data. */
enum class BrushSizeUnit {
    CANVAS_PIXELS, SCREEN_DP;

    fun documentPixels(size: Float, zoom: Float, density: Float, pixelTool: PixelTool? = null): Float =
        if (this == CANVAS_PIXELS || pixelTool != null) size
        else size * density / zoom.coerceAtLeast(.0001f)
}
