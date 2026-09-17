package dev.tipstroke.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import dev.tipstroke.core.model.*
import dev.tipstroke.drawing.android.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun CanvasScreen(
    initialLayersOpen: Boolean = false,
    initialColorPickerOpen: Boolean = false,
    initialSelectionOpen: Boolean = false,
    documentId: String? = null,
    documentName: String = "Untitled drawing",
    canvasWidthPx: Int = 2048,
    canvasHeightPx: Int = 2048,
    loadExisting: Boolean = false,
    library: DrawingLibrary? = null,
    gestureSettings: GestureSettings = GestureSettings(),
    onBackToGallery: (() -> Unit)? = null,
    onSaveActionChanged: (((() -> Unit)?) -> Unit)? = null,
    onStylusButtonHandlerChanged: ((((StylusButton) -> Unit)?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val brushPreferences = remember { BrushPreferences(context) }
    val colorHistoryPreferences = remember { ColorHistoryPreferences(context) }
    val initialBrushTuning = remember { brushPreferences.load(BrushPreset.Ink) }
    val initialEraserTuning = remember { brushPreferences.loadEraser() }
    var surface by remember { mutableStateOf<DrawingSurface?>(null) }
    var brush by remember { mutableStateOf(BrushPreset.Ink) }
    var erasing by remember { mutableStateOf(false) }
    var size by remember { mutableFloatStateOf(initialBrushTuning.sizePx) }
    var opacity by remember { mutableFloatStateOf(initialBrushTuning.opacity) }
    var color by remember { mutableStateOf(RgbaColor(.05f, .05f, .06f)) }
    var drawingPalette by remember { mutableStateOf<List<RgbaColor>>(emptyList()) }
    var paletteColorCount by remember { mutableIntStateOf(3) }
    var colorHistory by remember { mutableStateOf(colorHistoryPreferences.load()) }
    var colorPickerOpen by remember { mutableStateOf(initialColorPickerOpen) }
    var debug by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(CanvasDiagnostics()) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var layers by remember { mutableStateOf<List<LayerSummary>>(emptyList()) }
    var layerPreviews by remember { mutableStateOf<Map<LayerId, Bitmap>>(emptyMap()) }
    var selectedLayerId by remember { mutableStateOf<LayerId?>(null) }
    var selectedLayerIds by remember { mutableStateOf<Set<LayerId>>(emptySet()) }
    var layersOpen by remember { mutableStateOf(initialLayersOpen) }
    var message by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(library == null) }
    var saving by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportRequest?>(null) }
    var imageTransforming by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(initialSelectionOpen) }
    var selectionTool by remember { mutableStateOf(SelectionTool.LASSO) }
    var movingSelection by remember { mutableStateOf(false) }
    var hasSelection by remember { mutableStateOf(false) }
    var brushStudioOpen by remember { mutableStateOf(false) }
    var brushHardness by remember { mutableFloatStateOf(initialBrushTuning.hardness) }
    var eraserHardness by remember { mutableFloatStateOf(initialEraserTuning.hardness) }
    var pressureSize by remember { mutableStateOf(initialBrushTuning.pressureSize) }
    var pressureOpacity by remember { mutableStateOf(initialBrushTuning.pressureOpacity) }
    var speedTaper by remember { mutableStateOf(initialBrushTuning.speedTaper) }
    val lastStylusButtonAt = remember { longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE) }

    val importImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            surface?.addImage(uri)?.fold(
                onSuccess = { imageTransforming = true },
                onFailure = { message = it.message ?: "This image could not be opened." },
            )
            layersOpen = true
        }
    }

    val exportDestination = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pendingExport
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && request != null && uri != null) {
            exporting = true
            surface?.exportDrawing(uri, request.format, request.quality, request.scale, request.transparent) { outcome ->
                exporting = false
                message = outcome.fold({ "Export saved." }, { it.message ?: "Export failed." })
            }
        }
        pendingExport = null
    }

    fun sync() { surface?.settings?.apply {
        this.brush = brush.copy(
            hardness = brushHardness,
            pressureToSize = if (pressureSize) brush.pressureToSize else PressureCurve(1f, 1f, 1f),
            pressureToOpacity = if (pressureOpacity) brush.pressureToOpacity else PressureCurve(1f, 1f, 1f),
            speedTaper = if (speedTaper) .55f else 0f,
        )
        sizePx = size; this.opacity = opacity; this.color = color; this.erasing = erasing
        this.eraserHardness = eraserHardness; this.debug = debug; gestures = gestureSettings
    } }
    fun applyTuning(tuning: BrushTuning) {
        size = tuning.sizePx; opacity = tuning.opacity; brushHardness = tuning.hardness
        pressureSize = tuning.pressureSize; pressureOpacity = tuning.pressureOpacity; speedTaper = tuning.speedTaper
    }
    fun toggleEraser() {
        erasing = !erasing
        applyTuning(if (erasing) brushPreferences.loadEraser() else brushPreferences.load(brush))
        sync()
    }
    fun performStylusButton(button: StylusButton) {
        val now = android.os.SystemClock.uptimeMillis()
        val buttonIndex = button.ordinal
        if (lastStylusButtonAt[buttonIndex] != Long.MIN_VALUE && now - lastStylusButtonAt[buttonIndex] < 80L) return
        lastStylusButtonAt[buttonIndex] = now
        val action = when (button) {
            StylusButton.PRIMARY -> gestureSettings.stylusPrimaryButton
            StylusButton.SECONDARY -> gestureSettings.stylusSecondaryButton
        }
        when (action) {
            StylusButtonAction.TOGGLE_ERASER -> toggleEraser()
            StylusButtonAction.UNDO -> surface?.undo()
            StylusButtonAction.REDO -> surface?.redo()
            StylusButtonAction.DISABLED -> Unit
        }
    }
    LaunchedEffect(brush, erasing, size, opacity, color, brushHardness, eraserHardness, pressureSize, pressureOpacity, speedTaper, debug, gestureSettings, surface) { sync() }
    LaunchedEffect(surface, paletteColorCount) { surface?.setPaletteColorCount(paletteColorCount) }
    LaunchedEffect(imageTransforming, surface) { surface?.setImageTransformMode(imageTransforming) }
    LaunchedEffect(selectionMode, surface) { surface?.setSelectionMode(selectionMode) }
    LaunchedEffect(movingSelection, surface) { surface?.setSelectionMoveMode(movingSelection) }
    LaunchedEffect(brush.id, erasing, size, opacity, brushHardness, pressureSize, pressureOpacity, speedTaper) {
        val tuning = BrushTuning(size, opacity, brushHardness, pressureSize, pressureOpacity, speedTaper)
        if (erasing) brushPreferences.saveEraser(tuning) else brushPreferences.save(brush, tuning)
    }
    LaunchedEffect(surface, ready, library, documentId) {
        while (surface != null && ready && library != null && documentId != null) {
            delay(30_000)
            if (!saving) {
                saving = true
                surface?.saveProject(library, documentId, documentName) { saving = false }
            }
        }
    }
    val leaveEditor: () -> Unit = {
        val destination = onBackToGallery
        if (destination != null && !saving) {
            if (library != null && documentId != null && ready) {
                saving = true
                surface?.saveProject(library, documentId, documentName) { result ->
                    saving = false
                    if (result.isSuccess) destination() else message = result.exceptionOrNull()?.message ?: "Save failed."
                }
            } else destination()
        }
    }
    val saveWithoutLeaving: () -> Unit = {
        if (!saving && ready && surface != null && library != null && documentId != null) {
            saving = true
            surface?.saveProject(library, documentId, documentName) { result ->
                saving = false
                result.exceptionOrNull()?.let { message = it.message ?: "Autosave failed." }
            }
        }
    }
    SideEffect { onSaveActionChanged?.invoke(saveWithoutLeaving) }
    DisposableEffect(onSaveActionChanged) {
        onDispose { onSaveActionChanged?.invoke(null) }
    }
    DisposableEffect(onStylusButtonHandlerChanged, gestureSettings, surface) {
        onStylusButtonHandlerChanged?.invoke(::performStylusButton)
        onDispose { onStylusButtonHandlerChanged?.invoke(null) }
    }
    BackHandler(enabled = onBackToGallery != null, onBack = leaveEditor)
    BackHandler(enabled = colorPickerOpen) { colorPickerOpen = false }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val portrait = maxHeight > maxWidth
        val viewportHeight = maxHeight
        val compactLandscape = !portrait && maxHeight < 760.dp
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> DrawingSurface(context).also { view ->
                surface = view
                view.diagnosticsListener = { diagnostics = it }
                view.historyListener = { undo, redo -> canUndo = undo; canRedo = redo }
                view.layersListener = { updated, selected, selectedIds, previews ->
                    layers = updated
                    selectedLayerId = selected
                    selectedLayerIds = selectedIds
                    layerPreviews = previews
                }
                view.colorPickedListener = { picked -> color = picked }
                view.visiblePaletteListener = { drawingPalette = it }
                view.drawnColorListener = { drawn -> colorHistory = colorHistoryPreferences.record(drawn) }
                view.stylusButtonListener = ::performStylusButton
                view.selectionListener = { active, selected -> selectionMode = active; hasSelection = selected }
                if (library != null && documentId != null) {
                    if (loadExisting) view.loadProject(library, documentId) { outcome ->
                        ready = outcome.isSuccess
                        outcome.exceptionOrNull()?.let { message = it.message ?: "Drawing could not be opened." }
                    } else {
                        view.configureBlank(canvasWidthPx, canvasHeightPx)
                        ready = true
                    }
                } else view.publishLayers()
            } },
        )

        EditorChrome(
            canUndo = canUndo, canRedo = canRedo, debug = debug,
            zoomPercent = (diagnostics.zoom * 100).roundToInt(),
            onUndo = { surface?.undo() }, onRedo = { surface?.redo() },
            onReset = { surface?.resetView() }, onDebug = { debug = !debug },
            selectionActive = selectionMode || hasSelection,
            onSelection = { selectionMode = !selectionMode; movingSelection = false; imageTransforming = false; layersOpen = false; colorPickerOpen = false },
            layersOpen = layersOpen, onLayers = { colorPickerOpen = false; layersOpen = !layersOpen },
            showTopColorSwitcher = !portrait,
            color = color,
            frequentColors = drawingPalette,
            onColor = { color = it },
            onOpenColorPicker = { layersOpen = false; colorPickerOpen = true },
            onBack = if (onBackToGallery != null) leaveEditor else null,
            onExport = { if (ready) exportOpen = true },
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        )

        Box(
            Modifier.fillMaxSize().then(
                if (portrait) Modifier else Modifier.padding(top = 66.dp, bottom = 16.dp),
            ),
        ) {
            BrushRail(
                selected = brush, erasing = erasing,
                onBrush = {
                    brush = it; erasing = false; applyTuning(brushPreferences.load(it))
                },
                onEraser = {
                    toggleEraser()
                },
                onAdjust = { brushStudioOpen = true },
                modifier = Modifier.align(if (portrait) Alignment.BottomStart else Alignment.CenterStart)
                    .then(if (portrait) Modifier.navigationBarsPadding().padding(12.dp) else Modifier.padding(start = 20.dp)),
                horizontal = portrait,
                compact = compactLandscape,
            )

            TipControls(
                size = size, opacity = opacity, color = color, frequentColors = drawingPalette,
                onSize = { size = it }, onOpacity = { opacity = it }, onColor = { color = it },
                onOpenColorPicker = { layersOpen = false; colorPickerOpen = true },
                horizontal = portrait,
                compactVertical = compactLandscape,
                verticalTrackHeight = when {
                    viewportHeight < 440.dp -> 44.dp
                    compactLandscape -> 154.dp
                    else -> 205.dp
                },
                showColorSwitcher = portrait,
                modifier = Modifier.align(if (portrait) Alignment.BottomEnd else Alignment.CenterEnd)
                    .then(if (portrait) Modifier.navigationBarsPadding().padding(12.dp) else Modifier.padding(end = 20.dp)),
            )
        }

        if (selectionMode || hasSelection) SelectionBar(
            selecting = selectionMode,
            hasSelection = hasSelection,
            tool = selectionTool,
            moving = movingSelection,
            canMove = layers.any { it.id in selectedLayerIds && it.kind == LayerKind.RASTER },
            selectedLayerCount = selectedLayerIds.size,
            onTool = { tool -> selectionTool = tool; movingSelection = false; selectionMode = true; surface?.setSelectionTool(tool) },
            onMove = { movingSelection = !movingSelection; selectionMode = false },
            onSelectAll = { surface?.selectAll() },
            onClear = { movingSelection = false; surface?.clearSelection() },
            onDone = { selectionMode = false; movingSelection = false },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp),
        )

        if (layersOpen) {
            LayersPanel(
                layers = layers,
                previews = layerPreviews,
                selectedId = selectedLayerId,
                selectedIds = selectedLayerIds,
                imageTransforming = imageTransforming,
                onSelect = {
                    movingSelection = false
                    imageTransforming = false
                    surface?.selectLayer(it)
                },
                onToggleSelection = { surface?.toggleLayerSelection(it) },
                onToggleVisibility = { surface?.toggleLayerVisibility(it) },
                onOpacity = { surface?.setSelectedLayerOpacity(it) },
                onRename = { surface?.renameSelectedLayer(it) },
                onImageScale = { surface?.setSelectedImageScale(it) },
                onFitImage = { surface?.fitSelectedImage() },
                onOriginalImageSize = { surface?.originalSizeSelectedImage() },
                onImageTransforming = { imageTransforming = it },
                onAddPaint = { surface?.addPaintLayer() },
                onImportImage = { message = null; importImage.launch(arrayOf("image/*")) },
                onMoveForward = { surface?.moveSelectedLayer(true) },
                onMoveBackward = { surface?.moveSelectedLayer(false) },
                onDelete = { surface?.deleteSelectedLayer() },
                modifier = Modifier.align(if (portrait) Alignment.Center else Alignment.CenterEnd)
                    .padding(top = 66.dp, bottom = if (portrait) 106.dp else 16.dp, end = if (portrait) 0.dp else 116.dp),
            )
        }

        if (colorPickerOpen) {
            val dismissInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = dismissInteraction,
                    indication = null,
                ) { colorPickerOpen = false },
            )
            ColorPickerPanel(
                initialColor = color,
                drawingPalette = drawingPalette,
                paletteColorCount = paletteColorCount,
                colorHistory = colorHistory,
                onColorSelected = { selected -> color = selected },
                onPaletteColorCountChanged = { paletteColorCount = it.coerceIn(1, 8) },
                onClearHistory = {
                    colorHistoryPreferences.clear()
                    colorHistory = emptyList()
                },
                compact = portrait || maxHeight < 620.dp,
                modifier = Modifier.align(if (portrait) Alignment.Center else Alignment.CenterEnd)
                    .padding(end = if (portrait) 12.dp else 116.dp, top = 12.dp, bottom = 12.dp),
            )
        }

        message?.let { visibleMessage ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                action = { TextButton(onClick = { message = null }) { Text("Dismiss") } },
            ) { Text(visibleMessage) }
        }

        if (!ready || saving || exporting) {
            Surface(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 72.dp), color = Color(0xD9202125), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(if (!ready) "Opening drawing…" else if (exporting) "Exporting…" else "Saving…", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 9.dp))
                }
            }
        }

        if (debug) DebugOverlay(diagnostics, Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 22.dp, bottom = if (portrait) 116.dp else 16.dp))
    }


    if (brushStudioOpen) BrushStudioDialog(
        toolName = if (erasing) "Eraser" else brush.displayName,
        supportsHardness = erasing || brush.engine == BrushEngine.AIRBRUSH,
        hardness = if (erasing) eraserHardness else brushHardness,
        pressureSize = pressureSize,
        pressureOpacity = pressureOpacity,
        speedTaper = speedTaper,
        onHardness = { if (erasing) eraserHardness = it else brushHardness = it },
        onPressureSize = { pressureSize = it },
        onPressureOpacity = { pressureOpacity = it },
        onSpeedTaper = { speedTaper = it },
        onReset = {
            applyTuning(if (erasing) BrushTuning(32f, 1f, .35f, true, true, false)
                else BrushTuning(brush.baseSizePx, brush.opacity, brush.hardness, true, true, brush.speedTaper > 0f))
        },
        onDismiss = { brushStudioOpen = false },
    )

    if (exportOpen) ExportDrawingDialog(documentName, canvasWidthPx, canvasHeightPx, onDismiss = { exportOpen = false }) { request ->
        exportOpen = false
        pendingExport = request
        exportDestination.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.format.mimeType
            putExtra(Intent.EXTRA_TITLE, request.fileName)
        })
    }

}

@Composable
private fun EditorChrome(
    canUndo: Boolean,
    canRedo: Boolean,
    debug: Boolean,
    layersOpen: Boolean,
    selectionActive: Boolean,
    zoomPercent: Int,
    showTopColorSwitcher: Boolean,
    color: RgbaColor,
    frequentColors: List<RgbaColor>,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onDebug: () -> Unit,
    onSelection: () -> Unit,
    onLayers: () -> Unit,
    onColor: (RgbaColor) -> Unit,
    onOpenColorPicker: () -> Unit,
    onBack: (() -> Unit)?,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onBack != null) ChromeGroup { IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to gallery" }) { BackIcon() } }
            ChromeGroup {
                IconAction("Undo", enabled = canUndo, onClick = onUndo) { UndoIcon() }
                IconAction("Redo", enabled = canRedo, onClick = onRedo) { RedoIcon() }
                TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 13.dp)) { Text("Fit", fontSize = 13.sp) }
                Text("$zoomPercent%", color = Color(0xFFD8D9DC), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp))
                TextButton(onClick = onSelection, colors = ButtonDefaults.textButtonColors(contentColor = if (selectionActive) Color(0xFFED6A5A) else Color.White)) { Text("Select", fontSize = 13.sp) }
            }
        }
        ChromeGroup(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            IconButton(onClick = onLayers, modifier = Modifier.semantics { contentDescription = "Layers" }) { LayersIcon(if (layersOpen) Color(0xFFED6A5A) else Color.White) }
            if (showTopColorSwitcher) {
                ColorSwitcher(color, frequentColors, onColor, onOpenColorPicker, compact = true)
            }
            TextButton(onClick = onExport, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Export", fontSize = 13.sp) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Debug", color = Color(0xFFD8D9DC), fontSize = 11.sp)
                Switch(checked = debug, onCheckedChange = { onDebug() }, modifier = Modifier.scale(.68f).semantics { contentDescription = "Debug overlay" })
            }
        }
    }
}

@Composable private fun ChromeGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.background(Color(0xD9202125), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xB345474D), RoundedCornerShape(16.dp)).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable private fun BrushRail(selected: BrushPreset, erasing: Boolean, onBrush: (BrushPreset) -> Unit, onEraser: () -> Unit, onAdjust: () -> Unit, horizontal: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = RoundedCornerShape(22.dp)
    val content: @Composable RowScope.() -> Unit = {
        BrushPreset.builtIns.forEach { preset -> ToolButton(preset.displayName, selected.id == preset.id && !erasing, { onBrush(preset) }, icon = when (preset.engine) { BrushEngine.PENCIL -> ToolGlyph.PENCIL; BrushEngine.INK -> ToolGlyph.INK; BrushEngine.AIRBRUSH -> ToolGlyph.AIRBRUSH }, compact = compact) }
        ToolButton("Eraser", erasing, onEraser, ToolGlyph.ERASER, compact)
        TextButton(onClick = onAdjust, modifier = Modifier.semantics { contentDescription = "Adjust brush" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Adjust", fontSize = if (compact) 9.sp else 11.sp) }
    }
    if (horizontal) Row(modifier.background(Color(0xD9202125), shape).border(1.dp, Color(0xB345474D), shape).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
    else Column(modifier.background(Color(0xD9202125), shape).border(1.dp, Color(0xB345474D), shape).padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BrushPreset.builtIns.forEach { preset -> ToolButton(preset.displayName, selected.id == preset.id && !erasing, { onBrush(preset) }, icon = when (preset.engine) { BrushEngine.PENCIL -> ToolGlyph.PENCIL; BrushEngine.INK -> ToolGlyph.INK; BrushEngine.AIRBRUSH -> ToolGlyph.AIRBRUSH }, compact = compact) }
        ToolButton("Eraser", erasing, onEraser, ToolGlyph.ERASER, compact)
        TextButton(onClick = onAdjust, modifier = Modifier.semantics { contentDescription = "Adjust brush" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Adjust", fontSize = if (compact) 9.sp else 11.sp) }
    }
}

@Composable private fun SelectionBar(selecting: Boolean, hasSelection: Boolean, tool: SelectionTool, moving: Boolean, canMove: Boolean, selectedLayerCount: Int, onTool: (SelectionTool) -> Unit, onMove: () -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(.94f).widthIn(max = 620.dp), color = Color(0xED202125), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFF55575D))) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (moving) "Drag to move paint on $selectedLayerCount selected layer${if (selectedLayerCount == 1) "" else "s"}" else if (selecting) "Draw a selection" else "Selection active", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                TextButton(onClick = onDone) { Text("Done") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onTool(SelectionTool.LASSO) }, colors = ButtonDefaults.textButtonColors(contentColor = if (tool == SelectionTool.LASSO && selecting) Color(0xFFED6A5A) else Color.White)) { Text("Lasso") }
                TextButton(onClick = { onTool(SelectionTool.RECTANGLE) }, colors = ButtonDefaults.textButtonColors(contentColor = if (tool == SelectionTool.RECTANGLE && selecting) Color(0xFFED6A5A) else Color.White)) { Text("Rectangle") }
                TextButton(onClick = onMove, enabled = hasSelection && canMove, colors = ButtonDefaults.textButtonColors(contentColor = if (moving) Color(0xFFED6A5A) else Color.White)) { Text("Move") }
                TextButton(onClick = onSelectAll) { Text("All") }
                TextButton(onClick = onClear, enabled = hasSelection) { Text("Clear") }
            }
        }
    }
}

private enum class ToolGlyph { PENCIL, INK, AIRBRUSH, ERASER }

@Composable private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit, icon: ToolGlyph, compact: Boolean = false) {
    val bg by animateColorAsState(if (selected) Color(0xFFF4F4F2) else Color.Transparent, label = "tool")
    val fg = if (selected) Color(0xFF17181B) else Color(0xFFF1F1EF)
    Column(Modifier.size(width = if (compact) 58.dp else 68.dp, height = if (compact) 58.dp else 72.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable(onClick = onClick).semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.size(if (compact) 22.dp else 28.dp)) {
            when (icon) {
                ToolGlyph.PENCIL -> { rotate(-40f) { drawRoundRect(fg, Offset(size.width*.42f, 1f), androidx.compose.ui.geometry.Size(size.width*.2f, size.height*.82f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)); drawPath(Path().apply { moveTo(size.width*.42f, size.height*.82f); lineTo(size.width*.62f, size.height*.82f); lineTo(size.width*.52f, size.height); close() }, fg) } }
                ToolGlyph.INK -> { drawPath(Path().apply { moveTo(size.width*.18f,size.height*.82f); cubicTo(size.width*.25f,size.height*.35f,size.width*.7f,size.height*.2f,size.width*.82f,size.height*.08f); cubicTo(size.width*.74f,size.height*.5f,size.width*.55f,size.height*.9f,size.width*.18f,size.height*.82f); close() }, fg) }
                ToolGlyph.AIRBRUSH -> { for (i in 0..4) for (j in 0..4) drawCircle(fg.copy(alpha = .25f + .1f*j), 1.4.dp.toPx(), Offset(5.dp.toPx()+i*4.dp.toPx(), 5.dp.toPx()+j*4.dp.toPx())) }
                ToolGlyph.ERASER -> { rotate(-40f) { drawRoundRect(fg, Offset(size.width*.25f,size.height*.18f), androidx.compose.ui.geometry.Size(size.width*.5f,size.height*.65f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx())) } }
            }
        }
        Spacer(Modifier.height(if (compact) 3.dp else 5.dp)); Text(label, color = fg, fontSize = if (compact) 9.sp else 11.sp, maxLines = 1)
    }
}

@Composable private fun IconAction(label: String, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) { IconButton(onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) { icon() } }
@Composable private fun UndoIcon() = ArcArrow(false)
@Composable private fun RedoIcon() = ArcArrow(true)
@Composable private fun ArcArrow(mirror: Boolean) { Canvas(Modifier.size(23.dp).graphicsLayer { scaleX = if (mirror) -1f else 1f }) { val path = Path().apply { moveTo(size.width*.85f,size.height*.72f); cubicTo(size.width*.85f,size.height*.3f,size.width*.45f,size.height*.22f,size.width*.24f,size.height*.42f); moveTo(size.width*.24f,size.height*.42f); lineTo(size.width*.28f,size.height*.17f); moveTo(size.width*.24f,size.height*.42f); lineTo(size.width*.48f,size.height*.43f) }; drawPath(path, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) } }
@Composable private fun LayersIcon(color: Color) { Canvas(Modifier.size(23.dp)) { val stroke = Stroke(1.7.dp.toPx(), join = StrokeJoin.Round); val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()); drawRoundRect(color, Offset(2.dp.toPx(), 3.dp.toPx()), androidx.compose.ui.geometry.Size(17.dp.toPx(), 14.dp.toPx()), radius, style = stroke); drawRoundRect(color.copy(alpha = .7f), Offset(5.dp.toPx(), 7.dp.toPx()), androidx.compose.ui.geometry.Size(17.dp.toPx(), 14.dp.toPx()), radius, style = stroke) } }
@Composable private fun BackIcon() { Canvas(Modifier.size(22.dp)) { val width = 2.dp.toPx(); drawLine(Color.White, Offset(size.width * .78f, size.height * .5f), Offset(size.width * .22f, size.height * .5f), width, StrokeCap.Round); drawLine(Color.White, Offset(size.width * .22f, size.height * .5f), Offset(size.width * .46f, size.height * .24f), width, StrokeCap.Round); drawLine(Color.White, Offset(size.width * .22f, size.height * .5f), Offset(size.width * .46f, size.height * .76f), width, StrokeCap.Round) } }

@Composable private fun DebugOverlay(d: CanvasDiagnostics, modifier: Modifier = Modifier) { Text("${d.fps.roundToInt()} fps  •  ${d.tool.lowercase()}  •  p ${"%.2f".format(d.pressure)}  •  tilt ${"%.2f".format(d.tiltRadians)}\n${d.sampleRateHz.roundToInt()} Hz  •  ${d.allocatedTiles} tiles  •  ${d.dirtyTiles} dirty  •  ${d.undoBytes / 1024} KiB undo", modifier.background(Color(0xE617181B), RoundedCornerShape(10.dp)).padding(10.dp), color = Color(0xFFD8D9DC), fontSize = 11.sp, lineHeight = 16.sp) }
