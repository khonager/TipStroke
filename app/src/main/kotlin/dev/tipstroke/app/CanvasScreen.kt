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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import dev.tipstroke.core.drawing.PixelTool
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
    onGestureSettingsChange: ((GestureSettings) -> Unit)? = null,
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
    val smallCanvas = canvasWidthPx <= 128 && canvasHeightPx <= 128
    var pixelTool by remember { mutableStateOf<PixelTool?>(if (smallCanvas) PixelTool.PENCIL else null) }
    var pixelSize by remember { mutableFloatStateOf(1f) }
    var size by remember { mutableFloatStateOf(if (smallCanvas) 1f else initialBrushTuning.sizePx) }
    var opacity by remember { mutableFloatStateOf(if (smallCanvas) 1f else initialBrushTuning.opacity) }
    var color by remember { mutableStateOf(RgbaColor(.05f, .05f, .06f)) }
    var drawingPalette by remember { mutableStateOf<List<RgbaColor>>(emptyList()) }
    var paletteColorCount by remember { mutableIntStateOf(3) }
    var colorHistory by remember { mutableStateOf(colorHistoryPreferences.load()) }
    var colorPickerOpen by remember { mutableStateOf(initialColorPickerOpen) }
    var colorPickerModeName by rememberSaveable { mutableStateOf(ColorPickerMode.HSV_WHEEL.name) }
    var debug by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(CanvasDiagnostics()) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var layers by remember { mutableStateOf<List<LayerSummary>>(emptyList()) }
    var layerPreviews by remember { mutableStateOf<Map<LayerId, Bitmap>>(emptyMap()) }
    var selectedLayerId by remember { mutableStateOf<LayerId?>(null) }
    var selectedLayerIds by remember { mutableStateOf<Set<LayerId>>(emptySet()) }
    var layersOpen by remember { mutableStateOf(initialLayersOpen) }
    var animationOpen by remember { mutableStateOf(false) }
    var animationActivationOpen by remember { mutableStateOf(false) }
    var animationState by remember { mutableStateOf<AnimationUiState?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(library == null) }
    var saving by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var stillExportOpen by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportRequest?>(null) }
    var pendingAnimationExport by remember { mutableStateOf<AnimationExportRequest?>(null) }
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
    var pressureSizeStart by remember { mutableFloatStateOf(initialBrushTuning.pressureSizeStart) }
    var pressureSizeExponent by remember { mutableFloatStateOf(initialBrushTuning.pressureSizeExponent) }
    var pressureOpacityStart by remember { mutableFloatStateOf(initialBrushTuning.pressureOpacityStart) }
    var pressureOpacityExponent by remember { mutableFloatStateOf(initialBrushTuning.pressureOpacityExponent) }
    var speedTaperAmount by remember { mutableFloatStateOf(initialBrushTuning.speedTaperAmount) }
    var pencilPointSize by remember { mutableFloatStateOf(initialBrushTuning.pencilPointSize) }
    var pencilTiltSensitivity by remember { mutableFloatStateOf(initialBrushTuning.pencilTiltSensitivity) }
    var pencilShadeSize by remember { mutableFloatStateOf(initialBrushTuning.pencilShadeSize) }
    var pencilShadeOpacity by remember { mutableFloatStateOf(initialBrushTuning.pencilShadeOpacity) }
    var pencilGrain by remember { mutableFloatStateOf(initialBrushTuning.pencilGrain) }
    var pencilTiltMode by remember { mutableStateOf(initialBrushTuning.pencilTiltMode) }
    var pencilShadeStartRadians by remember { mutableFloatStateOf(initialBrushTuning.pencilShadeStartRadians) }
    var pencilShadeTransitionRadians by remember { mutableFloatStateOf(initialBrushTuning.pencilShadeTransitionRadians) }
    var phoneChromeManuallyHidden by rememberSaveable { mutableStateOf(false) }
    var phoneChromeOccludedByStroke by remember { mutableStateOf(false) }
    var phoneAdjustmentsOpen by rememberSaveable { mutableStateOf(false) }
    var phoneMenuOpen by remember { mutableStateOf(false) }
    var touchDrawing by rememberSaveable { mutableStateOf(gestureSettings.oneFingerDrag == FingerAction.DRAW) }
    val lastStylusButtonAt = remember { longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE) }
    val phoneChromeVisible = !phoneChromeManuallyHidden && !phoneChromeOccludedByStroke
    val effectiveGestureSettings = if (touchDrawing == (gestureSettings.oneFingerDrag == FingerAction.DRAW)) {
        gestureSettings
    } else {
        gestureSettings.copy(oneFingerDrag = if (touchDrawing) FingerAction.DRAW else FingerAction.NAVIGATE)
    }

    LaunchedEffect(gestureSettings.oneFingerDrag) {
        touchDrawing = gestureSettings.oneFingerDrag == FingerAction.DRAW
    }

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
        val animationRequest = pendingAnimationExport
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && animationRequest != null && uri != null) {
            exporting = true
            surface?.exportAnimation(uri, animationRequest.format, animationRequest.scale) { outcome ->
                exporting = false
                message = outcome.fold({ "Animation saved." }, { it.message ?: "Animation export failed." })
            }
        } else if (result.resultCode == Activity.RESULT_OK && request != null && uri != null) {
            exporting = true
            surface?.exportDrawing(uri, request.format, request.quality, request.scale, request.transparent) { outcome ->
                exporting = false
                message = outcome.fold({ "Export saved." }, { it.message ?: "Export failed." })
            }
        }
        pendingExport = null
        pendingAnimationExport = null
    }

    fun sync() {
        surface?.settings?.apply {
            this.brush = brush.copy(
                hardness = brushHardness,
                pressureToSize = if (pressureSize) PressureCurve(pressureSizeStart, 1f, pressureSizeExponent) else PressureCurve(1f, 1f, 1f),
                pressureToOpacity = if (pressureOpacity) PressureCurve(pressureOpacityStart, 1f, pressureOpacityExponent) else PressureCurve(1f, 1f, 1f),
                speedTaper = if (speedTaper) speedTaperAmount else 0f,
                pencilPointSize = pencilPointSize,
                pencilTiltSensitivity = pencilTiltSensitivity,
                pencilShadeSize = pencilShadeSize,
                pencilShadeOpacity = pencilShadeOpacity,
                pencilGrain = pencilGrain,
                pencilTiltMode = pencilTiltMode,
                pencilShadeStartRadians = pencilShadeStartRadians,
                pencilShadeTransitionRadians = pencilShadeTransitionRadians,
            )
            sizePx = size; this.opacity = opacity; this.color = color; this.erasing = erasing
            this.eraserHardness = eraserHardness; this.debug = debug; gestures = effectiveGestureSettings
        }
        surface?.setPixelTool(pixelTool)
    }
    fun applyTuning(tuning: BrushTuning) {
        size = tuning.sizePx; opacity = tuning.opacity; brushHardness = tuning.hardness
        pressureSize = tuning.pressureSize; pressureOpacity = tuning.pressureOpacity; speedTaper = tuning.speedTaper
        pressureSizeStart = tuning.pressureSizeStart; pressureSizeExponent = tuning.pressureSizeExponent
        pressureOpacityStart = tuning.pressureOpacityStart; pressureOpacityExponent = tuning.pressureOpacityExponent
        speedTaperAmount = tuning.speedTaperAmount
        pencilPointSize = tuning.pencilPointSize; pencilTiltSensitivity = tuning.pencilTiltSensitivity
        pencilShadeSize = tuning.pencilShadeSize; pencilShadeOpacity = tuning.pencilShadeOpacity
        pencilGrain = tuning.pencilGrain
        pencilTiltMode = tuning.pencilTiltMode
        pencilShadeStartRadians = tuning.pencilShadeStartRadians
        pencilShadeTransitionRadians = tuning.pencilShadeTransitionRadians
    }
    fun toggleEraser() {
        erasing = !erasing
        if (pixelTool == null) applyTuning(if (erasing) brushPreferences.loadEraser() else brushPreferences.load(brush))
        sync()
    }
    fun selectPixelTool(tool: PixelTool) {
        if (pixelTool == null) opacity = 1f
        pixelTool = tool
        erasing = false
        size = pixelSize
    }
    fun selectRegularBrush(preset: BrushPreset = brush) {
        pixelTool = null
        brush = preset
        erasing = false
        applyTuning(brushPreferences.load(preset))
    }
    val pixelToolsAvailable = smallCanvas || diagnostics.zoom >= 8f || pixelTool != null
    fun performStylusButton(button: StylusButton) {
        val now = android.os.SystemClock.uptimeMillis()
        val buttonIndex = button.ordinal
        if (lastStylusButtonAt[buttonIndex] != Long.MIN_VALUE && now - lastStylusButtonAt[buttonIndex] < 80L) return
        lastStylusButtonAt[buttonIndex] = now
        val action = when (button) {
            StylusButton.PRIMARY -> effectiveGestureSettings.stylusPrimaryButton
            StylusButton.SECONDARY -> effectiveGestureSettings.stylusSecondaryButton
        }
        when (action) {
            StylusButtonAction.TOGGLE_ERASER -> toggleEraser()
            StylusButtonAction.UNDO -> surface?.undo()
            StylusButtonAction.REDO -> surface?.redo()
            StylusButtonAction.DISABLED -> Unit
        }
    }
    LaunchedEffect(brush, erasing, pixelTool, size, opacity, color, brushHardness, eraserHardness, pressureSize, pressureOpacity, speedTaper, pressureSizeStart, pressureSizeExponent, pressureOpacityStart, pressureOpacityExponent, speedTaperAmount, pencilPointSize, pencilTiltSensitivity, pencilShadeSize, pencilShadeOpacity, pencilGrain, pencilTiltMode, pencilShadeStartRadians, pencilShadeTransitionRadians, debug, effectiveGestureSettings, surface) { sync() }
    LaunchedEffect(surface, paletteColorCount) { surface?.setPaletteColorCount(paletteColorCount) }
    LaunchedEffect(surface, debug) {
        while (debug && surface != null) {
            surface?.publishDiagnostics()
            delay(2_000)
        }
    }
    LaunchedEffect(imageTransforming, surface) { surface?.setImageTransformMode(imageTransforming) }
    LaunchedEffect(selectionMode, surface) { surface?.setSelectionMode(selectionMode) }
    LaunchedEffect(movingSelection, surface) { surface?.setSelectionMoveMode(movingSelection) }
    LaunchedEffect(brush.id, erasing, pixelTool, size, opacity, brushHardness, pressureSize, pressureOpacity, speedTaper, pressureSizeStart, pressureSizeExponent, pressureOpacityStart, pressureOpacityExponent, speedTaperAmount, pencilPointSize, pencilTiltSensitivity, pencilShadeSize, pencilShadeOpacity, pencilGrain, pencilTiltMode, pencilShadeStartRadians, pencilShadeTransitionRadians) {
        val tuning = BrushTuning(
            size, opacity, brushHardness, pressureSize, pressureOpacity, speedTaper,
            pencilPointSize, pencilTiltSensitivity, pencilShadeSize, pencilShadeOpacity, pencilGrain,
            pencilTiltMode, pencilShadeStartRadians, pencilShadeTransitionRadians,
            pressureSizeStart, pressureSizeExponent, pressureOpacityStart, pressureOpacityExponent,
            speedTaperAmount,
        )
        if (pixelTool == null) {
            if (erasing) brushPreferences.saveEraser(tuning) else brushPreferences.save(brush, tuning)
        }
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
    DisposableEffect(onStylusButtonHandlerChanged, effectiveGestureSettings, surface) {
        onStylusButtonHandlerChanged?.invoke(::performStylusButton)
        onDispose { onStylusButtonHandlerChanged?.invoke(null) }
    }
    BackHandler(enabled = onBackToGallery != null, onBack = leaveEditor)
    BackHandler(enabled = colorPickerOpen) { colorPickerOpen = false }
    BackHandler(enabled = layersOpen) { layersOpen = false }
    BackHandler(enabled = animationOpen) { animationOpen = false }

    fun openAnimation() {
        layersOpen = false
        colorPickerOpen = false
        if (animationState == null) animationActivationOpen = true else animationOpen = !animationOpen
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val density = LocalDensity.current
        val portrait = maxHeight > maxWidth
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val compactPhone = minOf(maxWidth, maxHeight) < 600.dp
        val compactLandscape = !portrait && maxHeight < 760.dp
        fun manuallyHidePhoneChrome() {
            if (!compactPhone) return
            phoneChromeManuallyHidden = true
            phoneChromeOccludedByStroke = false
            phoneAdjustmentsOpen = false
            phoneMenuOpen = false
            layersOpen = false
            colorPickerOpen = false
        }
        LaunchedEffect(compactPhone) {
            if (!compactPhone) phoneChromeOccludedByStroke = false
        }
        BackHandler(enabled = compactPhone && phoneChromeManuallyHidden) { phoneChromeManuallyHidden = false }
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
                view.animationListener = { animationState = it }
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
            update = { view ->
                view.chromeOcclusionListener = if (compactPhone) ({ occluded ->
                    phoneChromeOccludedByStroke = occluded
                }) else null
                view.setChromeOcclusionInsets(
                    topPx = with(density) { 72.dp.toPx() },
                    bottomPx = with(density) { (if (phoneAdjustmentsOpen) 196.dp else 72.dp).toPx() },
                    approachMarginPx = with(density) { 36.dp.toPx() },
                )
            },
        )

        if (!compactPhone) {
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
                animationOpen = animationOpen,
                onAnimation = ::openAnimation,
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
            )
        } else if (phoneChromeVisible) {
            PhoneEditorChrome(
                canUndo = canUndo,
                canRedo = canRedo,
                touchDrawing = touchDrawing,
                layersOpen = layersOpen,
                selectionActive = selectionMode || hasSelection,
                color = color,
                menuOpen = phoneMenuOpen,
                onMenuOpenChange = { phoneMenuOpen = it },
                onBack = if (onBackToGallery != null) leaveEditor else null,
                onUndo = { surface?.undo() },
                onRedo = { surface?.redo() },
                onTouchDrawing = { enabled ->
                    touchDrawing = enabled
                    onGestureSettingsChange?.invoke(
                        gestureSettings.copy(oneFingerDrag = if (enabled) FingerAction.DRAW else FingerAction.NAVIGATE),
                    )
                },
                onColorPicker = { layersOpen = false; colorPickerOpen = true },
                onLayers = { colorPickerOpen = false; layersOpen = !layersOpen },
                onReset = { surface?.resetView() },
                onSelection = { selectionMode = !selectionMode; movingSelection = false; imageTransforming = false; layersOpen = false; colorPickerOpen = false },
                onExport = { if (ready) exportOpen = true },
                onAnimation = ::openAnimation,
                onDebug = { debug = !debug },
                onHide = ::manuallyHidePhoneChrome,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp),
            )
        } else if (phoneChromeManuallyHidden) {
            PhoneChromeHandle(
                onClick = { phoneChromeManuallyHidden = false },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            )
        }

        if (!compactPhone) Box(
            Modifier.fillMaxSize().then(if (portrait) Modifier else Modifier.padding(top = 66.dp, bottom = 16.dp)),
        ) {
            BrushRail(
                selected = brush, erasing = erasing,
                pixelTool = pixelTool, pixelToolsAvailable = pixelToolsAvailable,
                pixelToolsFirst = smallCanvas,
                onPixelTool = ::selectPixelTool,
                onBrush = ::selectRegularBrush,
                onEraser = {
                    toggleEraser()
                },
                onAdjust = { brushStudioOpen = true },
                modifier = Modifier.align(if (portrait) Alignment.BottomStart else Alignment.CenterStart)
                    .then(if (portrait) Modifier.navigationBarsPadding().padding(12.dp) else Modifier.padding(start = 20.dp))
                    .then(if (portrait) Modifier.width((viewportWidth - 430.dp).coerceIn(170.dp, 440.dp))
                        else Modifier.heightIn(max = (viewportHeight - 110.dp).coerceAtLeast(220.dp))),
                horizontal = portrait,
                compact = compactLandscape,
            )

            TipControls(
                size = size, opacity = opacity, color = color, frequentColors = drawingPalette,
                sizeRange = if (pixelTool != null) 1f..32f else 1f..180f,
                onSize = {
                    size = it
                    if (pixelTool != null) pixelSize = it
                    surface?.settings?.sizePx = it
                    surface?.showBrushAdjustmentPreview()
                },
                onOpacity = {
                    opacity = it
                    surface?.settings?.opacity = it
                    surface?.showBrushAdjustmentPreview()
                },
                onColor = { color = it },
                onOpenColorPicker = { layersOpen = false; colorPickerOpen = true },
                onAdjustmentStart = {
                    sync()
                    surface?.showBrushAdjustmentPreview()
                },
                onAdjustmentEnd = { surface?.hideBrushAdjustmentPreview() },
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
        } else if (phoneChromeVisible) {
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (phoneAdjustmentsOpen) {
                    TipControls(
                        size = size,
                        sizeRange = if (pixelTool != null) 1f..32f else 1f..180f,
                        opacity = opacity,
                        color = color,
                        frequentColors = drawingPalette,
                        onSize = {
                            size = it
                            if (pixelTool != null) pixelSize = it
                            surface?.settings?.sizePx = it
                            surface?.showBrushAdjustmentPreview()
                        },
                        onOpacity = {
                            opacity = it
                            surface?.settings?.opacity = it
                            surface?.showBrushAdjustmentPreview()
                        },
                        onColor = { color = it },
                        onOpenColorPicker = { colorPickerOpen = true },
                        onAdjustmentStart = { sync(); surface?.showBrushAdjustmentPreview() },
                        onAdjustmentEnd = { surface?.hideBrushAdjustmentPreview() },
                        horizontal = true,
                        showColorSwitcher = false,
                        horizontalWidthFraction = 1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                PhoneBrushDock(
                    selected = brush,
                    erasing = erasing,
                    pixelTool = pixelTool,
                    pixelToolsAvailable = pixelToolsAvailable,
                    pixelToolsFirst = smallCanvas,
                    onPixelTool = ::selectPixelTool,
                    adjustmentsOpen = phoneAdjustmentsOpen,
                    onBrush = ::selectRegularBrush,
                    onEraser = ::toggleEraser,
                    onAdjustments = { phoneAdjustmentsOpen = !phoneAdjustmentsOpen },
                    onBrushStudio = { brushStudioOpen = true },
                )
            }
        }

        if (selectionMode || hasSelection) SelectionBar(
            selecting = selectionMode,
            hasSelection = hasSelection,
            tool = selectionTool,
            moving = movingSelection,
            canMove = selectedLayerIds.isNotEmpty(),
            canDuplicate = selectedLayerIds.any { selectedId -> layers.any { it.id == selectedId && it.kind == LayerKind.RASTER } },
            selectedLayerCount = selectedLayerIds.size,
            onTool = { tool -> selectionTool = tool; movingSelection = false; selectionMode = true; surface?.setSelectionTool(tool) },
            onMove = { movingSelection = !movingSelection; selectionMode = false },
            onSelectAll = { surface?.selectAll() },
            onDuplicate = { surface?.duplicateSelection() },
            onClear = { movingSelection = false; surface?.clearSelection() },
            onDone = { selectionMode = false; movingSelection = false },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = if (compactPhone) 66.dp else 72.dp),
        )

        if (layersOpen) {
            val dismissInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = dismissInteraction,
                    indication = null,
                ) { layersOpen = false },
            )
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
                onDuplicate = { imageTransforming = false; surface?.duplicateSelectedLayers() },
                onDelete = { surface?.deleteSelectedLayer() },
                modifier = Modifier.align(if (portrait) Alignment.Center else Alignment.CenterEnd)
                    .padding(
                        top = 66.dp,
                        bottom = if (portrait) (if (compactPhone) 76.dp else 106.dp) else 16.dp,
                        end = if (portrait) 0.dp else 116.dp,
                    ),
            )
        }

        if (animationOpen) animationState?.let { state ->
            AnimationPanel(
                state = state,
                layers = layers,
                selectedLayerId = selectedLayerId,
                hasSelection = hasSelection,
                onSelectFrame = { surface?.selectAnimationFrame(it) },
                onAddFrame = { surface?.addAnimationFrame() },
                onDuplicateFrame = { surface?.duplicateAnimationFrame() },
                onDeleteFrame = { surface?.deleteAnimationFrame() },
                onMoveFrame = { surface?.moveAnimationFrame(it) },
                onExposure = { surface?.setAnimationExposure(it) },
                onFps = { surface?.setAnimationFps(it) },
                onPlaybackMode = { surface?.setAnimationPlaybackMode(it) },
                onPlayPause = { surface?.toggleAnimationPlayback() },
                onOnion = { before, after, opacity -> surface?.setOnionSkin(before, after, opacity) },
                onBackground = { id, shared -> surface?.setAnimationBackground(id, shared) },
                onCopyCel = { surface?.copyCelToNewLayer(false) },
                onMoveCel = { surface?.copyCelToNewLayer(true) },
                onCopySelection = { surface?.duplicateSelection() },
                onMoveSelection = { surface?.moveSelectionToNewLayer() },
                onRecordMotion = { end, timing ->
                    if (surface?.armImageMotionRecording(end, timing) == true) imageTransforming = true
                    else message = "Select an image layer and at least two frames to record movement."
                },
                onRecordLine = { end, timing ->
                    if (surface?.armStrokeRevealRecording(end, timing) != true) {
                        message = "Select a paint layer, at least two frames, and turn off the eraser."
                    }
                },
                onCancelRecording = { surface?.cancelAnimationRecording() },
                onClose = { animationOpen = false },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(bottom = if (compactPhone) 75.dp else if (portrait) 95.dp else 12.dp),
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
                mode = ColorPickerMode.valueOf(colorPickerModeName),
                drawingPalette = drawingPalette,
                paletteColorCount = paletteColorCount,
                colorHistory = colorHistory,
                onColorSelected = { selected -> color = selected },
                onModeChanged = { colorPickerModeName = it.name },
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
        brush = brush,
        erasing = erasing,
        opacity = opacity,
        supportsHardness = erasing || brush.engine == BrushEngine.AIRBRUSH,
        hardness = if (erasing) eraserHardness else brushHardness,
        pressureSize = pressureSize,
        pressureOpacity = pressureOpacity,
        speedTaper = speedTaper,
        pressureSizeStart = pressureSizeStart,
        pressureSizeExponent = pressureSizeExponent,
        pressureOpacityStart = pressureOpacityStart,
        pressureOpacityExponent = pressureOpacityExponent,
        speedTaperAmount = speedTaperAmount,
        supportsPencilTilt = !erasing && brush.engine == BrushEngine.PENCIL,
        pencilPointSize = pencilPointSize,
        pencilTiltSensitivity = pencilTiltSensitivity,
        pencilShadeSize = pencilShadeSize,
        pencilShadeOpacity = pencilShadeOpacity,
        pencilGrain = pencilGrain,
        pencilTiltMode = pencilTiltMode,
        pencilShadeStartRadians = pencilShadeStartRadians,
        pencilShadeTransitionRadians = pencilShadeTransitionRadians,
        onHardness = { if (erasing) eraserHardness = it else brushHardness = it },
        onPressureSize = { pressureSize = it },
        onPressureOpacity = { pressureOpacity = it },
        onSpeedTaper = { speedTaper = it },
        onPressureSizeStart = { pressureSizeStart = it },
        onPressureSizeExponent = { pressureSizeExponent = it },
        onPressureOpacityStart = { pressureOpacityStart = it },
        onPressureOpacityExponent = { pressureOpacityExponent = it },
        onSpeedTaperAmount = { speedTaperAmount = it },
        onPencilPointSize = { pencilPointSize = it },
        onPencilTiltSensitivity = { pencilTiltSensitivity = it },
        onPencilShadeSize = { pencilShadeSize = it },
        onPencilShadeOpacity = { pencilShadeOpacity = it },
        onPencilGrain = { pencilGrain = it },
        onPencilTiltMode = { pencilTiltMode = it },
        onPencilShadeStartRadians = { pencilShadeStartRadians = it },
        onPencilShadeTransitionRadians = { pencilShadeTransitionRadians = it },
        onReset = {
            applyTuning(if (erasing) BrushTuning(32f, 1f, .35f, true, true, false)
                else BrushTuning(
                    brush.baseSizePx,
                    brush.opacity,
                    brush.hardness,
                    true,
                    true,
                    brush.speedTaper > 0f,
                    brush.pencilPointSize,
                    brush.pencilTiltSensitivity,
                    brush.pencilShadeSize,
                    brush.pencilShadeOpacity,
                    brush.pencilGrain,
                    brush.pencilTiltMode,
                    brush.pencilShadeStartRadians,
                    brush.pencilShadeTransitionRadians,
                    brush.pressureToSize.start,
                    brush.pressureToSize.exponent,
                    brush.pressureToOpacity.start,
                    brush.pressureToOpacity.exponent,
                    .55f,
                ))
        },
        onDismiss = { brushStudioOpen = false },
    )

    if (exportOpen && animationState != null) AnimationExportDialog(
        documentName, canvasWidthPx, canvasHeightPx,
        onDismiss = { exportOpen = false },
        onStillImage = { exportOpen = false; stillExportOpen = true },
    ) { request ->
        exportOpen = false
        pendingAnimationExport = request
        exportDestination.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.format.mimeType
            putExtra(Intent.EXTRA_TITLE, request.fileName)
        })
    }
    if (stillExportOpen || (exportOpen && animationState == null)) ExportDrawingDialog(documentName, canvasWidthPx, canvasHeightPx, onDismiss = {
        exportOpen = false; stillExportOpen = false
    }) { request ->
        exportOpen = false
        stillExportOpen = false
        pendingExport = request
        exportDestination.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.format.mimeType
            putExtra(Intent.EXTRA_TITLE, request.fileName)
        })
    }

    if (animationActivationOpen) AlertDialog(
        onDismissRequest = { animationActivationOpen = false },
        title = { Text("Start animation") },
        text = { Text("Where should the current artwork go? You can change a layer between shared background and animated cel later.") },
        confirmButton = { TextButton(onClick = {
            surface?.enableAnimation(false)
            animationActivationOpen = false
            animationOpen = true
        }) { Text("First frame") } },
        dismissButton = { TextButton(onClick = {
            surface?.enableAnimation(true)
            animationActivationOpen = false
            animationOpen = true
        }) { Text("Shared background") } },
    )

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
    animationOpen: Boolean,
    onAnimation: () -> Unit,
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
            TextButton(onClick = onAnimation, contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = if (animationOpen) Color(0xFFED6A5A) else Color.White)) {
                Text("Animate", fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Debug", color = Color(0xFFD8D9DC), fontSize = 11.sp)
                Switch(checked = debug, onCheckedChange = { onDebug() }, modifier = Modifier.scale(.68f).semantics { contentDescription = "Debug overlay" })
            }
        }
    }
}

@Composable
private fun PhoneEditorChrome(
    canUndo: Boolean,
    canRedo: Boolean,
    touchDrawing: Boolean,
    layersOpen: Boolean,
    selectionActive: Boolean,
    color: RgbaColor,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onTouchDrawing: (Boolean) -> Unit,
    onColorPicker: () -> Unit,
    onLayers: () -> Unit,
    onReset: () -> Unit,
    onSelection: () -> Unit,
    onExport: () -> Unit,
    onAnimation: () -> Unit,
    onDebug: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxWidth().widthIn(max = 520.dp),
        color = Color(0xE6202125),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xB345474D)),
    ) {
        Row(
            Modifier.height(48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(38.dp).semantics { contentDescription = "Back to gallery" }) { BackIcon() }
            }
            PhoneIconAction("Undo", canUndo, onUndo) { UndoIcon() }
            PhoneIconAction("Redo", canRedo, onRedo) { RedoIcon() }
            TextButton(
                onClick = { onTouchDrawing(!touchDrawing) },
                modifier = Modifier.size(width = 46.dp, height = 36.dp).semantics {
                    contentDescription = if (touchDrawing) "Touch draws; tap for touch navigation" else "Touch navigates; tap for touch drawing"
                },
                colors = ButtonDefaults.textButtonColors(contentColor = if (touchDrawing) Color(0xFFED6A5A) else Color.White),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (touchDrawing) "Draw" else "Pan", fontSize = 10.sp, maxLines = 1)
            }
            Box(
                Modifier.size(30.dp).clip(CircleShape)
                    .background(Color(color.red, color.green, color.blue, color.alpha))
                    .border(2.dp, Color.White, CircleShape)
                    .clickable(onClick = onColorPicker)
                    .semantics { contentDescription = "Open color picker" },
            )
            IconButton(onClick = onLayers, modifier = Modifier.size(38.dp).semantics { contentDescription = "Layers" }) {
                LayersIcon(if (layersOpen) Color(0xFFED6A5A) else Color.White)
            }
            Box {
                IconButton(
                    onClick = { onMenuOpenChange(true) },
                    modifier = Modifier.size(38.dp).semantics { contentDescription = "More canvas actions" },
                ) { MoreIcon() }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    DropdownMenuItem(text = { Text("Fit canvas") }, onClick = { onMenuOpenChange(false); onReset() })
                    DropdownMenuItem(
                        text = { Text(if (selectionActive) "Close selection" else "Select") },
                        onClick = { onMenuOpenChange(false); onSelection() },
                    )
                    DropdownMenuItem(text = { Text("Export") }, onClick = { onMenuOpenChange(false); onExport() })
                    DropdownMenuItem(text = { Text("Animate") }, onClick = { onMenuOpenChange(false); onAnimation() })
                    DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { onMenuOpenChange(false); onDebug() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Hide controls") }, onClick = onHide)
                }
            }
        }
    }
}

@Composable
private fun PhoneChromeHandle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.size(48.dp).clickable(onClick = onClick).semantics { contentDescription = "Show drawing controls" },
        color = Color(0xD9202125),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xB345474D)),
    ) {
        Box(contentAlignment = Alignment.Center) { MoreIcon() }
    }
}

@Composable
private fun PhoneBrushDock(
    selected: BrushPreset,
    erasing: Boolean,
    pixelTool: PixelTool?,
    pixelToolsAvailable: Boolean,
    pixelToolsFirst: Boolean,
    onPixelTool: (PixelTool) -> Unit,
    adjustmentsOpen: Boolean,
    onBrush: (BrushPreset) -> Unit,
    onEraser: () -> Unit,
    onAdjustments: () -> Unit,
    onBrushStudio: () -> Unit,
) {
    val listState = rememberLazyListState()
    val activeIndex = toolIndex(selected, erasing, pixelTool, pixelToolsAvailable, pixelToolsFirst)
    LaunchedEffect(activeIndex, pixelToolsAvailable, pixelToolsFirst) {
        listState.scrollToItem(toolScrollStart(activeIndex))
    }
    Surface(
        Modifier.fillMaxWidth().widthIn(max = 520.dp),
        color = Color(0xE6202125),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xB345474D)),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val toolWidth = (maxWidth - (if (pixelTool == null) 96.dp else 54.dp)).coerceAtLeast(88.dp)
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LazyRow(
                    modifier = Modifier.width(toolWidth),
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pixelToolsAvailable && pixelToolsFirst) {
                        items(PixelTool.entries, key = { it.name }) { tool ->
                            ToolButton(tool.displayName, pixelTool == tool && !erasing, { onPixelTool(tool) }, tool.glyph, compact = true, phone = true)
                        }
                        item(key = "pixel-divider") { Box(Modifier.width(1.dp).height(36.dp).background(Color(0xFF55575D))) }
                    }
                    items(BrushPreset.builtIns, key = { it.id.value }) { preset ->
                        ToolButton(
                            preset.displayName,
                            pixelTool == null && selected.id == preset.id && !erasing,
                            { onBrush(preset) },
                            preset.glyph,
                            compact = true,
                            phone = true,
                        )
                    }
                    if (pixelToolsAvailable && !pixelToolsFirst) {
                        item(key = "pixel-divider") { Box(Modifier.width(1.dp).height(36.dp).background(Color(0xFF55575D))) }
                        items(PixelTool.entries, key = { it.name }) { tool ->
                            ToolButton(tool.displayName, pixelTool == tool && !erasing, { onPixelTool(tool) }, tool.glyph, compact = true, phone = true)
                        }
                    }
                    item(key = "eraser") {
                        ToolButton("Eraser", erasing, onEraser, ToolGlyph.ERASER, compact = true, phone = true)
                    }
                }
                IconButton(
                    onClick = onAdjustments,
                    modifier = Modifier.size(42.dp).semantics { contentDescription = if (adjustmentsOpen) "Hide size and opacity" else "Show size and opacity" },
                ) { TuneIcon(if (adjustmentsOpen) Color(0xFFED6A5A) else Color.White) }
                if (pixelTool == null) TextButton(onClick = onBrushStudio, modifier = Modifier.size(width = 42.dp, height = 50.dp), contentPadding = PaddingValues(0.dp)) {
                    Text("Studio", fontSize = 8.sp)
                }
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

@Composable private fun BrushRail(
    selected: BrushPreset,
    erasing: Boolean,
    pixelTool: PixelTool?,
    pixelToolsAvailable: Boolean,
    pixelToolsFirst: Boolean,
    onPixelTool: (PixelTool) -> Unit,
    onBrush: (BrushPreset) -> Unit,
    onEraser: () -> Unit,
    onAdjust: () -> Unit,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(22.dp)
    val listState = rememberLazyListState()
    val activeIndex = toolIndex(selected, erasing, pixelTool, pixelToolsAvailable, pixelToolsFirst)
    LaunchedEffect(activeIndex, pixelToolsAvailable, pixelToolsFirst) {
        listState.scrollToItem(toolScrollStart(activeIndex))
    }
    val dockModifier = modifier.background(Color(0xD9202125), shape)
        .border(1.dp, Color(0xB345474D), shape).padding(5.dp)
    if (horizontal) LazyRow(
        modifier = dockModifier,
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pixelToolsAvailable && pixelToolsFirst) {
            items(PixelTool.entries, key = { it.name }) { tool ->
                ToolButton(tool.displayName, pixelTool == tool && !erasing, { onPixelTool(tool) }, tool.glyph, compact)
            }
            item(key = "pixel-divider") { Box(Modifier.width(1.dp).height(36.dp).background(Color(0xFF55575D))) }
        }
        items(BrushPreset.builtIns, key = { it.id.value }) { preset ->
            ToolButton(preset.displayName, pixelTool == null && selected.id == preset.id && !erasing, { onBrush(preset) }, preset.glyph, compact)
        }
        if (pixelToolsAvailable && !pixelToolsFirst) {
            item(key = "pixel-divider") { Box(Modifier.width(1.dp).height(36.dp).background(Color(0xFF55575D))) }
            items(PixelTool.entries, key = { it.name }) { tool ->
                ToolButton(tool.displayName, pixelTool == tool && !erasing, { onPixelTool(tool) }, tool.glyph, compact)
            }
        }
        item(key = "eraser") { ToolButton("Eraser", erasing, onEraser, ToolGlyph.ERASER, compact) }
        if (pixelTool == null) item(key = "adjust") {
            TextButton(onClick = onAdjust, modifier = Modifier.semantics { contentDescription = "Adjust brush" }) { Text("Adjust", fontSize = if (compact) 9.sp else 11.sp) }
        }
    } else LazyColumn(
        modifier = dockModifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (pixelToolsAvailable && pixelToolsFirst) {
            items(PixelTool.entries, key = { it.name }) { tool ->
                ToolButton(tool.displayName, pixelTool == tool && !erasing, { onPixelTool(tool) }, tool.glyph, compact)
            }
            item(key = "pixel-divider") { Box(Modifier.width(36.dp).height(1.dp).background(Color(0xFF55575D))) }
        }
        items(BrushPreset.builtIns, key = { it.id.value }) { preset ->
            ToolButton(preset.displayName, pixelTool == null && selected.id == preset.id && !erasing, { onBrush(preset) }, preset.glyph, compact)
        }
        if (pixelToolsAvailable && !pixelToolsFirst) {
            item(key = "pixel-divider") { Box(Modifier.width(36.dp).height(1.dp).background(Color(0xFF55575D))) }
            items(PixelTool.entries, key = { it.name }) { tool ->
                ToolButton(tool.displayName, pixelTool == tool && !erasing, { onPixelTool(tool) }, tool.glyph, compact)
            }
        }
        item(key = "eraser") { ToolButton("Eraser", erasing, onEraser, ToolGlyph.ERASER, compact) }
        if (pixelTool == null) item(key = "adjust") {
            TextButton(onClick = onAdjust, modifier = Modifier.semantics { contentDescription = "Adjust brush" }) { Text("Adjust", fontSize = if (compact) 9.sp else 11.sp) }
        }
    }
}

@Composable private fun SelectionBar(selecting: Boolean, hasSelection: Boolean, tool: SelectionTool, moving: Boolean, canMove: Boolean, canDuplicate: Boolean, selectedLayerCount: Int, onTool: (SelectionTool) -> Unit, onMove: () -> Unit, onSelectAll: () -> Unit, onDuplicate: () -> Unit, onClear: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(.94f).widthIn(max = 620.dp), color = Color(0xED202125), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFF55575D))) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (moving) "Drag to move content on $selectedLayerCount selected layer${if (selectedLayerCount == 1) "" else "s"}" else if (selecting) "Draw a selection" else "Selection active", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                TextButton(onClick = onDone) { Text("Done") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onTool(SelectionTool.LASSO) }, colors = ButtonDefaults.textButtonColors(contentColor = if (tool == SelectionTool.LASSO && selecting) Color(0xFFED6A5A) else Color.White)) { Text("Lasso") }
                TextButton(onClick = { onTool(SelectionTool.RECTANGLE) }, colors = ButtonDefaults.textButtonColors(contentColor = if (tool == SelectionTool.RECTANGLE && selecting) Color(0xFFED6A5A) else Color.White)) { Text("Rectangle") }
                TextButton(onClick = onMove, enabled = hasSelection && canMove, colors = ButtonDefaults.textButtonColors(contentColor = if (moving) Color(0xFFED6A5A) else Color.White)) { Text("Move") }
                TextButton(onClick = onDuplicate, enabled = hasSelection && canDuplicate) { Text("Duplicate") }
                TextButton(onClick = onSelectAll) { Text("All") }
                TextButton(onClick = onClear, enabled = hasSelection) { Text("Clear") }
            }
        }
    }
}

private val PixelTool.displayName: String get() = when (this) {
    PixelTool.PENCIL -> "Pixel"
    PixelTool.LINE -> "Line"
    PixelTool.DITHER -> "Shade"
}

private val PixelTool.glyph: ToolGlyph get() = when (this) {
    PixelTool.PENCIL -> ToolGlyph.PIXEL
    PixelTool.LINE -> ToolGlyph.LINE
    PixelTool.DITHER -> ToolGlyph.DITHER
}

private val BrushPreset.glyph: ToolGlyph get() = when (engine) {
    BrushEngine.PENCIL -> ToolGlyph.PENCIL
    BrushEngine.INK -> ToolGlyph.INK
    BrushEngine.AIRBRUSH -> ToolGlyph.AIRBRUSH
}

private fun toolIndex(
    brush: BrushPreset,
    erasing: Boolean,
    pixelTool: PixelTool?,
    pixelToolsAvailable: Boolean,
    pixelToolsFirst: Boolean,
): Int {
    val regularIndex = BrushPreset.builtIns.indexOfFirst { it.id == brush.id }.coerceAtLeast(0)
    if (!pixelToolsAvailable) return if (erasing) 3 else regularIndex
    return when {
        erasing -> 7
        pixelTool != null -> (if (pixelToolsFirst) 0 else 4) + pixelTool.ordinal
        else -> (if (pixelToolsFirst) 4 else 0) + regularIndex
    }
}

private fun toolScrollStart(activeIndex: Int): Int =
    if (activeIndex == 4) 2 else (activeIndex - 1).coerceAtLeast(0)

private enum class ToolGlyph { PENCIL, INK, AIRBRUSH, ERASER, PIXEL, LINE, DITHER }

@Composable private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit, icon: ToolGlyph, compact: Boolean = false, phone: Boolean = false) {
    val bg by animateColorAsState(if (selected) Color(0xFFF4F4F2) else Color.Transparent, label = "tool")
    val fg = if (selected) Color(0xFF17181B) else Color(0xFFF1F1EF)
    val buttonWidth = if (phone) 42.dp else if (compact) 58.dp else 68.dp
    val buttonHeight = if (phone) 52.dp else if (compact) 58.dp else 72.dp
    val iconSize = if (phone) 19.dp else if (compact) 22.dp else 28.dp
    Column(Modifier.size(width = buttonWidth, height = buttonHeight).clip(RoundedCornerShape(if (phone) 13.dp else 16.dp)).background(bg).clickable(onClick = onClick).semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.size(iconSize)) {
            when (icon) {
                ToolGlyph.PENCIL -> { rotate(-40f) { drawRoundRect(fg, Offset(size.width*.42f, 1f), androidx.compose.ui.geometry.Size(size.width*.2f, size.height*.82f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)); drawPath(Path().apply { moveTo(size.width*.42f, size.height*.82f); lineTo(size.width*.62f, size.height*.82f); lineTo(size.width*.52f, size.height); close() }, fg) } }
                ToolGlyph.INK -> { drawPath(Path().apply { moveTo(size.width*.18f,size.height*.82f); cubicTo(size.width*.25f,size.height*.35f,size.width*.7f,size.height*.2f,size.width*.82f,size.height*.08f); cubicTo(size.width*.74f,size.height*.5f,size.width*.55f,size.height*.9f,size.width*.18f,size.height*.82f); close() }, fg) }
                ToolGlyph.AIRBRUSH -> { for (i in 0..4) for (j in 0..4) drawCircle(fg.copy(alpha = .25f + .1f*j), 1.4.dp.toPx(), Offset(5.dp.toPx()+i*4.dp.toPx(), 5.dp.toPx()+j*4.dp.toPx())) }
                ToolGlyph.ERASER -> { rotate(-40f) { drawRoundRect(fg, Offset(size.width*.25f,size.height*.18f), androidx.compose.ui.geometry.Size(size.width*.5f,size.height*.65f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx())) } }
                ToolGlyph.PIXEL -> { drawRect(fg, Offset(size.width*.18f, size.height*.58f), androidx.compose.ui.geometry.Size(size.width*.24f, size.height*.24f)); drawRect(fg, Offset(size.width*.42f, size.height*.34f), androidx.compose.ui.geometry.Size(size.width*.24f, size.height*.24f)); drawRect(fg, Offset(size.width*.66f, size.height*.1f), androidx.compose.ui.geometry.Size(size.width*.24f, size.height*.24f)) }
                ToolGlyph.LINE -> drawLine(fg, Offset(size.width*.16f, size.height*.82f), Offset(size.width*.84f, size.height*.16f), 3.dp.toPx())
                ToolGlyph.DITHER -> { for (x in 0..3) for (y in 0..3) if ((x + y) % 2 == 0) drawRect(fg, Offset(x * size.width / 4f, y * size.height / 4f), androidx.compose.ui.geometry.Size(size.width / 4f, size.height / 4f)) }
            }
        }
        Spacer(Modifier.height(if (phone) 2.dp else if (compact) 3.dp else 5.dp)); Text(label, color = fg, fontSize = if (phone) 8.sp else if (compact) 9.sp else 11.sp, maxLines = 1)
    }
}

@Composable private fun IconAction(label: String, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) { IconButton(onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) { icon() } }
@Composable private fun PhoneIconAction(label: String, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) { IconButton(onClick, enabled = enabled, modifier = Modifier.size(38.dp).semantics { contentDescription = label }) { icon() } }
@Composable private fun UndoIcon() = ArcArrow(false)
@Composable private fun RedoIcon() = ArcArrow(true)
@Composable private fun ArcArrow(mirror: Boolean) { Canvas(Modifier.size(23.dp).graphicsLayer { scaleX = if (mirror) -1f else 1f }) { val path = Path().apply { moveTo(size.width*.85f,size.height*.72f); cubicTo(size.width*.85f,size.height*.3f,size.width*.45f,size.height*.22f,size.width*.24f,size.height*.42f); moveTo(size.width*.24f,size.height*.42f); lineTo(size.width*.28f,size.height*.17f); moveTo(size.width*.24f,size.height*.42f); lineTo(size.width*.48f,size.height*.43f) }; drawPath(path, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) } }
@Composable private fun LayersIcon(color: Color) { Canvas(Modifier.size(23.dp)) { val stroke = Stroke(1.7.dp.toPx(), join = StrokeJoin.Round); val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()); drawRoundRect(color, Offset(2.dp.toPx(), 3.dp.toPx()), androidx.compose.ui.geometry.Size(17.dp.toPx(), 14.dp.toPx()), radius, style = stroke); drawRoundRect(color.copy(alpha = .7f), Offset(5.dp.toPx(), 7.dp.toPx()), androidx.compose.ui.geometry.Size(17.dp.toPx(), 14.dp.toPx()), radius, style = stroke) } }
@Composable private fun BackIcon() { Canvas(Modifier.size(22.dp)) { val width = 2.dp.toPx(); drawLine(Color.White, Offset(size.width * .78f, size.height * .5f), Offset(size.width * .22f, size.height * .5f), width, StrokeCap.Round); drawLine(Color.White, Offset(size.width * .22f, size.height * .5f), Offset(size.width * .46f, size.height * .24f), width, StrokeCap.Round); drawLine(Color.White, Offset(size.width * .22f, size.height * .5f), Offset(size.width * .46f, size.height * .76f), width, StrokeCap.Round) } }
@Composable private fun MoreIcon() { Canvas(Modifier.size(22.dp)) { repeat(3) { index -> drawCircle(Color.White, 1.8.dp.toPx(), Offset(size.width * (.28f + index * .22f), size.height / 2f)) } } }
@Composable private fun TuneIcon(color: Color) { Canvas(Modifier.size(23.dp)) { val width = 1.8.dp.toPx(); val xs = listOf(.28f, .5f, .72f); val knobs = listOf(.35f, .68f, .45f); xs.forEachIndexed { index, x -> drawLine(color.copy(alpha = .8f), Offset(size.width*x, size.height*.18f), Offset(size.width*x, size.height*.82f), width, StrokeCap.Round); drawCircle(color, 3.dp.toPx(), Offset(size.width*x, size.height*knobs[index])) } } }

@Composable private fun DebugOverlay(d: CanvasDiagnostics, modifier: Modifier = Modifier) {
    val accent = when (d.memoryPressure) {
        MemoryPressure.NORMAL -> Color(0xFF91C7A3)
        MemoryPressure.ELEVATED -> Color(0xFFFFC66D)
        MemoryPressure.CRITICAL -> Color(0xFFFF7D72)
    }
    val memory = if (d.processBudgetBytes > 0L) {
        "mem ${formatBytes(d.processBytes)} / ${formatBytes(d.processBudgetBytes)}  •  doc ${formatBytes(d.documentBytes)}\n" +
            "~${d.fullLayersRemaining} full layers left  •  ${formatBytes(d.fullLayerBytes)} each  •  device ${formatBytes(d.deviceAvailableBytes)} available"
    } else "memory sampling…"
    Column(modifier.background(Color(0xE617181B), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text("${d.fps.roundToInt()} fps  •  ${d.tool.lowercase()}  •  p ${"%.2f".format(d.pressure)}  •  tilt ${"%.2f".format(d.tiltRadians)}", color = Color(0xFFD8D9DC), fontSize = 11.sp, lineHeight = 16.sp)
        Text("${d.sampleRateHz.roundToInt()} Hz  •  ${d.allocatedTiles} tiles  •  ${d.dirtyTiles} dirty  •  ${formatBytes(d.undoBytes)} undo", color = Color(0xFFD8D9DC), fontSize = 11.sp, lineHeight = 16.sp)
        Text(memory, color = accent, fontSize = 11.sp, lineHeight = 16.sp)
        Text("Advisory: sparse layers vary with painted area and undo history", color = Color(0xFF92959B), fontSize = 9.sp, lineHeight = 13.sp)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MiB".format(bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KiB"
}
