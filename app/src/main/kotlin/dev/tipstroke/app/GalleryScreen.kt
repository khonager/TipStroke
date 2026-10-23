package dev.tipstroke.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import dev.tipstroke.drawing.android.DrawingLibrary
import dev.tipstroke.drawing.android.DrawingSummary
import dev.tipstroke.drawing.android.GalleryDrawing
import dev.tipstroke.drawing.android.GalleryItem
import dev.tipstroke.drawing.android.GalleryStack
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NewDrawingRequest(val id: String, val name: String, val widthPx: Int, val heightPx: Int)

@Composable
fun GalleryScreen(
    library: DrawingLibrary,
    onOpen: (DrawingSummary) -> Unit,
    onNew: (NewDrawingRequest) -> Unit,
    onSettings: () -> Unit,
) {
    var items by remember(library) { mutableStateOf(library.galleryItems()) }
    var openStackId by remember { mutableStateOf<String?>(null) }
    var transferOpen by remember { mutableStateOf(false) }
    var transferIds by remember { mutableStateOf<List<String>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<DrawingSummary?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var renameStack by remember { mutableStateOf<GalleryStack?>(null) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragPoint by remember { mutableStateOf(Offset.Unspecified) }
    var dragTranslation by remember { mutableStateOf(Offset.Zero) }
    var dropTargetKey by remember { mutableStateOf<String?>(null) }
    val cardBounds = remember { mutableStateMapOf<String, Rect>() }
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    val openStack = items.filterIsInstance<GalleryStack>().firstOrNull { it.id == openStackId }
    val visibleItems: List<GalleryItem> = openStack?.drawings?.map(::GalleryDrawing) ?: items
    LaunchedEffect(openStackId) { cardBounds.clear() }

    fun refreshGallery() {
        items = library.galleryItems()
        if (openStackId != null && items.none { it is GalleryStack && it.id == openStackId }) openStackId = null
    }

    fun updateDropTarget(point: Offset) {
        dropTargetKey = cardBounds.entries.firstOrNull { (key, bounds) -> key != draggedKey && bounds.contains(point) }?.key
    }

    fun finishDrag() {
        val sourceKey = draggedKey
        val targetKey = dropTargetKey
        val point = dragPoint
        val targetBounds = targetKey?.let(cardBounds::get)
        if (sourceKey != null && targetKey != null && targetBounds != null) {
            val centerZone = Rect(
                targetBounds.left + targetBounds.width * .22f,
                targetBounds.top + targetBounds.height * .18f,
                targetBounds.right - targetBounds.width * .22f,
                targetBounds.bottom - targetBounds.height * .18f,
            )
            if (openStack != null) {
                library.reorderInStack(
                    openStack.id,
                    sourceKey.removePrefix("drawing:"),
                    targetKey.removePrefix("drawing:"),
                    point.y > targetBounds.center.y || point.x > targetBounds.center.x,
                )
            } else {
                val source = items.firstOrNull { it.key == sourceKey }
                val target = items.firstOrNull { it.key == targetKey }
                if (source is GalleryDrawing && centerZone.contains(point) && target != null) {
                    library.stackDrawing(source.drawing.id, target.key)
                } else {
                    library.reorderTopLevel(
                        sourceKey,
                        targetKey,
                        point.y > targetBounds.center.y || point.x > targetBounds.center.x,
                    )
                }
            }
            refreshGallery()
        }
        draggedKey = null
        dropTargetKey = null
        dragPoint = Offset.Unspecified
        dragTranslation = Offset.Zero
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    fun duplicateDrawing(drawing: DrawingSummary) {
        val stackKey = openStack?.key
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                library.duplicate(drawing.id).onSuccess { duplicate ->
                    stackKey?.let { library.stackDrawing(duplicate.id, it) }
                }
            }
            result.onSuccess { duplicate ->
                refreshGallery()
            }.onFailure { operationError = it.message ?: "The drawing could not be duplicated." }
        }
    }

    fun dragModifier(item: GalleryItem) = Modifier
        .onGloballyPositioned { cardBounds[item.key] = it.boundsInRoot() }
        .zIndex(if (draggedKey == item.key) 2f else 0f)
        .graphicsLayer {
            alpha = if (draggedKey == item.key) .72f else 1f
            if (draggedKey == item.key) {
                translationX = dragTranslation.x
                translationY = dragTranslation.y
            }
        }
        .then(if (dropTargetKey == item.key) Modifier.border(2.dp, Color(0xFFED6A5A), RoundedCornerShape(14.dp)) else Modifier)
        .pointerInput(item.key, openStackId) {
            detectDragGesturesAfterLongPress(
                onDragStart = { local ->
                    draggedKey = item.key
                    dragTranslation = Offset.Zero
                    dragPoint = (cardBounds[item.key]?.topLeft ?: Offset.Zero) + local
                    updateDropTarget(dragPoint)
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragPoint += amount
                    dragTranslation += amount
                    updateDropTarget(dragPoint)
                    if (gridBounds != Rect.Zero && autoScrollJob?.isActive != true) {
                        when {
                            dragPoint.y < gridBounds.top + 90f -> autoScrollJob = coroutineScope.launch {
                                dragTranslation += Offset(0f, gridState.scrollBy(-32f))
                            }
                            dragPoint.y > gridBounds.bottom - 90f -> autoScrollJob = coroutineScope.launch {
                                dragTranslation += Offset(0f, gridState.scrollBy(32f))
                            }
                        }
                    }
                },
                onDragEnd = ::finishDrag,
                onDragCancel = {
                    autoScrollJob?.cancel(); autoScrollJob = null
                    draggedKey = null; dropTargetKey = null; dragPoint = Offset.Unspecified; dragTranslation = Offset.Zero
                },
            )
        }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF17181B)).safeDrawingPadding()) {
        val shortWindow = maxHeight < 500.dp
        val gutter = if (maxWidth < 600.dp || shortWindow) 16.dp else 28.dp
        val thumbnailHeight = (maxHeight * .32f).coerceIn(100.dp, 220.dp)
        Column {
            GalleryTopBar(onNew = { creating = true }, onSettings = onSettings)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (shortWindow) 180.dp else 240.dp),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "Gallery drawings" }.onGloballyPositioned { gridBounds = it.boundsInRoot() },
                contentPadding = PaddingValues(gutter),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "gallery-heading", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                if (openStack != null) TextButton(onClick = { openStackId = null }) { Text("‹ Gallery") }
                                Text(openStack?.name ?: "Your drawings", color = Color(0xFFF5F5F2), fontSize = if (shortWindow) 22.sp else 28.sp, fontWeight = FontWeight.SemiBold)
                                if (openStack != null) {
                                    TextButton(onClick = { renameStack = openStack }) { Text("Rename") }
                                    TextButton(onClick = { library.unstack(openStack.id); refreshGallery() }) { Text("Unstack") }
                                }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = { transferIds = null; transferOpen = true }) { Text("Import") }
                                TextButton(onClick = {
                                    transferIds = items.flatMap { if (it is GalleryDrawing) listOf(it.drawing.id) else (it as GalleryStack).drawings.map { drawing -> drawing.id } }
                                    transferOpen = true
                                }) { Text("Export / select drawings") }
                            }
                        }
                        Text(if (openStack == null) "Hold to reorder · drop in the center to group"
                            else "Hold to reorder drawings in this stack",
                            color = Color(0xFF9EA0A6), fontSize = 12.sp)
                    }
                }
                items(visibleItems, key = GalleryItem::key) { item ->
                    when (item) {
                        is GalleryDrawing -> DrawingCard(
                            item.drawing, { onOpen(item.drawing) }, { duplicateDrawing(item.drawing) },
                            { deleteCandidate = item.drawing }, dragModifier(item),
                            onExport = { transferIds = listOf(item.drawing.id); transferOpen = true },
                            onMoveOut = openStack?.let { stack -> { library.moveOutOfStack(stack.id, item.drawing.id); refreshGallery() } },
                            thumbnailHeight = thumbnailHeight,
                        )
                        is GalleryStack -> StackCard(item, { openStackId = item.id }, dragModifier(item), thumbnailHeight)
                    }
                }
                if (openStack == null) item { NewDrawingCard(thumbnailHeight) { creating = true } }
                if (visibleItems.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Your drawings are saved locally on this device.", color = Color(0xFF9EA0A6), fontSize = 13.sp)
                }
            }
        }
    }

    if (transferOpen) GalleryTransfer(library, transferIds, { transferOpen = false }, ::refreshGallery)

    if (creating) NewDrawingDialog(
        onDismiss = { creating = false },
        onCreate = { name, width, height ->
            creating = false
            onNew(NewDrawingRequest(library.newId(), name, width, height))
        },
    )
    deleteCandidate?.let { drawing ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete ${drawing.name}?") },
            text = { Text("This removes the local drawing and cannot be undone.") },
            confirmButton = { TextButton(onClick = { library.delete(drawing.id); deleteCandidate = null; refreshGallery() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } },
        )
    }
    operationError?.let { error ->
        AlertDialog(
            onDismissRequest = { operationError = null },
            title = { Text("Could not duplicate drawing") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { operationError = null }) { Text("OK") } },
        )
    }
    renameStack?.let { stack ->
        RenameStackDialog(
            stack.name,
            onDismiss = { renameStack = null },
            onRename = { library.renameStack(stack.id, it); renameStack = null; refreshGallery() },
        )
    }
}

@Composable
private fun GalleryTopBar(onNew: () -> Unit, onSettings: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 560.dp
        Row(
            Modifier.fillMaxWidth().height(62.dp).border(.5.dp, Color(0xFF34363A)).padding(horizontal = if (compact) 16.dp else 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tip", color = Color(0xFFF4F4F2), fontSize = if (compact) 21.sp else 25.sp, fontWeight = FontWeight.SemiBold)
            Text("Stroke", color = Color(0xFFED6A5A), fontSize = if (compact) 21.sp else 25.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Button(onNew, shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = if (compact) 13.dp else 18.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFED6A5A))) {
                Text(if (compact) "＋" else "＋  New drawing", color = Color.White, fontSize = if (compact) 20.sp else 14.sp)
            }
            Spacer(Modifier.width(if (compact) 4.dp else 12.dp))
            if (compact) IconButton(onClick = onSettings, modifier = Modifier.semantics { contentDescription = "Settings" }) { GallerySettingsIcon() }
            else TextButton(onClick = onSettings, modifier = Modifier.semantics { contentDescription = "Settings" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Settings", color = Color(0xFFE8E8E6)) }
        }
    }
}

@Composable private fun GallerySettingsIcon() {
    Canvas(Modifier.size(24.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val color = Color(0xFFE8E8E6)
        drawCircle(color, size.minDimension * .25f, center, style = Stroke(2.dp.toPx()))
        drawCircle(color, size.minDimension * .06f, center)
        repeat(8) { index ->
            val angle = Math.toRadians((index * 45.0))
            val inner = size.minDimension * .33f
            val outer = size.minDimension * .44f
            drawLine(color, Offset(center.x + kotlin.math.cos(angle).toFloat() * inner, center.y + kotlin.math.sin(angle).toFloat() * inner), Offset(center.x + kotlin.math.cos(angle).toFloat() * outer, center.y + kotlin.math.sin(angle).toFloat() * outer), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun DrawingCard(
    drawing: DrawingSummary,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onExport: () -> Unit,
    onMoveOut: (() -> Unit)? = null,
    thumbnailHeight: Dp = 180.dp,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen).padding(2.dp)) {
        val image = remember(drawing.thumbnailFile.absolutePath, drawing.modifiedAtMillis) {
            BitmapFactory.decodeFile(drawing.thumbnailFile.absolutePath)?.asImageBitmap()
        }
        Box(Modifier.fillMaxWidth().height(thumbnailHeight).clip(RoundedCornerShape(12.dp)).background(Color(0xFF292B30)), contentAlignment = Alignment.Center) {
            image?.let { Image(it, drawing.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(drawing.name, color = Color(0xFFF3F3F0), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(drawing.modifiedAtMillis))}  •  ${drawing.widthPx} × ${drawing.heightPx}",
                    color = Color(0xFF9EA0A6), fontSize = 11.sp,
                )
            }
            Box {
                TextButton(onClick = { menuOpen = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(38.dp)) { Text("⋮", fontSize = 24.sp) }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    onMoveOut?.let { moveOut -> DropdownMenuItem(text = { Text("Move out of stack") }, onClick = { menuOpen = false; moveOut() }) }
                    DropdownMenuItem(text = { Text("Export") }, onClick = { menuOpen = false; onExport() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun StackCard(stack: GalleryStack, onOpen: () -> Unit, modifier: Modifier = Modifier, thumbnailHeight: Dp = 180.dp) {
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen).padding(2.dp)) {
        Box(
            Modifier.fillMaxWidth().height(thumbnailHeight).clip(RoundedCornerShape(12.dp)).background(Color(0xFF292B30)).padding(8.dp),
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(2) { column ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(2) { row ->
                            val drawing = stack.drawings.getOrNull(column * 2 + row)
                            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(if (drawing == null) Color(0xFF34363A) else Color.White)) {
                                drawing?.let {
                                    val image = remember(it.thumbnailFile.absolutePath, it.modifiedAtMillis) {
                                        BitmapFactory.decodeFile(it.thumbnailFile.absolutePath)?.asImageBitmap()
                                    }
                                    image?.let { bitmap -> Image(bitmap, it.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(stack.name, color = Color(0xFFF3F3F0), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 9.dp))
        Text("${stack.drawings.size} drawings", color = Color(0xFF9EA0A6), fontSize = 11.sp)
    }
}

@Composable
private fun RenameStackDialog(currentName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename stack") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Stack name") }, singleLine = true) },
        confirmButton = { Button(onClick = { onRename(name) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewDrawingCard(thumbnailHeight: Dp = 180.dp, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(thumbnailHeight).border(1.dp, Color(0xFF555860), RoundedCornerShape(12.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text("＋", color = Color(0xFFF2F2EF), fontSize = 42.sp, fontWeight = FontWeight.Light) }
        Text("New drawing", color = Color(0xFFF3F3F0), fontSize = 16.sp, modifier = Modifier.padding(top = 9.dp))
    }
}

@Composable
private fun NewDrawingDialog(onDismiss: () -> Unit, onCreate: (String, Int, Int) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { CanvasPresetPreferences(context) }
    val builtIns = remember(context) { builtInCanvasPresets(context) }
    var saved by remember { mutableStateOf(preferences.saved()) }
    var defaultId by remember { mutableStateOf(preferences.defaultId()) }
    val initial = (builtIns + saved).firstOrNull { it.id == defaultId } ?: builtIns.first()
    var selectedId by remember { mutableStateOf<String?>(initial.id) }
    var name by remember { mutableStateOf("Untitled drawing") }
    var width by remember { mutableStateOf(initial.widthPx.toString()) }
    var height by remember { mutableStateOf(initial.heightPx.toString()) }
    var presetName by remember { mutableStateOf("") }
    val validWidth = width.toIntOrNull()?.takeIf { it in MIN_CANVAS_SIDE..MAX_CANVAS_SIDE }
    val validHeight = height.toIntOrNull()?.takeIf { it in MIN_CANVAS_SIDE..MAX_CANVAS_SIDE }
    fun select(preset: CanvasPreset) {
        selectedId = preset.id
        width = preset.widthPx.toString()
        height = preset.heightPx.toString()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New drawing") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                (builtIns + saved).groupBy(CanvasPreset::category).forEach { (category, presets) ->
                    Column {
                        Text(category, style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            presets.forEach { preset ->
                                FilterChip(
                                    selected = selectedId == preset.id,
                                    onClick = { select(preset) },
                                    label = { Text("${preset.name} · ${preset.widthPx} × ${preset.heightPx}${if (preset.id == defaultId) " ★" else ""}") },
                                )
                            }
                        }
                    }
                }
                saved.firstOrNull { it.id == selectedId }?.let { selected ->
                    TextButton(onClick = {
                        preferences.remove(selected.id)
                        saved = preferences.saved()
                        defaultId = preferences.defaultId()
                        select(builtIns.first())
                    }) { Text("Remove saved preset") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(width, { width = it.filter(Char::isDigit); selectedId = null }, Modifier.weight(1f), label = { Text("Width px") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(height, { height = it.filter(Char::isDigit); selectedId = null }, Modifier.weight(1f), label = { Text("Height px") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Text("Each side: $MIN_CANVAS_SIDE–$MAX_CANVAS_SIDE px. Drawings use sparse tiles, so empty areas take little space.", style = MaterialTheme.typography.bodySmall)
                if (selectedId == null) {
                    OutlinedTextField(presetName, { presetName = it }, Modifier.fillMaxWidth(), label = { Text("Custom preset name") }, singleLine = true)
                    TextButton(
                        onClick = {
                            val preset = preferences.add(presetName, validWidth!!, validHeight!!)
                            saved = preferences.saved()
                            select(preset)
                            presetName = ""
                        },
                        enabled = validWidth != null && validHeight != null && presetName.isNotBlank(),
                    ) { Text("Save custom preset") }
                }
                selectedId?.let { id ->
                    if (id != defaultId) TextButton(onClick = { preferences.setDefault(id); defaultId = id }) { Text("Set as default") }
                    else Text("★ Default preset", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = { Button(onClick = { onCreate(name.ifBlank { "Untitled drawing" }, validWidth!!, validHeight!!) }, enabled = validWidth != null && validHeight != null) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
