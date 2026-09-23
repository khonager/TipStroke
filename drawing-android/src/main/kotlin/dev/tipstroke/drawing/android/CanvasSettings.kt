package dev.tipstroke.drawing.android

import android.os.Build
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
    /** The Android Emulator presents its host pointer as a single touchscreen contact. */
    @Volatile var emulateMouseWithTouch: Boolean = isAndroidEmulator()
}

internal fun isAndroidEmulator(
    fingerprint: String = Build.FINGERPRINT,
    model: String = Build.MODEL,
    product: String = Build.PRODUCT,
    hardware: String = Build.HARDWARE,
    device: String = Build.DEVICE,
    manufacturer: String = Build.MANUFACTURER,
): Boolean =
    fingerprint.startsWith("generic") ||
        fingerprint.contains("emulator", ignoreCase = true) ||
        model.contains("Emulator", ignoreCase = true) ||
        model.contains("sdk_gphone", ignoreCase = true) ||
        product.startsWith("sdk_") ||
        hardware.contains("ranchu", ignoreCase = true) ||
        hardware.contains("goldfish", ignoreCase = true) ||
        device.startsWith("emu") ||
        manufacturer.contains("Genymotion", ignoreCase = true)

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
    val processBytes: Long = 0,
    val processBudgetBytes: Long = 0,
    val deviceAvailableBytes: Long = 0,
    val documentBytes: Long = 0,
    val fullLayerBytes: Long = 0,
    val fullLayersRemaining: Int = 0,
    val memoryPressure: MemoryPressure = MemoryPressure.NORMAL,
)
