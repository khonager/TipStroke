package dev.tipstroke.drawing.android

import dev.tipstroke.core.model.*

class CanvasSettings {
    @Volatile var brush: BrushPreset = BrushPreset.Ink
    @Volatile var sizePx: Float = BrushPreset.Ink.baseSizePx
    @Volatile var opacity: Float = 1f
    @Volatile var color: RgbaColor = RgbaColor(0.05f, 0.05f, 0.06f)
    @Volatile var erasing: Boolean = false
    @Volatile var eraserHardness: Float = .35f
    @Volatile var debug: Boolean = false
    @Volatile var gestures: GestureSettings = GestureSettings()
}

data class CanvasDiagnostics(
    val fps: Float = 0f,
    val pressure: Float = 0f,
    val tiltRadians: Float = 0f,
    val tool: String = "None",
    val sampleRateHz: Float = 0f,
    val zoom: Float = 1f,
    val allocatedTiles: Int = 0,
    val dirtyTiles: Int = 0,
    val undoBytes: Long = 0,
)
