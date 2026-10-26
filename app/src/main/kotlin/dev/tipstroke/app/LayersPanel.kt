package dev.tipstroke.app

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import dev.tipstroke.core.model.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun LayersPanel(
    layers: List<LayerSummary>,
    previews: Map<LayerId, Bitmap>,
    selectedId: LayerId?,
    selectedIds: Set<LayerId>,
    referenceLayerId: LayerId? = null,
    onReferenceStrokes: (LayerId?) -> Unit = {},
    onClipLayer: (LayerId?) -> Unit = {},
    backgroundColor: Int = android.graphics.Color.WHITE,
    backgroundOpacity: Float = 1f,
    onBackgroundColor: (Int) -> Unit = {},
    onBackgroundOpacity: (Float) -> Unit = {},
    onBackgroundOpacityFinished: () -> Unit,
    imageTransforming: Boolean,
    onSelect: (LayerId) -> Unit,
    onToggleSelection: (LayerId) -> Unit,
    onToggleVisibility: (LayerId) -> Unit,
    onOpacity: (Float) -> Unit,
    onOpacityFinished: () -> Unit,
    onRename: (String) -> Unit,
    onImageScale: (Float) -> Unit,
    onFitImage: () -> Unit,
    onOriginalImageSize: () -> Unit,
    onImageTransforming: (Boolean) -> Unit,
    onAddPaint: () -> Unit,
    onImportImage: () -> Unit,
    onMoveForward: () -> Unit,
    onMoveBackward: () -> Unit,
    onReorder: (LayerId, LayerId, Boolean) -> Unit = { _, _, _ -> },
    onGroup: (LayerId, LayerId) -> Unit = { _, _ -> },
    onUngroup: (String) -> Unit = {},
    onSelectGroup: (String) -> Unit = {},
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val latestBackgroundOpacityFinished by rememberUpdatedState(onBackgroundOpacityFinished)
    val latestOpacityFinished by rememberUpdatedState(onOpacityFinished)
    DisposableEffect(Unit) {
        onDispose {
            latestBackgroundOpacityFinished()
            latestOpacityFinished()
        }
    }
    var settingsLayerId by remember { mutableStateOf<LayerId?>(null) }
    var renameLayer by remember { mutableStateOf<LayerSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var backgroundPickerOpen by remember { mutableStateOf(false) }
    var backgroundPickerMode by remember { mutableStateOf(ColorPickerMode.HSV_WHEEL) }
    val listState = rememberLazyListState()
    val rowBounds = remember { mutableStateMapOf<LayerId, Rect>() }
    val itemBounds = remember { mutableStateMapOf<LayerId, Rect>() }
    val previewCoordinates = remember { mutableStateMapOf<LayerId, LayoutCoordinates>() }
    fun previewBounds() = previewCoordinates.filterValues { it.isAttached }.mapValues { it.value.boundsInRoot() }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    var draggedId by remember { mutableStateOf<LayerId?>(null) }
    var dragPoint by remember { mutableStateOf(Offset.Unspecified) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    var draggedRowSize by remember { mutableStateOf(Size.Zero) }
    var draggedItemHeight by remember { mutableFloatStateOf(0f) }
    var draggedItemTopInset by remember { mutableFloatStateOf(0f) }
    var committedReorder by remember { mutableStateOf(false) }
    var listCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestReorder by rememberUpdatedState(onReorder)
    val latestGroup by rememberUpdatedState(onGroup)
    val drop by remember(layers) {
        derivedStateOf {
            resolveLayerDrop(layers, draggedId, dragPoint, listBounds, rowBounds, previewBounds(),
                grabOffset.y + draggedItemTopInset, itemBounds, draggedItemHeight)
        }
    }
    val targetId = drop?.targetId
    val groupDrop = drop?.group == true
    val sourceIndex by remember(layers) { derivedStateOf { layers.indexOfFirst { it.id == draggedId } } }
    var previewDestination by remember { mutableIntStateOf(-1) }
    LaunchedEffect(drop?.destinationIndex, groupDrop, draggedId) {
        // Keep the insertion layout fixed while hovering a visible thumbnail so
        // the group target cannot move away and oscillate beneath the pointer.
        if (!groupDrop) previewDestination = drop?.destinationIndex ?: sourceIndex
    }
    val activeDestination = if (groupDrop) previewDestination else drop?.destinationIndex ?: sourceIndex
    val rowStride = (draggedId?.let(itemBounds::get)?.height ?: draggedRowSize.height) +
        with(LocalDensity.current) { 7.dp.toPx() }
    fun rowShift(index: Int): Float {
        val destination = activeDestination
        if (destination < 0 || sourceIndex < 0) return 0f
        return when {
            sourceIndex < destination && index in (sourceIndex + 1)..destination -> -rowStride
            destination < sourceIndex && index in destination until sourceIndex -> rowStride
            else -> 0f
        }
    }
    val scrollEdge = with(LocalDensity.current) { 40.dp.toPx() }
    LaunchedEffect(draggedId) {
        while (draggedId != null) {
            if (listBounds != Rect.Zero && dragPoint != Offset.Unspecified) {
                when {
                    dragPoint.y < listBounds.top + scrollEdge -> listState.scrollBy(-12f)
                    dragPoint.y > listBounds.bottom - scrollEdge -> listState.scrollBy(12f)
                }
            }
            delay(16)
        }
    }

    fun finishDrag() {
        val source = draggedId
        // Use the same derived target for the gap, line, and release. A separate
        // hit test here could pick a different position as preview bounds move.
        val finalDrop = drop
        if (source != null && finalDrop != null) {
            if (finalDrop.group) latestGroup(source, finalDrop.targetId)
            else {
                // The preview has already put the intervening rows where the new
                // list layout will put them. Do not animate that offset back to zero.
                committedReorder = true
                latestReorder(source, finalDrop.targetId, finalDrop.towardFront)
            }
        }
        draggedId = null
        dragPoint = Offset.Unspecified
        grabOffset = Offset.Zero
    }
    val latestFinishDrag by rememberUpdatedState(::finishDrag)
    Surface(
        modifier.widthIn(max = 420.dp).fillMaxWidth().fillMaxHeight().semantics { contentDescription = "Layers panel" },
        color = Color(0xE6202125),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF47494F)),
        shadowElevation = 18.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", color = Color(0xFFF5F5F2), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onImportImage) { Text("Import") }
                TextButton(onClick = onAddPaint) { Text("＋ Layer") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Text("Hold to reorder · drop on a preview to group", color = Color(0xFF9B9DA3), fontSize = 12.sp)
            Text("Swipe right to select more layers", color = Color(0xFF9B9DA3), fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).onGloballyPositioned {
                listCoordinates = it
                listBounds = it.boundsInRoot()
            }.drawWithContent {
                drawContent()
                val visibleDrop = drop
                val lineY = if (visibleDrop != null && !visibleDrop.group && visibleDrop.destinationIndex != sourceIndex)
                    insertionLineY(layers, sourceIndex, visibleDrop.destinationIndex, itemBounds, draggedItemHeight) else null
                if (lineY != null) {
                    val localY = lineY - listBounds.top
                    if (localY in 0f..size.height) {
                        drawLine(Color(0xFF75A7FF), Offset(0f, localY), Offset(size.width, localY), 2.dp.toPx())
                    }
                }
            }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    val point = listCoordinates?.takeIf { it.isAttached }?.localToRoot(held.position)
                        ?: return@awaitEachGesture
                    val source = rowBounds.entries.firstOrNull { it.value.contains(point) }
                        ?: return@awaitEachGesture
                    committedReorder = false
                    draggedId = source.key
                    draggedRowSize = source.value.size
                    val sourceItem = itemBounds[source.key]
                    draggedItemHeight = sourceItem?.height ?: source.value.height
                    draggedItemTopInset = source.value.top - (sourceItem?.top ?: source.value.top)
                    grabOffset = point - source.value.topLeft
                    dragPoint = point
                    try {
                        while (true) {
                            // Once held, own the drag before the child list can scroll it.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == held.id } ?: break
                            if (change.isConsumed) break
                            if (!change.pressed) {
                                if (event.changes.none { it.pressed }) latestFinishDrag()
                                change.consume()
                                break
                            }
                            change.consume()
                            dragPoint = listCoordinates?.takeIf { it.isAttached }?.localToRoot(change.position) ?: dragPoint
                        }
                    } finally {
                        draggedId = null
                        dragPoint = Offset.Unspecified
                        grabOffset = Offset.Zero
                    }
                }

            }) {
            LazyColumn(Modifier.fillMaxSize(), state = listState,
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(layers, key = { it.id.value }) { layer ->
                    DisposableEffect(layer.id) {
                        onDispose { rowBounds.remove(layer.id); itemBounds.remove(layer.id); previewCoordinates.remove(layer.id) }
                    }
                    Column(Modifier.onGloballyPositioned { itemBounds[layer.id] = it.boundsInRoot() }) {
                        if (layer.groupId != null && layers.getOrNull(layers.indexOf(layer) - 1)?.groupId != layer.groupId) {
                            Row(Modifier.fillMaxWidth().padding(top = 5.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${if (layers.filter { it.groupId == layer.groupId }.all { it.id in selectedIds }) "✓ " else ""}Group · ${layers.count { it.groupId == layer.groupId }} layers", color = Color(0xFFB8C9E5), fontSize = 11.sp,
                                    modifier = Modifier.weight(1f).clickable { onSelectGroup(layer.groupId!!) }
                                        .padding(vertical = 8.dp).semantics { contentDescription = "Select group" })
                                TextButton(onClick = { onUngroup(layer.groupId!!) }, modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Ungroup", fontSize = 10.sp) }
                            }
                        }
                        val animatedShift by animateFloatAsState(rowShift(layers.indexOf(layer)), label = "layer insertion")
                        val shift = if (committedReorder) 0f else animatedShift
                        Box(Modifier.padding(start = if (layer.groupId != null) 12.dp else 0.dp)
                            .onGloballyPositioned { rowBounds[layer.id] = it.boundsInRoot() }) {
                        LayerRow(
                            layer = layer,
                            preview = previews[layer.id],
                            primary = layer.id == selectedId,
                            selected = layer.id in selectedIds,
                            onSelect = { onSelect(layer.id) },
                            onToggleSelection = { onToggleSelection(layer.id) },
                            onVisibility = { onToggleVisibility(layer.id) },
                            onSettings = { onSelect(layer.id); settingsLayerId = layer.id },
                            modifier = Modifier.graphicsLayer {
                                alpha = if (draggedId == layer.id) 0f else 1f
                                translationY = shift
                            },
                            onPreviewBounds = { previewCoordinates[layer.id] = it },
                            groupTarget = targetId == layer.id && groupDrop,
                        )
                        }
                    }
                }
                item(key = "canvas-background") {
                    TextButton(onClick = { backgroundPickerOpen = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Box(Modifier.size(24.dp).background(Color(backgroundColor), RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(12.dp))
                        Text("Canvas background")
                    }
                }
            }
            // The floating row lives outside the scrolling/rearranging items. Pointer
            // coordinates come from this stationary Box, so translation cannot feed back.
            layers.firstOrNull { it.id == draggedId }?.let { layer ->
                // Capture a finite position for this composition. A pending placement
                // must not read cleared drag state after release/cancellation.
                val floatingOffset = layerDragOffset(dragPoint, listBounds.topLeft, grabOffset)
                LayerRow(layer, previews[layer.id], primary = layer.id == selectedId,
                    selected = layer.id in selectedIds,
                    onSelect = {}, onToggleSelection = {}, onVisibility = {},
                    modifier = Modifier.width(with(LocalDensity.current) { draggedRowSize.width.toDp() })
                        .offset { floatingOffset }.graphicsLayer { alpha = .88f; shadowElevation = 12.dp.toPx() })
            }
            }

        }
    }
    layers.firstOrNull { it.id == settingsLayerId }?.let { layer ->
        AlertDialog(
            onDismissRequest = { onOpacityFinished(); settingsLayerId = null },
            title = { Text("Layer settings") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LayerControls(
                        layer = layer,
                        imageTransforming = imageTransforming,
                        onRename = { renameLayer = layer; renameText = layer.name },
                        onOpacity = onOpacity,
                        onOpacityFinished = onOpacityFinished,
                        onImageScale = onImageScale,
                        onFitImage = onFitImage,
                        onOriginalImageSize = onOriginalImageSize,
                        onImageTransforming = {
                            onImageTransforming(it)
                            if (it) settingsLayerId = null
                        },
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelButton("Back", onMoveBackward)
                        PanelButton("Front", onMoveForward)
                        PanelButton("Duplicate", { onDuplicate(); settingsLayerId = null })
                    }
                    var clipMenuOpen by remember(layer.id) { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { clipMenuOpen = true }) {
                            Text(layer.clipSourceId?.let { id -> "Clipped to ${layers.firstOrNull { it.id == id }?.name ?: "layer"} ▾" } ?: "Clip to layer ▾")
                        }
                        DropdownMenu(clipMenuOpen, { clipMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("None — show all pixels") }, onClick = { onClipLayer(null); clipMenuOpen = false })
                            layers.filter { candidate ->
                                val visited = mutableSetOf(layer.id)
                                var cursor: LayerSummary? = candidate
                                var allowed = true
                                while (cursor != null) {
                                    if (!visited.add(cursor.id)) { allowed = false; break }
                                    val parent = cursor.clipSourceId
                                    cursor = layers.firstOrNull { it.id == parent }
                                }
                                allowed
                            }.forEach { source ->
                                DropdownMenuItem(text = { Text(source.name) }, onClick = { onClipLayer(source.id); clipMenuOpen = false })
                            }
                        }
                    }
                    if (layer.kind == LayerKind.RASTER) {
                        TextButton(onClick = {
                            onReferenceStrokes(if (referenceLayerId == layer.id) null else layer.id)
                            settingsLayerId = null
                        }) { Text(if (referenceLayerId == layer.id) "Turn off Reference strokes" else "Use as Reference strokes") }
                    }
                    TextButton(onClick = { onDelete(); settingsLayerId = null }, enabled = layers.size > 1) { Text("Delete layer") }

                }
            },
            confirmButton = {
                TextButton(onClick = { onOpacityFinished(); settingsLayerId = null }) { Text("Done") }
            },
        )
    }
    renameLayer?.let {
        AlertDialog(
            onDismissRequest = { renameLayer = null },
            title = { Text("Rename layer") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(80) },
                    label = { Text("Layer name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = { onRename(renameText); renameLayer = null },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameLayer = null }) { Text("Cancel") } },
        )
    }
    if (backgroundPickerOpen) {
        val closeBackgroundPicker = {
            onBackgroundOpacityFinished()
            backgroundPickerOpen = false
        }
        Dialog(onDismissRequest = closeBackgroundPicker) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ColorPickerPanel(
                    initialColor = RgbaColor(
                        ((backgroundColor shr 16) and 0xFF) / 255f,
                        ((backgroundColor shr 8) and 0xFF) / 255f,
                        (backgroundColor and 0xFF) / 255f,
                    ),
                    mode = backgroundPickerMode,
                    drawingPalette = emptyList(), paletteColorCount = 0,
                    colorHistory = emptyList(),
                    onColorSelected = { color ->
                        val packed = android.graphics.Color.rgb(
                            (color.red * 255f).roundToInt(),
                            (color.green * 255f).roundToInt(),
                            (color.blue * 255f).roundToInt(),
                        )
                        if (packed != backgroundColor) onBackgroundColor(packed)
                    },
                    onModeChanged = { backgroundPickerMode = it },
                    onPaletteColorCountChanged = {}, onClearHistory = {},
                    compact = maxHeight < 620.dp,
                    title = "Canvas background",
                    showOriginalImageColors = false,
                    showPaletteAndHistory = false,
                    footer = {
                        ValueHeader("Background opacity", "${(backgroundOpacity * 100).roundToInt()}%")
                        Slider(backgroundOpacity, onBackgroundOpacity,
                            onValueChangeFinished = onBackgroundOpacityFinished,
                            valueRange = 0f..1f, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = closeBackgroundPicker, modifier = Modifier.align(Alignment.End)) {
                            Text("Done")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LayerControls(
    layer: LayerSummary,
    imageTransforming: Boolean,
    onRename: () -> Unit,
    onOpacity: (Float) -> Unit,
    onOpacityFinished: () -> Unit,
    onImageScale: (Float) -> Unit,
    onFitImage: () -> Unit,
    onOriginalImageSize: () -> Unit,
    onImageTransforming: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(layer.name, color = Color(0xFFE7E7E5), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onRename, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(32.dp)) { Text("Rename", fontSize = 11.sp) }
        }
        ValueHeader("Layer opacity", "${(layer.opacity * 100).roundToInt()}%")
        Slider(layer.opacity, onOpacity, onValueChangeFinished = onOpacityFinished,
            valueRange = 0f..1f, modifier = Modifier.fillMaxWidth().height(40.dp))
        if (layer.kind == LayerKind.IMAGE) {
            FilledTonalButton(
                onClick = { onImageTransforming(!imageTransforming) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (imageTransforming) Color(0xFFED6A5A) else Color(0xFF34363B),
                    contentColor = Color.White,
                ),
            ) { Text(if (imageTransforming) "Finish transforming" else "Edit photo on canvas") }
            Text(
                "Drag to move · pinch to resize · twist to rotate",
                color = Color(0xFFAAAEB4), fontSize = 11.sp,
                modifier = Modifier.padding(top = 5.dp, bottom = 8.dp),
            )
            ValueHeader("Image scale", "${((layer.imageScale ?: 1f) * 100).roundToInt()}%")
            val imageScale = layer.imageScale ?: 1f
            var scaleRange by remember(layer.id) { mutableFloatStateOf(maxOf(4f, imageScale * 2f)) }
            LaunchedEffect(imageScale) {
                if (imageScale > scaleRange) scaleRange = imageScale
            }
            Slider(imageScale.coerceIn(.0001f, scaleRange), onImageScale,
                valueRange = .0001f..scaleRange,
                onValueChangeFinished = { if (imageScale >= scaleRange * .95f) scaleRange = (scaleRange * 2f).takeIf { it.isFinite() } ?: scaleRange },
                modifier = Modifier.fillMaxWidth().height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onImageScale(imageScale / 2f) }) { Text("Half size") }
                OutlinedButton(onClick = { onImageScale(imageScale * 2f) }) { Text("Double size") }
            }
            Text(
                "Original ${layer.originalWidthPx} × ${layer.originalHeightPx} px · source preserved",
                color = Color(0xFFAAAEB4), fontSize = 11.sp,
            )
            Text(
                "Choose Eraser to hide image pixels non-destructively. A selection limits where it erases.",
                color = Color(0xFFAAAEB4), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onFitImage, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Fit canvas") }
                FilledTonalButton(onOriginalImageSize, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("100%") }
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: LayerSummary,
    preview: Bitmap?,
    primary: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleSelection: () -> Unit,
    onVisibility: () -> Unit,
    onSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    onPreviewBounds: (LayoutCoordinates) -> Unit = {},
    groupTarget: Boolean = false,
) {
    val shape = RoundedCornerShape(15.dp)
    var dragDistance by remember(layer.id) { mutableFloatStateOf(0f) }
    val swipeThreshold = with(LocalDensity.current) { 34.dp.toPx() }
    Row(
        modifier.fillMaxWidth().offset { IntOffset(dragDistance.coerceIn(0f, swipeThreshold).roundToInt(), 0) }.clip(shape)
            .background(if (selected) Color(0xFF343034) else Color(0xFF292A2E))
            .border(if (selected) 1.5.dp else .5.dp,
                if (primary) Color(0xFFED6A5A) else if (selected) Color(0xFF75A7FF) else Color(0xFF414349), shape)
            .pointerInput(layer.id, selected) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        if (amount > 0f || dragDistance > 0f) {
                            change.consume()
                            dragDistance = (dragDistance + amount).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        if (dragDistance >= swipeThreshold) onToggleSelection()
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            }
            .clickable(onClick = onSelect).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.onGloballyPositioned { onPreviewBounds(it) }
            .border(if (groupTarget) 2.dp else 0.dp, Color(0xFF75A7FF), RoundedCornerShape(10.dp))) {
            LayerThumbnail(layer.kind, preview)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(layer.name, color = Color(0xFFF1F1EF), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (layer.clipSourceId != null) "Clipped layer" else if (layer.kind == LayerKind.IMAGE) "Image · original linked" else "Paint layer",
                color = Color(0xFF9EA0A6), fontSize = 10.sp,
            )
        }
        IconButton(onSettings, Modifier.size(36.dp).semantics { contentDescription = "Settings for ${layer.name}" }) {
            Text("⋮", color = Color(0xFFB9BBC0), fontSize = 22.sp)
        }
        IconButton(onVisibility, Modifier.size(40.dp).semantics { contentDescription = if (layer.visible) "Hide ${layer.name}" else "Show ${layer.name}" }) {
            EyeIcon(layer.visible)
        }
    }
}

@Composable
private fun LayerThumbnail(kind: LayerKind, preview: Bitmap?) {
    Canvas(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF2F1ED))) {
        if (preview != null) {
            drawImage(preview.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        } else if (kind == LayerKind.IMAGE) {
            drawRect(Color(0xFFB7D4DD))
            drawCircle(Color(0xFFFFD786), size.minDimension * .1f, Offset(size.width * .72f, size.height * .27f))
            val mountains = Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width * .38f, size.height * .42f)
                lineTo(size.width * .58f, size.height * .7f)
                lineTo(size.width * .76f, size.height * .5f)
                lineTo(size.width, size.height * .76f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(mountains, Color(0xFF496D66))
        } else {
            val path = Path().apply {
                moveTo(size.width * .12f, size.height * .68f)
                cubicTo(size.width * .3f, size.height * .1f, size.width * .52f, size.height * .9f, size.width * .88f, size.height * .3f)
            }
            drawPath(path, Color(0xFF202125), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun EyeIcon(visible: Boolean) {
    Canvas(Modifier.size(21.dp)) {
        val color = if (visible) Color.White else Color(0xFF777A81)
        val path = Path().apply {
            moveTo(1f, size.height / 2)
            quadraticTo(size.width / 2, 2f, size.width - 1f, size.height / 2)
            quadraticTo(size.width / 2, size.height - 2f, 1f, size.height / 2)
        }
        drawPath(path, color, style = Stroke(1.6.dp.toPx()))
        if (visible) drawCircle(color, 2.8.dp.toPx()) else drawLine(color, Offset(2f, 2f), Offset(size.width - 2f, size.height - 2f), 1.6.dp.toPx())
    }
}

@Composable
private fun ValueHeader(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFFE7E7E5), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(value, color = Color(0xFFED6A5A), fontSize = 12.sp)
    }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick, contentPadding = PaddingValues(horizontal = 11.dp), modifier = Modifier.height(40.dp)) { Text(label, fontSize = 12.sp) }
}

internal data class LayerDropTarget(
    val targetId: LayerId,
    val towardFront: Boolean,
    val group: Boolean,
    val destinationIndex: Int,
)

/** Top of the dragged item's final slot, using measured item heights. */
internal fun insertionLineY(
    layers: List<LayerSummary>, sourceIndex: Int, destinationIndex: Int,
    items: Map<LayerId, Rect>, sourceHeight: Float = layers.getOrNull(sourceIndex)?.let { items[it.id]?.height } ?: 0f,
): Float? {
    val destination = layers.getOrNull(destinationIndex)?.let { items[it.id] } ?: return null
    if (destinationIndex <= sourceIndex) return destination.top
    if (sourceHeight <= 0f) return null
    return destination.bottom - sourceHeight
}

/** Hit tests in the stationary list's root coordinates, independent of the drag overlay. */
internal fun resolveLayerDrop(
    layers: List<LayerSummary>, sourceId: LayerId?, point: Offset, viewport: Rect,
    rows: Map<LayerId, Rect>, previews: Map<LayerId, Rect>,
    grabY: Float = rows[sourceId]?.height?.div(2f) ?: 0f,
    items: Map<LayerId, Rect> = rows,
    sourceHeight: Float = items[sourceId]?.height ?: 0f,
): LayerDropTarget? {
    val source = layers.indexOfFirst { it.id == sourceId }
    if (source < 0 || point == Offset.Unspecified || !viewport.contains(point)) return null
    val previewTarget = layers.firstOrNull { it.id != sourceId && previews[it.id]?.contains(point) == true }
    if (previewTarget != null && (previewTarget.groupId == null || previewTarget.groupId != layers[source].groupId)) {
        return LayerDropTarget(previewTarget.id, true, true, source)
    }
    // Choose the nearest final slot for the dragged item's top edge. The rows
    // beneath it can already be translated by the preview, so hit testing their
    // original centers makes an upward drop land one row too low.
    val draggedTop = point.y - grabY
    val destinationIndex = layers.indices.mapNotNull { index ->
        val item = items[layers[index].id] ?: return@mapNotNull null
        if (!item.overlaps(viewport)) return@mapNotNull null
        insertionLineY(layers, source, index, items, sourceHeight)?.let { index to it }
    }.minByOrNull { (_, line) -> kotlin.math.abs(line - draggedTop) }?.first ?: return null
    if (destinationIndex == source) return null
    val targetIndex = destinationIndex
    val target = layers[targetIndex]
    val towardFront = destinationIndex < source
    val sameGroup = target.groupId != null && target.groupId == layers[source].groupId
    val anchor = if (target.groupId != null && !sameGroup) {
        if (towardFront) layers.indexOfFirst { it.groupId == target.groupId }
        else layers.indexOfLast { it.groupId == target.groupId }
    } else targetIndex
    val insertion = anchor + if (towardFront) 0 else 1
    return LayerDropTarget(target.id, towardFront, false, insertion - if (source < insertion) 1 else 0)
}

/** Also safe during the last placement frame after drag state has been cleared. */
internal fun layerDragOffset(point: Offset, origin: Offset, grab: Offset): IntOffset {
    val local = point - origin - grab
    return if (local.x.isFinite() && local.y.isFinite()) IntOffset(local.x.roundToInt(), local.y.roundToInt()) else IntOffset.Zero
}
