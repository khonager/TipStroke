package dev.tipstroke.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import dev.tipstroke.core.editor.NativeBrush
import dev.tipstroke.core.brush.BrushPackage
import dev.tipstroke.core.brush.LibraryBrush
import dev.tipstroke.core.model.*
import platform.UIKit.UIViewController

/** Commands/settings only. UIKit owns touches, wet paint, tiles and viewport transforms. */
interface AppleEditorActions {
    fun brushChanged(brush: NativeBrush)
    fun colorChanged(red: Float, green: Float, blue: Float)
    fun fingerDrawingChanged(enabled: Boolean)
}

class AppleEditorBridge(private val actions: AppleEditorActions) {
    private var brush by mutableStateOf(BrushPreset.Ink)
    private var color by mutableStateOf(RgbaColor(0f, 0f, 0f))
    private var fingerDrawing by mutableStateOf(true)
    private var mode by mutableStateOf(ColorPickerMode.HSV_WHEEL)
    private var history by mutableStateOf(emptyList<RgbaColor>())

    fun brushData(): String = BrushPackage.encode(LibraryBrush(brush))
    fun restoreBrush(data: String): Boolean {
        val restored = runCatching { BrushPackage.decode(data).preset }.getOrNull() ?: return false
        if (restored.ink != null || restored.stamp != null || restored.id !in BrushPreset.builtIns.map { it.id }) return false
        updateBrush(restored.copy(baseSizePx = restored.baseSizePx.coerceIn(2f, 512f)))
        return true
    }
    fun selectBrush(id: String) {
        updateBrush(BrushPreset.builtIns.firstOrNull { it.id.value == id } ?: BrushPreset.Ink)
    }
    private fun updateBrush(value: BrushPreset) { brush = value; actions.brushChanged(NativeBrush(value)) }
    fun setColor(red: Float, green: Float, blue: Float) { color = RgbaColor(red, green, blue) }
    fun recordColor() { history = (listOf(color) + history).distinct().take(10) }
    fun restore(id: String, size: Float, opacity: Float, finger: Boolean) {
        val preset = BrushPreset.builtIns.firstOrNull { it.id.value == id } ?: BrushPreset.Ink
        updateBrush(preset.copy(baseSizePx = if (size.isFinite()) size.coerceIn(2f, 512f) else preset.baseSizePx,
            opacity = if (opacity.isFinite()) opacity.coerceIn(.05f, 1f) else preset.opacity))
        fingerDrawing = finger
        actions.fingerDrawingChanged(finger)
    }
    fun brushController(): UIViewController = ComposeUIViewController {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                BrushSettingsPanel(brush, ::updateBrush, fingerDrawing) {
                    fingerDrawing = it; actions.fingerDrawingChanged(it)
                }
            }
        }
    }
    fun colorController(): UIViewController = ComposeUIViewController {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    ColorPickerPanel(color, mode, emptyList(), 4, colorHistory = history,
                        onColorSelected = { color = it; actions.colorChanged(it.red, it.green, it.blue) },
                        onModeChanged = { mode = it }, onPaletteColorCountChanged = {},
                        onClearHistory = { history = emptyList() }, showOriginalImageColors = false,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    fun blendController(layerId: String, modeName: String, onMode: (String) -> Unit): UIViewController = ComposeUIViewController {
        var selected by remember { mutableStateOf(LayerBlendMode.fromStorage(modeName)) }
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Blend mode", style = MaterialTheme.typography.titleLarge)
                    BlendModeScroller(LayerId(layerId), selected,
                        { selected = it; onMode(it.name) }, {}, {})
                }
            }
        }
    }
}
