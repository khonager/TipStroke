@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.tipstroke.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import dev.tipstroke.core.brush.BrushCollection
import kotlin.uuid.Uuid
import kotlin.io.encoding.Base64
import dev.tipstroke.core.model.*
import platform.UIKit.UIViewController
import platform.Foundation.NSUserDefaults

/** Commands/settings only. UIKit owns touches, wet paint, tiles and viewport transforms. */
interface AppleBrushLibraryActions {
    fun librarySaved(data: String)
    fun importFromFile()
    fun exportToFile(data: String)
}

interface AppleLayerBlendActions {
    fun blendChanged(mode: String)
}

interface AppleEditorActions {
    fun brushChanged(brush: NativeBrush)
    fun colorChanged(red: Float, green: Float, blue: Float)
    fun fingerDrawingChanged(enabled: Boolean)
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class AppleEditorBridge(private val actions: AppleEditorActions) {
    private var library by mutableStateOf(emptyList<LibraryBrush>())
    private var brush by mutableStateOf(BrushPreset.Ink)
    private var color by mutableStateOf(RgbaColor(0f, 0f, 0f))
    private var fingerDrawing by mutableStateOf(true)
    private var mode by mutableStateOf(ColorPickerMode.HSV_WHEEL)
    private var drawingColors by mutableStateOf(emptyList<RgbaColor>())
    private var paletteCount by mutableIntStateOf(4)
    private val defaults = NSUserDefaults.standardUserDefaults
    private var pins by mutableStateOf(defaults.stringArrayForKey("tip.pins")?.filterIsInstance<String>().orEmpty())
    private var brushInitialized = false
    private var history by mutableStateOf(decodeColors(defaults.stringForKey("colors.recent").orEmpty()).take(10))
    private val shelf = ColorShelf(defaults.stringForKey("colors.saved").orEmpty(),
        defaults.integerForKey("colors.slots").toInt().takeIf { it in 2..16 } ?: 4, defaults.boolForKey("colors.sourceRecent")) { data, slots, recent ->
        defaults.setObject(data, forKey = "colors.saved"); defaults.setInteger(slots.toLong(), forKey = "colors.slots")
        defaults.setBool(recent, forKey = "colors.sourceRecent")
    }

    fun brushData(): String = BrushPackage.encode(LibraryBrush(brush))
    fun restoreBrush(data: String): Boolean {
        val restored = runCatching { BrushPackage.decode(data).preset }.getOrNull() ?: return false
        updateBrush(restored.copy(baseSizePx = restored.baseSizePx.coerceIn(2f, 512f)))
        return true
    }
    fun selectBrush(id: String) {
        val preset = (BrushPreset.libraryBuiltIns + library.map { it.preset }).firstOrNull { it.id.value == id } ?: BrushPreset.Ink
        selectPreset(preset)
    }
    private fun selectPreset(preset: BrushPreset) {
        val saved = defaults.stringForKey("tip.tuning." + preset.id.value)
        val tuned = saved?.let { runCatching { BrushPackage.decode(it).preset }.getOrNull() }
        updateBrush(tuned?.takeIf { it.id == preset.id } ?: preset)
    }
    private fun updateBrush(value: BrushPreset) {
        if (brushInitialized && brush.id != value.id) defaults.setObject(brushData(), forKey = "tip.tuning." + brush.id.value)
        brushInitialized = true
        brush = value
        actions.brushChanged(NativeBrush(value))
    }
    fun setColor(red: Float, green: Float, blue: Float) { color = RgbaColor(red, green, blue) }
    fun setDrawingColors(data: String) { drawingColors = decodeColors(data).take(8) }
    fun recordColor() {
        history = (listOf(color) + history).distinct().take(10)
        defaults.setObject(encodeColors(history), forKey = "colors.recent")
    }
    fun restore(id: String, size: Float, opacity: Float, finger: Boolean) {
        val preset = (BrushPreset.libraryBuiltIns + library.map { it.preset }).firstOrNull { it.id.value == id } ?: BrushPreset.Ink
        updateBrush(preset.copy(baseSizePx = if (size.isFinite()) size.coerceIn(2f, 512f) else preset.baseSizePx,
            opacity = if (opacity.isFinite()) opacity.coerceIn(.05f, 1f) else preset.opacity))
        fingerDrawing = finger
        actions.fingerDrawingChanged(finger)
    }
    fun restoreLibrary(data: String): Boolean {
        val decoded = runCatching { BrushCollection.decode(data) }.getOrNull() ?: return false
        if (decoded.any { item -> BrushPreset.libraryBuiltIns.any { it.id == item.preset.id } }) return false
        library = decoded; return true
    }
    fun importBrushes(data: String): Boolean {
        val decoded = runCatching { BrushCollection.decode(data) }.getOrNull() ?: return false
        if (library.size + decoded.size > 200) return false
        library = library + decoded.map { it.copy(preset = it.preset.copy(id = BrushId("custom-${Uuid.random()}"))) }
        library.lastOrNull()?.let { updateBrush(it.preset) }; return true
    }
    fun setTexture(encoded: String, width: Int, height: Int, grain: Boolean): Boolean = runCatching {
        require(encoded.length <= 90000)
        val texture = BrushTexture(width, height, Base64.decode(encoded))
        val stamp = brush.stamp ?: StampBrush()
        val updated = if (grain) stamp.copy(grain = StampGrain.IMAGE, grainTexture = texture)
            else stamp.copy(shape = StampShape.IMAGE, shapeTexture = texture)
        updateBrush(brush.copy(engine = BrushEngine.INK, ink = null, stamp = updated))
        true
    }.getOrDefault(false)
    fun editTexture(grain: Boolean, rotate: Boolean) {
        val stamp = brush.stamp ?: return
        val source = (if (grain) stamp.grainTexture else stamp.shapeTexture) ?: return
        val changed = if (rotate) source.rotated() else source.inverted()
        updateBrush(brush.copy(stamp = if (grain) stamp.copy(grainTexture = changed) else stamp.copy(shapeTexture = changed)))
    }
    fun libraryData(): String = BrushCollection.encode(library)
    fun libraryController(callbacks: AppleBrushLibraryActions): UIViewController = ComposeUIViewController {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                val groups = remember { defaults.dictionaryForKey("tip.groups").orEmpty().entries.mapNotNull { (name, values) ->
                    (name as? String)?.let { it to (values as? List<*>)?.filterIsInstance<String>().orEmpty() }
                }.toMutableStateMap() }
                BrushCollectionPanel(library, brush, ::selectPreset,
                    onSave = { preset, name ->
                        if (library.size >= 200) return@BrushCollectionPanel
                        val saved = preset.copy(id = BrushId("custom-${Uuid.random()}"), displayName = name)
                        library = library + LibraryBrush(saved); updateBrush(saved); callbacks.librarySaved(libraryData())
                    }, onDelete = { preset ->
                        val id = preset.id.value
                        library = library.filterNot { it.preset.id == preset.id }
                        if (brush.id == preset.id) selectBrush(BrushPreset.Ink.id.value)
                        defaults.removeObjectForKey("tip.tuning." + id)
                        pins = pins - id
                        defaults.setObject(pins, forKey = "tip.pins")
                        for (name in groups.keys.toList()) groups[name] = groups.getValue(name) - id
                        defaults.setObject(groups.toMap(), forKey = "tip.groups")
                        callbacks.librarySaved(libraryData())
                    },
                    onImport = callbacks::importFromFile, onExport = { callbacks.exportToFile(BrushPackage.encode(LibraryBrush(it))) },
                    pins = pins, onPins = { pins = it; defaults.setObject(it, forKey = "tip.pins") },
                    groups = groups.toMap(), onGroups = { groups.clear(); groups.putAll(it); defaults.setObject(it, forKey = "tip.groups") })
            }
        }
    }
    fun brushController(): UIViewController = ComposeUIViewController {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    var hold by remember { mutableStateOf(defaults.objectForKey("holdDrawingTools") == null || defaults.boolForKey("holdDrawingTools")) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Hold for drawing tools", Modifier.weight(1f))
                        Switch(hold, { hold = it; defaults.setBool(it, forKey = "holdDrawingTools") })
                    }
                    BrushSettingsPanel(brush, { if (it.id == brush.id) updateBrush(it) else selectPreset(it) }, fingerDrawing) {
                        fingerDrawing = it; actions.fingerDrawingChanged(it)
                    }
                }
            }
        }
    }
    fun quickColorsController(): UIViewController = ComposeUIViewController {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val choices = BrushPreset.libraryBuiltIns + library.map { it.preset }
                    for (id in pins) {
                        val preset = choices.firstOrNull { it.id.value == id } ?: continue
                        TextButton(onClick = { selectPreset(preset) }) { Text(preset.displayName) }
                    }
                    val quick = (listOf(color) + shelf.saved + if (shelf.recent) history else drawingColors).distinct().take(shelf.slots)
                    quick.forEachIndexed { index, value ->
                        Box(Modifier.size(32.dp).background(Color(value.red, value.green, value.blue, value.alpha), CircleShape)
                            .semantics { contentDescription = "Quick color ${index + 1}" }
                            .clickable { color = value; actions.colorChanged(value.red, value.green, value.blue) })
                    }
                }
            }
        }
    }
    fun colorController(): UIViewController = ComposeUIViewController {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    ColorPickerPanel(color, mode, drawingColors, paletteCount, colorHistory = history,
                        onColorSelected = { color = it; actions.colorChanged(it.red, it.green, it.blue) },
                        onModeChanged = { mode = it }, onPaletteColorCountChanged = { paletteCount = it.coerceIn(1, 8) },
                        onClearHistory = { history = emptyList(); defaults.removeObjectForKey("colors.recent") }, showOriginalImageColors = false,
                        shelf = { selected, choose -> ColorShelfEditor(shelf, selected, choose) },
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    fun blendController(layerId: String, modeName: String, callbacks: AppleLayerBlendActions): UIViewController = ComposeUIViewController {
        var selected by remember { mutableStateOf(LayerBlendMode.fromStorage(modeName)) }
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Blend mode", style = MaterialTheme.typography.titleLarge)
                    BlendModeScroller(LayerId(layerId), selected,
                        { selected = it; callbacks.blendChanged(it.name) }, {}, {})
                }
            }
        }
    }
}
