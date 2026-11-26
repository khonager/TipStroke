package dev.tipstroke.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import dev.tipstroke.core.canvas.*
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

interface AppleNewDrawingActions {
    fun createDrawing(name: String, width: Int, height: Int)
    fun createFromImage()
    fun cancelNewDrawing()
}

/** Host persists documents; this bridge persists only local picker preferences. */
class AppleNewDrawingBridge(private val actions: AppleNewDrawingActions) {
    fun controller(screenWidth: Int, screenHeight: Int, tileBudget: Int): UIViewController = ComposeUIViewController {
        MaterialTheme {
            val defaults = remember { NSUserDefaults.standardUserDefaults }
            val square = (kotlin.math.sqrt(tileBudget.coerceAtLeast(1).toDouble()).toInt() * 256).coerceIn(16, 8192)
            val builtIns = remember { listOf(CanvasPreset("screen", "Screen size", screenWidth.coerceIn(16, 8192),
                screenHeight.coerceIn(16, 8192), "Everyday"), CanvasPreset("max-square", "Max one-layer square", square, square, "Everyday")) + standardCanvasPresets() }
            var saved by remember { mutableStateOf(CanvasPresetCodec.decode(defaults.stringForKey("canvas.saved").orEmpty(), 8192)) }
            var favorites by remember { mutableStateOf(defaults.stringForKey("canvas.favorites").orEmpty().split('|').filter { it.isNotEmpty() }.distinct().take(8)) }
            var defaultId by remember { mutableStateOf(defaults.stringForKey("canvas.default") ?: "max-square") }
            val initial = (builtIns + saved).find { it.id == defaultId } ?: builtIns.first()
            var selectedId by remember { mutableStateOf<String?>(initial.id) }
            var name by remember { mutableStateOf("Untitled") }
            var width by remember { mutableStateOf(initial.widthPx.toString()) }
            var height by remember { mutableStateOf(initial.heightPx.toString()) }
            var presetName by remember { mutableStateOf("") }
            val validWidth = width.toIntOrNull()?.takeIf { it in 16..8192 }
            val validHeight = height.toIntOrNull()?.takeIf { it in 16..8192 }
            fun select(preset: CanvasPreset) {
                selectedId = preset.id; width = preset.widthPx.toString(); height = preset.heightPx.toString()
            }
            fun storeSaved() { defaults.setObject(CanvasPresetCodec.encode(saved), forKey = "canvas.saved") }
            fun storeFavorites() { defaults.setObject(favorites.joinToString("|"), forKey = "canvas.favorites") }
            Surface {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp)) {
                    Text("New drawing", style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = actions::createFromImage) { Text("From image") }
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                        ResolutionBrowser(builtIns + saved, selectedId, favorites, ::select, {
                            favorites = it; storeFavorites()
                        }, customPreview = if (validWidth != null && validHeight != null)
                            CanvasPreset("custom-preview", "Custom", validWidth, validHeight, "Custom") else null)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(width, { width = it.filter(Char::isDigit); selectedId = null }, Modifier.weight(1f),
                                label = { Text("Width px") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(height, { height = it.filter(Char::isDigit); selectedId = null }, Modifier.weight(1f),
                                label = { Text("Height px") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        Text("Each side: 16–8192 px", style = MaterialTheme.typography.bodySmall)
                        if (selectedId == null) {
                            OutlinedTextField(presetName, { presetName = it }, Modifier.fillMaxWidth(), label = { Text("Custom preset name") }, singleLine = true)
                            TextButton(enabled = validWidth != null && validHeight != null && presetName.isNotBlank(), onClick = {
                                val id = "custom-${validWidth}x${validHeight}"
                                val preset = CanvasPreset(id, presetName.trim(), validWidth!!, validHeight!!, "Saved")
                                saved = saved.filterNot { it.id == id } + preset; storeSaved(); select(preset); presetName = ""
                            }) { Text("Save custom preset") }
                        }
                        selectedId?.let { id ->
                            TextButton(onClick = { defaultId = id; defaults.setObject(id, forKey = "canvas.default") }, enabled = defaultId != id) {
                                Text(if (defaultId == id) "Default preset" else "Set as default")
                            }
                            if (saved.any { it.id == id }) TextButton(onClick = {
                                saved = saved.filterNot { it.id == id }; storeSaved()
                                favorites = favorites - id; storeFavorites()
                                if (defaultId == id) { defaultId = "max-square"; defaults.setObject(defaultId, forKey = "canvas.default") }
                                select(builtIns.first())
                            }) { Text("Remove saved preset") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { actions.cancelNewDrawing() }) { Text("Cancel") }
                        Button(enabled = validWidth != null && validHeight != null, onClick = {
                            actions.createDrawing(name.trim().ifBlank { "Untitled" }, validWidth!!, validHeight!!)
                        }) { Text("Create") }
                    }
                }
            }
        }
    }
}
