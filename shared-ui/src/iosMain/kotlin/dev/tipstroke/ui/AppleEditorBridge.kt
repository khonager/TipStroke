@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.tipstroke.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.ByteVar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import dev.tipstroke.core.editor.NativeBrush
import dev.tipstroke.core.brush.BrushCategory
import dev.tipstroke.core.brush.BrushCategoriesData
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
    fun pickedTip(id: String, eraser: Boolean)
    fun closePicker()
    fun openLibrary()
    fun importFromFile()
    fun exportToFile(data: String)
}

interface AppleLayerBlendActions {
    fun blendChanged(mode: String)
}

interface AppleEditorActions {
    fun strokePreview(brush: NativeBrush, eraser: Boolean): NSData?
    fun eraserChanged()
    fun brushChanged(brush: NativeBrush)
    fun colorChanged(red: Float, green: Float, blue: Float)
    fun fingerDrawingChanged(enabled: Boolean)
    fun swatchDrag(x: Float, y: Float, red: Float, green: Float, blue: Float, ended: Boolean, cancelled: Boolean)
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
    private var pins by mutableStateOf(defaults.stringArrayForKey("tip.pins")?.filterIsInstance<String>() ?: BrushPreset.builtIns.map { it.id.value })
    private var dockPins by mutableStateOf(defaults.stringArrayForKey("tip.dockPins")?.filterIsInstance<String>()
        ?: pins.toList().also { defaults.setObject(it, forKey = "tip.dockPins") })
    private val drawingPosition = TipPickerPosition().apply { open("All") }
    private val eraserPosition = TipPickerPosition()
    fun currentDrawingBrush() = NativeBrush(brush)
    private fun eraserSource(): BrushPreset {
        val id = defaults.stringForKey("tip.eraser")
        val preset = (BrushPreset.libraryBuiltIns + library.map { it.preset }).find { it.id.value == id } ?: BrushPreset.Ink
        val saved = defaults.stringForKey("tip.eraserTuning." + preset.id.value)
        return saved?.let { runCatching { BrushPackage.decode(it).preset }.getOrNull() } ?: preset
    }
    fun eraserBrush(): NativeBrush = NativeBrush(NativeBrush(eraserSource()).eraserPreset())
    private fun saveEraser(value: BrushPreset) {
        defaults.setObject(BrushPackage.encode(LibraryBrush(value)), forKey = "tip.eraserTuning." + value.id.value)
    }
    fun setEraserAdjustment(size: Float, opacity: Float) {
        saveEraser(eraserSource().copy(baseSizePx = size, opacity = opacity))
    }
    private var groups by mutableStateOf(loadGroups())
    private fun loadGroups(): List<BrushCategory> {
        defaults.stringForKey("tip.groups.v2")?.let { data ->
            runCatching { BrushCategoriesData.decode(data) }.getOrNull()?.let { return it }
        }
        val legacy = defaults.dictionaryForKey("tip.groups").orEmpty().entries.mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            BrushCategory(name = name, brushIds = (value as? List<*>)?.filterIsInstance<String>().orEmpty().distinct().take(200))
        }.sortedBy { it.name }.take(64)
        return legacy + BrushCategoriesData.defaults().filter { default ->
            legacy.none { it.name.equals(default.name, true) }
        }.take(64 - legacy.size)
    }
    private fun saveGroups(value: List<BrushCategory>) {
        defaults.setObject(BrushCategoriesData.encode(value), forKey = "tip.groups.v2")
        groups = value
    }
    fun dockGroups(): List<BrushCategory> {
        val ids = (BrushPreset.libraryBuiltIns + library.map { it.preset }).map { it.id.value }.toSet()
        return groups.filter { it.showInDock }.map { it.copy(brushIds = it.brushIds.filter(ids::contains)) }
            .filter { it.brushIds.isNotEmpty() }
    }
    fun dockBrushNames(): Map<String, String> =
        (BrushPreset.libraryBuiltIns + library.map { it.preset }).associate { it.id.value to it.displayName }

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
    fun setFingerDrawing(enabled: Boolean) {
        fingerDrawing = enabled
        actions.fingerDrawingChanged(enabled)
    }

    fun setDrawingAdjustment(size: Float, opacity: Float) {
        // The native tool stays active (including shape and smudge modes).
        brush = brush.copy(baseSizePx = size.coerceIn(2f, 512f), opacity = opacity.coerceIn(.05f, 1f))
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
    @Composable
    private fun BrushTheme(eraser: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalStrokePreview provides { tip, modifier ->
            val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, tip, eraser) {
                value = withContext(Dispatchers.Default) {
                    val data = actions.strokePreview(NativeBrush(tip), eraser) ?: return@withContext null
                    val bytes = data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt())
                    bytes?.let { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }
                }
            }
            Box(modifier.background(Color(0xff303238))) {
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }) { MaterialTheme(colorScheme = darkColorScheme(), content = content) }
    }

    fun libraryController(callbacks: AppleBrushLibraryActions, openGroups: Boolean): UIViewController = ComposeUIViewController {
        BrushTheme {
            Surface(Modifier.fillMaxSize()) {
                var manage by remember { mutableStateOf(openGroups) }
                var editing by remember { mutableStateOf<String?>(null) }
                if (manage) {
                    val group = groups.find { it.id == editing }
                    if (group == null) {
                        BrushCategoryManager(groups, ::saveGroups, { editing = it }, { manage = false }) { _, label, enabled, click ->
                            TextButton(onClick = click, enabled = enabled) { Text(label) }
                        }
                    } else {
                        BrushGroupMembers(group, BrushPreset.libraryBuiltIns + library.map { it.preset }, { updated ->
                            saveGroups(groups.map { if (it.id == updated.id) updated else it })
                        }) { editing = null }
                    }
                }
                else BrushCollectionPanel(library, brush, ::selectPreset,
                    onSave = { preset, name ->
                        if (library.size >= 200) return@BrushCollectionPanel
                        val saved = preset.copy(id = BrushId("custom-${Uuid.random()}"), displayName = name)
                        library = library + LibraryBrush(saved); updateBrush(saved); callbacks.librarySaved(libraryData())
                    }, onDelete = { preset ->
                        val id = preset.id.value
                        library = library.filterNot { it.preset.id == preset.id }
                        if (brush.id == preset.id) selectBrush(BrushPreset.Ink.id.value)
                        defaults.removeObjectForKey("tip.tuning." + id)
                        dockPins = dockPins - id
                        defaults.setObject(dockPins, forKey = "tip.dockPins")
                        pins = pins - id
                        defaults.setObject(pins, forKey = "tip.pins")
                        saveGroups(groups.map { it.copy(brushIds = it.brushIds - id) })
                        callbacks.librarySaved(libraryData())
                    },
                    onImport = callbacks::importFromFile, onExport = { callbacks.exportToFile(BrushPackage.encode(LibraryBrush(it))) },
                    pins = pins, onPins = { pins = it; defaults.setObject(it, forKey = "tip.pins") },
                    groups = groups.associate { it.name to it.brushIds }, onGroups = { updated ->
                        saveGroups(updated.map { (name, ids) ->
                            groups.find { it.name == name }?.copy(brushIds = ids) ?: BrushCategory(name = name, brushIds = ids)
                        })
                    }, onManageGroups = { manage = true })
            }
        }
    }
    fun pickerController(callbacks: AppleBrushLibraryActions, eraser: Boolean, pinLimit: Int): UIViewController = ComposeUIViewController {
        val tips = BrushPreset.libraryBuiltIns + library.map { it.preset }
        BrushTheme(eraser) {
            Surface(Modifier.fillMaxSize()) {
                TipPicker(if (eraser) eraserPosition else drawingPosition, eraser,
                    if (eraser) defaults.stringForKey("tip.eraser") ?: BrushPreset.Ink.id.value else brush.id.value,
                    tips, pins, dockPins, pinLimit, groups,
                    { pins = it; defaults.setObject(it, forKey = "tip.pins") },
                    { dockPins = it; defaults.setObject(it, forKey = "tip.dockPins") }, ::saveGroups,
                    onPick = {
                        if (eraser) defaults.setObject(it.id.value, forKey = "tip.eraser")
                        callbacks.pickedTip(it.id.value, eraser)
                        callbacks.closePicker()
                    }, onLibrary = callbacks::openLibrary, onClose = callbacks::closePicker,
                    canRename = { p -> library.any { it.preset.id == p.id } },
                    onRename = { p, name ->
                        library = library.map { if (it.preset.id == p.id) it.copy(preset = it.preset.copy(displayName = name)) else it }
                        callbacks.librarySaved(libraryData())
                    })
            }
        }
    }
    fun pinsController(callbacks: AppleBrushLibraryActions): UIViewController = ComposeUIViewController {
        val byId = (BrushPreset.libraryBuiltIns + library.map { it.preset }).associateBy { it.id.value }
        BrushTheme {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val slots = (maxWidth / 44.dp).toInt().coerceAtLeast(1)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
                    val pick: (BrushPreset) -> Unit = { callbacks.pickedTip(it.id.value, false) }
                    PinnedTipCells(dockPins.mapNotNull(byId::get), slots, 44.dp, pick) { tip ->
                        TipSwatch(tip, Modifier.size(44.dp).clickable { pick(tip) }
                            .semantics { contentDescription = tip.displayName }.padding(2.dp))
                    }
                }
            }
        }
    }
    fun brushController(): UIViewController = settingsController(false)
    fun settingsController(eraser: Boolean): UIViewController = ComposeUIViewController {
        BrushTheme(eraser) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    var eraserPreset by remember { mutableStateOf(if (eraser) eraserSource() else brush) }
                    val active = if (eraser) eraserPreset else brush
                    TipSwatch(active, Modifier.fillMaxWidth().height(56.dp))
                    var hold by remember { mutableStateOf(defaults.objectForKey("holdDrawingTools") == null || defaults.boolForKey("holdDrawingTools")) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Hold for drawing tools", Modifier.weight(1f))
                        Switch(hold, { hold = it; defaults.setBool(it, forKey = "holdDrawingTools") })
                    }
                    BrushSettingsPanel(active, { next ->
                        if (eraser) {
                            if (next.id != eraserPreset.id) defaults.setObject(next.id.value, forKey = "tip.eraser")
                            eraserPreset = if (next.id == eraserPreset.id) next else eraserSource()
                            saveEraser(eraserPreset)
                            actions.eraserChanged()
                        } else if (next.id == brush.id) updateBrush(next) else selectPreset(next)
                    }, fingerDrawing) {
                        setFingerDrawing(it)
                    }
                }
            }
        }
    }
    fun quickColorsController(): UIViewController = ComposeUIViewController {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val quick = (listOf(color) + shelf.saved + if (shelf.recent) history else drawingColors).distinct().take(shelf.slots)
                    quick.forEachIndexed { index, value ->
                        var origin by remember { mutableStateOf(Offset.Zero) }
                        Box(Modifier.size(32.dp).onGloballyPositioned { origin = it.positionInRoot() }
                            .pointerInput(value) {
                                var position = Offset.Zero
                                fun send(ended: Boolean = false, cancelled: Boolean = false) {
                                    val point = (origin + position) / density
                                    actions.swatchDrag(point.x, point.y, value.red, value.green, value.blue, ended, cancelled)
                                }
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { position = it; send() },
                                    onDrag = { change, _ -> change.consume(); position = change.position; send() },
                                    onDragEnd = { send(ended = true) }, onDragCancel = { send(cancelled = true) })
                            }.background(Color(value.red, value.green, value.blue, value.alpha), CircleShape)
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
