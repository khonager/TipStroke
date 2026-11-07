package dev.tipstroke.app

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tipstroke.core.model.*
import dev.tipstroke.drawing.android.BrushStrokePreviewView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun BrushLibraryDialog(
    current: BrushPreset, onUse: (BrushPreset) -> Unit, onDismiss: () -> Unit,
    favoriteIds: List<String> = emptyList(), favoriteCapacity: Int = 3, editBrushId: BrushId? = null,
    onFavorites: (List<String>) -> Unit = {}, onLibraryChanged: (List<LibraryBrush>) -> Unit = {},
) {
    val context = LocalContext.current
    var searchOpen by remember { mutableStateOf(false) }
    val library = remember { BrushLibrary(context) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    var brushes by remember { mutableStateOf<List<LibraryBrush>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LibraryBrush?>(null) }
    var deleting by remember { mutableStateOf<LibraryBrush?>(null) }
    var pendingExport by remember { mutableStateOf<LibraryBrush?>(null) }
    var query by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("All tips") }
    LaunchedEffect(library) {
        val result = withContext(Dispatchers.IO) { runCatching { library.load() } }
        result.onSuccess {
            brushes = it; ready = true; onLibraryChanged(it)
            if (editBrushId != null) editing = it.firstOrNull { entry -> entry.preset.id == editBrushId } ?: LibraryBrush(current)
        }
            .onFailure { message = "Could not load tips. Your saved library has been preserved." }
    }
    fun save(next: List<LibraryBrush>, after: () -> Unit = {}) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { library.save(next) } }
            busy = false
            result.onSuccess { brushes = next; onLibraryChanged(next); after() }
                .onFailure { message = it.message ?: "Could not save tips." }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val imported = BrushLibrary.copyOf(BrushPackage.read(requireNotNull(context.contentResolver.openInputStream(uri))))
                    val next = brushes + imported
                    library.save(next)
                    next
                } }
                busy = false
                result.onSuccess { brushes = it; onLibraryChanged(it); message = "Tip imported as a new copy." }
                    .onFailure { message = "Import failed: ${it.message ?: "Invalid tip file."}" }
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val item = pendingExport
        pendingExport = null
        if (uri != null && item != null) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use {
                        it.write(BrushPackage.encode(item).toByteArray(Charsets.UTF_8))
                    }
                } }
                busy = false
                message = result.fold({ "Tip exported. Share the .tipbrush file with another TipStroke user." },
                    { "Export failed: ${it.message ?: "Could not write file."}" })
            }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 1040.dp).fillMaxWidth().fillMaxHeight(.98f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tip library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TipAction("Create tip", Icons.Outlined.Add, enabled = ready && !busy, onClick = {
                        editing = BrushLibrary.copyOf(LibraryBrush(BrushPreset.Blank))
                    })
                    TipAction("Search", Icons.Outlined.Search, onClick = {
                        searchOpen = !searchOpen
                        scope.launch { gridState.animateScrollToItem(0) }
                    })
                    TipAction("Back to canvas", Icons.Outlined.Close, enabled = !busy, onClick = onDismiss)
                }
                val all = BrushPreset.builtIns.map { LibraryBrush(it, "TipStroke") } + brushes
                val shown = (when (section) {
                    "My tips" -> brushes
                    "Favorites" -> favoriteIds.mapNotNull { id -> all.firstOrNull { it.preset.id.value == id } }
                    else -> all
                }).filter { it.preset.displayName.contains(query, true) || it.author.contains(query, true) }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(280.dp), state = gridState, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TipAction("Import", Icons.Outlined.FileOpen, enabled = ready && !busy, onClick = { importer.launch(arrayOf("*/*")) })
                                listOf("All tips", "My tips", "Favorites").forEach { name ->
                                    FilterChip(section == name, { section = name }, label = { Text(name) })
                                }
                            }
                            if (searchOpen) OutlinedTextField(query, { query = it }, label = { Text("Search tips") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (section == "Favorites") Text("${favoriteIds.size.coerceAtMost(favoriteCapacity)} / $favoriteCapacity canvas slots. Extra pins return when there is more room.", style = MaterialTheme.typography.bodySmall)
                            if (busy || !ready && message == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(if (query.isBlank()) "Your tips will appear here. Create a new shape-and-grain tip or import a shared file." else "No matching tips.") }
                    items(shown, key = { it.preset.id.value }) { item ->
                        val featured = item.preset.id in BrushPreset.builtIns.map { it.id }
                        val favorite = item.preset.id.value in favoriteIds
                        var moreOpen by remember { mutableStateOf(false) }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.preset.displayName, style = MaterialTheme.typography.titleMedium)
                                        Text(if (item.preset.id == current.id) "Selected" else if (item.author.isNotBlank()) item.author else "Custom tip",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    TipAction(if (favorite) "Unpin favorite" else if (favoriteIds.size >= favoriteCapacity) "Favorite bar is full" else "Pin favorite", if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, enabled = !busy && (favorite || favoriteIds.size < favoriteCapacity), onClick = {
                                        onFavorites(if (favorite) favoriteIds - item.preset.id.value else favoriteIds + item.preset.id.value)
                                    })
                                }
                                TipStrokeThumbnail(item.preset, Modifier.fillMaxWidth().height(80.dp).background(Color(0xFFFAFAFA))
                                    .clickable(enabled = !busy, onClickLabel = "Use tip") { onUse(item.preset); onDismiss() }
                                    .semantics { contentDescription = "Select ${item.preset.displayName}" })
                                Row {
                                    TipAction("Use", Icons.Outlined.Check, enabled = !busy, onClick = { onUse(item.preset); onDismiss() })
                                    TipAction(if (featured) "Customize" else "Edit", Icons.Outlined.Edit, enabled = ready && !busy, onClick = { editing = if (featured) BrushLibrary.copyOf(item) else item })
                                    Spacer(Modifier.weight(1f))
                                    TipAction("More actions for ${item.preset.displayName}", Icons.Outlined.MoreHoriz, onClick = { moreOpen = !moreOpen })
                                }
                                if (moreOpen) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall)
                                    if (favorite && section == "Favorites") TipAction("Move to front", Icons.Outlined.VerticalAlignTop, onClick = {
                                        onFavorites(listOf(item.preset.id.value) + favoriteIds.filterNot { it == item.preset.id.value })
                                    })
                                    Row {
                                        TipAction("Export", Icons.Outlined.FileDownload, enabled = !busy, onClick = {
                                            pendingExport = item
                                            exporter.launch("${item.preset.displayName.replace(Regex("[^a-zA-Z0-9 _-]"), "_")}.tipbrush")
                                        })
                                        TipAction("Share", Icons.Outlined.Share, enabled = !busy, onClick = {
                                            busy = true
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) { runCatching {
                                                    val directory = File(context.cacheDir, "shared-brushes").apply { mkdirs() }
                                                    // Keep a bounded cache without revoking recent shares immediately.
                                                    directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(19)?.forEach { it.delete() }
                                                    val file = File.createTempFile("brush-", ".tipbrush", directory)
                                                    file.writeText(BrushPackage.encode(item))
                                                    FileProvider.getUriForFile(context, "${context.packageName}.brush-files", file)
                                                } }
                                                busy = false
                                                result.onSuccess { uri ->
                                                    runCatching {
                                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                                            type = "application/json"
                                                            putExtra(Intent.EXTRA_STREAM, uri)
                                                            putExtra(Intent.EXTRA_SUBJECT, item.preset.displayName)
                                                            clipData = ClipData.newRawUri("TipStroke tip", uri)
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }, "Share tip"))
                                                    }.onFailure { message = "No sharing app is available. Use Export instead." }
                                                }.onFailure { message = "Could not prepare tip for sharing." }
                                            }
                                        })
                                    }
                                    if (!featured) Row {
                                        TipAction("Duplicate", Icons.Outlined.ContentCopy, enabled = ready && !busy, onClick = {
                                            editing = BrushLibrary.copyOf(item).let { it.copy(preset = it.preset.copy(displayName = "${it.preset.displayName} copy".take(60))) }
                                        })
                                        TipAction("Delete", Icons.Outlined.DeleteOutline, enabled = ready && !busy, onClick = { deleting = item })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { initial ->
        BrushCreatorDialog(initial, busy, message, onSave = { item ->
            val next = if (brushes.any { it.preset.id == item.preset.id }) brushes.map { if (it.preset.id == item.preset.id) item else it } else brushes + item
            save(next) { editing = null; section = "My tips"; onUse(item.preset); message = "Tip saved and selected." }
        }, onDismiss = { editing = null })
    }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("Delete ${item.preset.displayName}?") },
            text = { Text("This removes the tip from your library. Existing drawings keep their painted strokes.") },
            confirmButton = { TipAction("Delete", Icons.Outlined.DeleteOutline, enabled = !busy, onClick = { save(brushes.filterNot { it.preset.id == item.preset.id }) { deleting = null; onFavorites(favoriteIds - item.preset.id.value) } }) },
            dismissButton = { TipAction("Cancel", Icons.Outlined.Close, enabled = !busy, onClick = { deleting = null }) })
    }
}

@Composable
internal fun LegacyBrushCreatorDialog(initial: LibraryBrush, busy: Boolean, message: String?, onSave: (LibraryBrush) -> Unit, onDismiss: () -> Unit, onConvert: () -> Unit) {
    val previewColor = LocalTipPreviewColor.current
    var draft by remember(initial) { mutableStateOf(initial) }
    val p = draft.preset
    fun update(preset: BrushPreset) { draft = draft.copy(preset = preset) }
    @Composable fun CreatorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, actualSize: Boolean = false, at: (Float) -> BrushPreset) {
        TipPreviewSlider(label, value, range, at, actualSize = actualSize) { update(at(it)) }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(.96f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tip Studio", style = MaterialTheme.typography.headlineSmall)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                AndroidView(factory = { BrushStrokePreviewView(it) }, update = {
                    it.setPreview(p, false, p.opacity, previewColor, usePresetSize = true)
                }, modifier = Modifier.fillMaxWidth().height(110.dp).background(tipPreviewBackground(previewColor)))
                Text("Native stroke preview · pressure and tilt vary across the sample", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(p.displayName, { update(p.copy(displayName = it.take(60))) }, label = { Text("Tip name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.author, { draft = draft.copy(author = it.take(80)) }, label = { Text("Creator name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.description, { draft = draft.copy(description = it.take(1000)) }, label = { Text("Description / sharing terms") }, modifier = Modifier.fillMaxWidth())
                    TipAction("Design with shape & grain", Icons.Outlined.Texture, onClick = onConvert)
                    Text("Tip engine", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BrushEngine.entries.forEach { engine ->
                            FilterChip(p.engine == engine, { update(p.copy(engine = engine)) }, label = { Text(engine.name.lowercase().replaceFirstChar { it.uppercase() }) })
                        }
                    }
                    CreatorSlider("Size (px)", p.baseSizePx, 2f..180f, actualSize = true) { p.copy(baseSizePx = it) }
                    CreatorSlider("Opacity", p.opacity, .05f..1f) { p.copy(opacity = it) }
                    if (p.engine == BrushEngine.AIRBRUSH) CreatorSlider("Edge hardness", p.hardness, 0f..1f) { p.copy(hardness = it) }
                    Text("Pressure → size", style = MaterialTheme.typography.titleSmall)
                    CreatorSlider("Light-pressure size", p.pressureToSize.start, .01f..1f) { p.copy(pressureToSize = p.pressureToSize.copy(start = it)) }
                    CreatorSlider("Size response exponent", p.pressureToSize.exponent, .25f..2f) { p.copy(pressureToSize = p.pressureToSize.copy(exponent = it)) }
                    Text("Pressure → opacity", style = MaterialTheme.typography.titleSmall)
                    CreatorSlider("Light-pressure opacity", p.pressureToOpacity.start, .01f..1f) { p.copy(pressureToOpacity = p.pressureToOpacity.copy(start = it)) }
                    CreatorSlider("Opacity response exponent", p.pressureToOpacity.exponent, .25f..2f) { p.copy(pressureToOpacity = p.pressureToOpacity.copy(exponent = it)) }
                    Text("Set a light-pressure value to 1 to disable that pressure response. Lower exponents respond faster to light pressure.", style = MaterialTheme.typography.bodySmall)
                    CreatorSlider("Speed taper", p.speedTaper, 0f..1f) { p.copy(speedTaper = it) }
                    if (p.engine == BrushEngine.PENCIL) {
                        Text("Pencil point, texture and tilt", style = MaterialTheme.typography.titleSmall)
                        CreatorSlider("Point size", p.pencilPointSize, .5f..2f) { p.copy(pencilPointSize = it) }
                        CreatorSlider("Graphite grain", p.pencilGrain, 0f..1f) { p.copy(pencilGrain = it) }
                        Row {
                            PencilTiltMode.entries.forEach { mode ->
                                FilterChip(p.pencilTiltMode == mode, { update(p.copy(pencilTiltMode = mode)) }, label = { Text(if (mode == PencilTiltMode.GRADUAL) "Gradual" else "Shading switch") })
                            }
                        }
                        if (p.pencilTiltMode == PencilTiltMode.GRADUAL) {
                            CreatorSlider("Tilt sensitivity", p.pencilTiltSensitivity, 0f..1f) { p.copy(pencilTiltSensitivity = it) }
                        } else {
                            CreatorSlider("Shading starts (degrees)", Math.toDegrees(p.pencilShadeStartRadians.toDouble()).toFloat(),
                                Math.toDegrees(BrushPreset.MIN_PENCIL_SHADE_START_RADIANS.toDouble()).toFloat()..Math.toDegrees(BrushPreset.MAX_PENCIL_SHADE_START_RADIANS.toDouble()).toFloat()) {
                                p.copy(pencilShadeStartRadians = Math.toRadians(it.toDouble()).toFloat().coerceIn(BrushPreset.MIN_PENCIL_SHADE_START_RADIANS, BrushPreset.MAX_PENCIL_SHADE_START_RADIANS))
                            }
                            CreatorSlider("Transition (degrees)", Math.toDegrees(p.pencilShadeTransitionRadians.toDouble()).toFloat(),
                                Math.toDegrees(BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS.toDouble()).toFloat()..Math.toDegrees(BrushPreset.MAX_PENCIL_SHADE_TRANSITION_RADIANS.toDouble()).toFloat()) {
                                p.copy(pencilShadeTransitionRadians = Math.toRadians(it.toDouble()).toFloat().coerceIn(BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS, BrushPreset.MAX_PENCIL_SHADE_TRANSITION_RADIANS))
                            }
                            Text("Full shading at ${Math.toDegrees(p.pencilShadeEndRadians().toDouble()).roundToInt()}° (capped at 69°).", style = MaterialTheme.typography.bodySmall)
                        }
                        CreatorSlider("Tilted size boost", p.pencilShadeSize, 0f..4f) { p.copy(pencilShadeSize = it) }
                        CreatorSlider("Side opacity", p.pencilShadeOpacity, .2f..1f) { p.copy(pencilShadeOpacity = it) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TipAction("Cancel", Icons.Outlined.Close, enabled = !busy, onClick = onDismiss)
                    TipAction("Save and use", Icons.Outlined.Check, enabled = !busy && p.displayName.isNotBlank(), onClick = { onSave(draft.copy(preset = p.copy(displayName = p.displayName.trim()))) })
                }
            }
        }
    }
}
