package dev.tipstroke.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.tipstroke.drawing.android.MediaImporter
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
    val context = LocalContext.current
    var importingMedia by remember { mutableStateOf(false) }
    var mediaImportError by remember { mutableStateOf<String?>(null) }
    val latestOnOpen by rememberUpdatedState(onOpen)
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importingMedia = true
            MediaImporter.import(context.applicationContext, library, uri) { result ->
                importingMedia = false
                result.fold(onSuccess = { latestOnOpen(it) }, onFailure = { mediaImportError = it.message ?: "Could not import media" })
            }
        }
    }
    if (importingMedia) AlertDialog(onDismissRequest = {}, title = { Text("Importing media") },
        text = { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparing your local canvas…") } }, confirmButton = {})
    mediaImportError?.let { error -> AlertDialog(onDismissRequest = { mediaImportError = null },
        title = { Text("Import failed") }, text = { Text(error) },
        confirmButton = { TipAction("OK", Icons.Outlined.Check, onClick = { mediaImportError = null }) }) }
    var items by remember(library) { mutableStateOf(library.galleryItems()) }
    var openStackId by remember { mutableStateOf<String?>(null) }
    var transferOpen by remember { mutableStateOf(false) }
    var transferIds by remember { mutableStateOf<List<String>?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selectedDrawings by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reordering by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var deleteIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
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


    fun refreshGallery() {
        items = library.galleryItems()
        if (openStackId != null && items.none { it is GalleryStack && it.id == openStackId }) openStackId = null
    }

    fun moveItem(item: GalleryItem, delta: Int) {
        val index = visibleItems.indexOfFirst { it.key == item.key }
        val target = visibleItems.getOrNull(index + delta) ?: return
        if (openStack != null) library.reorderInStack(openStack.id, item.key.removePrefix("drawing:"), target.key.removePrefix("drawing:"), delta > 0)
        else library.reorderTopLevel(item.key, target.key, delta > 0)
        refreshGallery()
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

    fun changeDrawings(ids: List<String>, delete: Boolean) {
        if (working || ids.isEmpty()) return
        working = true
        val parent = openStack?.key
        coroutineScope.launch {
            val failed = withContext(Dispatchers.IO) {
                ids.filterNot { id ->
                    runCatching {
                        if (delete) check(library.delete(id)) { "Could not delete drawing" }
                        else {
                            val copy = library.duplicate(id).getOrThrow()
                            if (parent != null) library.stackDrawing(copy.id, parent)
                        }
                    }.isSuccess
                }.toSet()
            }
            selectedDrawings = failed
            if (failed.isEmpty()) selecting = false
            else operationError = "Could not " + (if (delete) "delete" else "duplicate") + " ${failed.size} drawing(s)."
            working = false
            refreshGallery()
        }
    }

    fun dragModifier(item: GalleryItem) = Modifier
        .onGloballyPositioned { cardBounds[item.key] = it.boundsInRoot() }
        .zIndex(if (draggedKey == item.key) 2f else 0f)
        .then(if (dropTargetKey == item.key) Modifier.border(2.dp, Color(0xFFED6A5A), RoundedCornerShape(14.dp)) else Modifier)
        .pointerInput(item.key, visibleItems, openStackId) {
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
        .graphicsLayer {
            alpha = if (draggedKey == item.key) .72f else 1f
            translationX = if (draggedKey == item.key) dragTranslation.x else 0f
            translationY = if (draggedKey == item.key) dragTranslation.y else 0f
        }

    CompositionLocalProvider(LocalContentColor provides Color(0xFFF5F5F2)) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF17181B)).safeDrawingPadding()) {
        val shortWindow = maxHeight < 500.dp
        val gutter = if (maxWidth < 600.dp || shortWindow) 8.dp else 16.dp
        val minimumWidth = 156.dp
        val thumbnailHeight = minOf(minimumWidth * .78f, maxHeight * .32f)
        Column {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minimumWidth),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "Gallery drawings" }.onGloballyPositioned { gridBounds = it.boundsInRoot() },
                contentPadding = PaddingValues(gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "gallery-heading", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                if (openStack != null) TipAction("‹ Gallery", Icons.Outlined.ArrowBack, onClick = { openStackId = null })
                                Text(openStack?.name ?: "TipStroke", color = Color(0xFFF5F5F2), fontSize = if (shortWindow) 22.sp else 28.sp, fontWeight = FontWeight.SemiBold)
                                if (openStack != null) {
                                    TipAction("Rename", Icons.Outlined.Edit, onClick = { renameStack = openStack })
                                    TipAction("Unstack", Icons.Outlined.LayersClear, onClick = { library.unstack(openStack.id); refreshGallery() })
                                }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                if (!selecting) {
                                    TipAction("New drawing", Icons.Outlined.Add) { creating = true }
                                    TipAction("Settings", Icons.Outlined.Settings, onClick = onSettings)
                                    TipAction("Image / GIF / video", Icons.Outlined.PermMedia, enabled = !importingMedia, onClick = { mediaPicker.launch(arrayOf("image/*", "video/*")) })
                                    TipAction(if (reordering) "Done reordering" else "Reorder", if (reordering) Icons.Outlined.Check else Icons.Outlined.SwapVert) { reordering = !reordering }
                                    TipAction("Import project", Icons.Outlined.FileOpen, onClick = { transferIds = null; transferOpen = true })
                                    TipAction("Select drawings", Icons.Outlined.Checklist, onClick = { selecting = true; reordering = false })
                                } else {
                                    val visible = visibleItems.flatMap { if (it is GalleryDrawing) listOf(it.drawing.id) else (it as GalleryStack).drawings.map { drawing -> drawing.id } }.toSet()
                                    val all = visible.isNotEmpty() && selectedDrawings.containsAll(visible)
                                    Text("${selectedDrawings.size} selected", modifier = Modifier.align(Alignment.CenterVertically))
                                    TipAction(if (all) "Deselect all" else "Select all", Icons.Outlined.SelectAll, enabled = !working) {
                                        selectedDrawings = if (all) emptySet() else visible
                                    }
                                    Box {
                                        TipAction("Selected drawing actions", Icons.Outlined.MoreVert, enabled = selectedDrawings.isNotEmpty() && !working) { selectionMenu = true }
                                        DropdownMenu(selectionMenu, { selectionMenu = false }) {
                                            DropdownMenuItem(text = { Text("Export") }, onClick = { selectionMenu = false; transferIds = selectedDrawings.toList(); transferOpen = true })
                                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { selectionMenu = false; changeDrawings(selectedDrawings.toList(), false) })
                                            DropdownMenuItem(text = { Text("Delete") }, onClick = { selectionMenu = false; deleteIds = selectedDrawings.toList() })
                                        }
                                    }
                                    TipAction("Cancel selection", Icons.Outlined.Close, enabled = !working) { selecting = false; selectedDrawings = emptySet() }
                                }
                            }
                        }
                        Text(if (openStack == null) "Hold to reorder · drop in the center to group"
                            else "Hold to reorder drawings in this stack",
                            color = Color(0xFF9EA0A6), fontSize = 12.sp)
                    }
                }
                items(visibleItems, key = GalleryItem::key) { item ->
                    DisposableEffect(item.key) { onDispose { cardBounds.remove(item.key) } }
                    Column {
                    if (reordering) Row {
                        TipAction("Move earlier", Icons.Outlined.ArrowBack, enabled = visibleItems.firstOrNull()?.key != item.key, onClick = { moveItem(item, -1) })
                        TipAction("Move later", Icons.Outlined.ArrowForward, enabled = visibleItems.lastOrNull()?.key != item.key, onClick = { moveItem(item, 1) })
                    }
                    when (item) {
                        is GalleryDrawing -> DrawingCard(
                            item.drawing, {
                                if (working) return@DrawingCard
                                if (selecting) selectedDrawings = if (item.drawing.id in selectedDrawings) selectedDrawings - item.drawing.id else selectedDrawings + item.drawing.id
                                else onOpen(item.drawing)
                            }, { changeDrawings(listOf(item.drawing.id), false) },
                            { deleteIds = listOf(item.drawing.id) }, if (selecting) Modifier else dragModifier(item),
                            onExport = { transferIds = listOf(item.drawing.id); transferOpen = true },
                            onMoveOut = openStack?.let { stack -> { library.moveOutOfStack(stack.id, item.drawing.id); refreshGallery() } },
                            thumbnailHeight = thumbnailHeight,
                            selected = if (selecting) item.drawing.id in selectedDrawings else null,
                        )
                        is GalleryStack -> {
                            val ids = item.drawings.map { it.id }
                            val all = selectedDrawings.containsAll(ids)
                            StackCard(item, {
                                if (!selecting) openStackId = item.id
                                else if (!working) selectedDrawings = if (all) selectedDrawings - ids.toSet() else selectedDrawings + ids
                            }, if (selecting) Modifier else dragModifier(item), thumbnailHeight, if (selecting) all else null)
                        }
                    }
                    }
                }
                if (openStack == null && !selecting) item { NewDrawingCard(thumbnailHeight) { creating = true } }
                if (visibleItems.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Your drawings are saved locally on this device.", color = Color(0xFF9EA0A6), fontSize = 13.sp)
                }
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
    if (deleteIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteIds = emptyList() },
            title = { Text("Delete ${deleteIds.size} drawing(s)?") },
            text = { Text("This removes the local drawing and cannot be undone.") },
            confirmButton = { TipAction("Delete", Icons.Outlined.Delete, onClick = { changeDrawings(deleteIds, true); deleteIds = emptyList() }) },
            dismissButton = { TipAction("Cancel", Icons.Outlined.Close, onClick = { deleteIds = emptyList() }) },
        )
    }
    operationError?.let { error ->
        AlertDialog(
            onDismissRequest = { operationError = null },
            title = { Text("Gallery action failed") },
            text = { Text(error) },
            confirmButton = { TipAction("OK", Icons.Outlined.Check, onClick = { operationError = null }) },
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
private fun DrawingCard(
    drawing: DrawingSummary,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onExport: () -> Unit,
    onMoveOut: (() -> Unit)? = null,
    thumbnailHeight: Dp = 180.dp,
    selected: Boolean? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen).padding(2.dp)) {
        val image = remember(drawing.thumbnailFile.absolutePath, drawing.modifiedAtMillis) {
            BitmapFactory.decodeFile(drawing.thumbnailFile.absolutePath)?.asImageBitmap()
        }
        Box(Modifier.fillMaxWidth().height(thumbnailHeight).clip(RoundedCornerShape(12.dp)).background(Color(0xFF292B30)), contentAlignment = Alignment.Center) {
            image?.let { Image(it, drawing.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (selected != null) Checkbox(selected, onCheckedChange = { onOpen() }, modifier = Modifier.align(Alignment.TopEnd).background(Color(0xDD202125)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(drawing.name, color = Color(0xFFF3F3F0), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(drawing.modifiedAtMillis))}  •  ${drawing.widthPx} × ${drawing.heightPx}",
                    color = Color(0xFF9EA0A6), fontSize = 11.sp,
                )
            }
            if (selected == null) Box {
                TipAction("Drawing actions", Icons.Outlined.MoreVert) { menuOpen = true }
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
private fun StackCard(stack: GalleryStack, onOpen: () -> Unit, modifier: Modifier = Modifier, thumbnailHeight: Dp = 180.dp, selected: Boolean? = null) {
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
            if (selected != null) Checkbox(selected, onCheckedChange = { onOpen() }, modifier = Modifier.align(Alignment.TopEnd).background(Color(0xDD202125)))
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
        confirmButton = { ActionIconButton(onClick = { onRename(name) }, label = "Rename", icon = Icons.Outlined.Edit) },
        dismissButton = { TipAction("Cancel", Icons.Outlined.Close, onClick = onDismiss) },
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
    var favorites by remember { mutableStateOf(preferences.favoriteIds()) }
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
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp),
        title = { Text("New drawing") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                dev.tipstroke.ui.ResolutionBrowser(
                    presets = builtIns + saved, selectedId = selectedId, favorites = favorites,
                    onSelect = { select(it) },
                    onFavorites = { favorites = it; preferences.setFavorites(it) },
                    customPreview = if (validWidth != null && validHeight != null)
                        CanvasPreset("custom-preview", "Custom", validWidth, validHeight, "Custom") else null,
                )
                saved.firstOrNull { it.id == selectedId }?.let { selected ->
                    ActionIconButton(onClick = {
                        preferences.remove(selected.id)
                        saved = preferences.saved()
                        favorites = preferences.favoriteIds()
                        defaultId = preferences.defaultId()
                        select(builtIns.first())
                    }, label = "Remove saved preset", icon = Icons.Outlined.Delete)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(width, { width = it.filter(Char::isDigit); selectedId = null }, Modifier.weight(1f), label = { Text("Width px") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(height, { height = it.filter(Char::isDigit); selectedId = null }, Modifier.weight(1f), label = { Text("Height px") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (validWidth != null && validHeight != null) Text(
                    "1/${dev.tipstroke.drawing.android.LayerCapacity.maximum(context, validWidth, validHeight)} Layers · smaller canvases allow more layers",
                    style = MaterialTheme.typography.labelMedium)
                Text("Each side: $MIN_CANVAS_SIDE–$MAX_CANVAS_SIDE px. Drawings use sparse tiles, so empty areas take little space.", style = MaterialTheme.typography.bodySmall)
                if (selectedId == null) {
                    OutlinedTextField(presetName, { presetName = it }, Modifier.fillMaxWidth(), label = { Text("Custom preset name") }, singleLine = true)
                    ActionIconButton(
                        onClick = {
                            val preset = preferences.add(presetName, validWidth!!, validHeight!!)
                            saved = preferences.saved()
                            select(preset)
                            presetName = ""
                        },
                        enabled = validWidth != null && validHeight != null && presetName.isNotBlank(), label = "Save custom preset", icon = Icons.Outlined.Save)
                }
                selectedId?.let { id ->
                    if (id != defaultId) ActionIconButton(onClick = { preferences.setDefault(id); defaultId = id }, label = "Set as default", icon = Icons.Outlined.Star)
                    else Text("★ Default preset", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = { ActionIconButton(onClick = { onCreate(name.ifBlank { "Untitled drawing" }, validWidth!!, validHeight!!) }, enabled = validWidth != null && validHeight != null, label = "Create", icon = Icons.Outlined.Add) },
        dismissButton = { TipAction("Cancel", Icons.Outlined.Close, onClick = onDismiss) },
    )
}
