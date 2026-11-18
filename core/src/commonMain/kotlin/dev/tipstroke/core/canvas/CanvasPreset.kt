package dev.tipstroke.core.canvas

/** Pixel dimensions, independent of display density or platform rendering. */
data class CanvasPreset(val id: String, val name: String, val widthPx: Int, val heightPx: Int, val category: String)

/** One non-scrolling row; hidden pins remain stored when the window shrinks. */
fun resolutionFavoriteCapacity(availableWidth: Float): Int =
    ((availableWidth + 8f) / 112f).toInt().coerceIn(0, 8)

fun standardCanvasPresets(): List<CanvasPreset> = listOf(
    CanvasPreset("full-hd", "Full HD", 1920, 1080, "Everyday"),
    CanvasPreset("square-2048", "Square", 2048, 2048, "Everyday"),
    CanvasPreset("uhd-4k", "4K UHD", 3840, 2160, "Everyday"),
    CanvasPreset("manga-page", "Manga page (A4, 300 dpi)", 2480, 3508, "Comics"),
    CanvasPreset("manga-panel", "Manga panel", 1200, 1800, "Comics"),
    CanvasPreset("icon-64", "Small icon", 64, 64, "Icons"),
    CanvasPreset("icon-256", "App icon", 256, 256, "Icons"),
    CanvasPreset("icon-512", "Large icon", 512, 512, "Icons"),
    CanvasPreset("pixel-16", "Pixel art 16", 16, 16, "Pixel art"),
    CanvasPreset("pixel-32", "Pixel art 32", 32, 32, "Pixel art"),
    CanvasPreset("pixel-64", "Pixel art 64", 64, 64, "Pixel art"),
    CanvasPreset("pixel-128", "Pixel art 128", 128, 128, "Pixel art"),
)
