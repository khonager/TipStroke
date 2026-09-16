package dev.tipstroke.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.launch

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
    var creating by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<DrawingSummary?>(null) }
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

    Column(Modifier.fillMaxSize().background(Color(0xFF17181B))) {
        GalleryTopBar(onNew = { creating = true }, onSettings = onSettings)
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (openStack != null) {
                    TextButton(onClick = { openStackId = null }) { Text("‹  Gallery") }
                    Spacer(Modifier.width(8.dp))
                }
                Text(openStack?.name ?: "Your drawings", color = Color(0xFFF5F5F2), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (openStack != null) {
                    TextButton(onClick = { renameStack = openStack }) { Text("Rename") }
                    TextButton(onClick = { library.unstack(openStack.id); refreshGallery() }) { Text("Unstack") }
                }
            }
            Text(
                if (openStack == null) "Long-press and drag to reorder. Drop in the center of a drawing or stack to group it."
                else "Long-press and drag to reorder drawings inside this stack.",
                color = Color(0xFF9EA0A6), fontSize = 12.sp,
            )
            Spacer(Modifier.height(20.dp))
            if (visibleItems.isEmpty()) {
                EmptyGallery { creating = true }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(260.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().onGloballyPositioned { gridBounds = it.boundsInRoot() },
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(visibleItems, key = GalleryItem::key) { item ->
                        when (item) {
                            is GalleryDrawing -> DrawingCard(
                                item.drawing,
                                { onOpen(item.drawing) },
                                { deleteCandidate = item.drawing },
                                dragModifier(item),
                                onMoveOut = openStack?.let { stack -> { library.moveOutOfStack(stack.id, item.drawing.id); refreshGallery() } },
                            )
                            is GalleryStack -> StackCard(item, { openStackId = item.id }, dragModifier(item))
                        }
                    }
                    if (openStack == null) item { NewDrawingCard { creating = true } }
                }
            }
        }
    }

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
    BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding()) {
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
private fun EmptyGallery(onNew: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(150.dp).border(1.dp, Color(0xFF555860), RoundedCornerShape(24.dp)).clickable(onClick = onNew), contentAlignment = Alignment.Center) {
            Text("＋", color = Color(0xFFED6A5A), fontSize = 52.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.height(20.dp))
        Text("Start a new drawing", color = Color(0xFFF3F3F0), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text("Your work is saved locally on this device.", color = Color(0xFF9EA0A6), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun DrawingCard(
    drawing: DrawingSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onMoveOut: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen).padding(2.dp)) {
        val image = remember(drawing.thumbnailFile.absolutePath, drawing.modifiedAtMillis) {
            BitmapFactory.decodeFile(drawing.thumbnailFile.absolutePath)?.asImageBitmap()
        }
        val documentAspect = (drawing.widthPx.toFloat() / drawing.heightPx).coerceIn(.125f, 8f)
        Box(Modifier.fillMaxWidth().aspectRatio(documentAspect).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
            image?.let { Image(it, drawing.name, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
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
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun StackCard(stack: GalleryStack, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen).padding(2.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.48f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF292B30)).padding(8.dp),
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
private fun NewDrawingCard(onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.48f).border(1.dp, Color(0xFF555860), RoundedCornerShape(12.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text("＋", color = Color(0xFFF2F2EF), fontSize = 42.sp, fontWeight = FontWeight.Light) }
        Text("New drawing", color = Color(0xFFF3F3F0), fontSize = 16.sp, modifier = Modifier.padding(top = 9.dp))
    }
}

@Composable
private fun NewDrawingDialog(onDismiss: () -> Unit, onCreate: (String, Int, Int) -> Unit) {
    var name by remember { mutableStateOf("Untitled drawing") }
    var width by remember { mutableStateOf("2048") }
    var height by remember { mutableStateOf("2048") }
    val validWidth = width.toIntOrNull()?.takeIf { it in 64..8192 }
    val validHeight = height.toIntOrNull()?.takeIf { it in 64..8192 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New drawing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(width, { width = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Width px") }, singleLine = true)
                    OutlinedTextField(height, { height = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Height px") }, singleLine = true)
                }
            }
        },
        confirmButton = { Button(onClick = { onCreate(name.ifBlank { "Untitled drawing" }, validWidth!!, validHeight!!) }, enabled = validWidth != null && validHeight != null) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
