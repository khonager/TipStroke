package dev.tipstroke.app

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
internal fun BrushLibraryDialog(current: BrushPreset, onUse: (BrushPreset) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val library = remember { BrushLibrary(context) }
    val scope = rememberCoroutineScope()
    var brushes by remember { mutableStateOf<List<LibraryBrush>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LibraryBrush?>(null) }
    var deleting by remember { mutableStateOf<LibraryBrush?>(null) }
    var pendingExport by remember { mutableStateOf<LibraryBrush?>(null) }
    var query by remember { mutableStateOf("") }
    var featured by remember { mutableStateOf(false) }
    LaunchedEffect(library) {
        val result = withContext(Dispatchers.IO) { runCatching { library.load() } }
        result.onSuccess { brushes = it; ready = true }
            .onFailure { message = "Could not load brushes. Your saved library has been preserved." }
    }
    fun save(next: List<LibraryBrush>, after: () -> Unit = {}) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { library.save(next) } }
            busy = false
            result.onSuccess { brushes = next; after() }
                .onFailure { message = it.message ?: "Could not save brushes." }
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
                result.onSuccess { brushes = it; message = "Brush imported as a new copy." }
                    .onFailure { message = "Import failed: ${it.message ?: "Invalid brush file."}" }
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
                message = result.fold({ "Brush exported. Share the .tipbrush file with another TipStroke user." },
                    { "Export failed: ${it.message ?: "Could not write file."}" })
            }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(.94f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Brush library", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!featured, { featured = false }, label = { Text("My brushes") })
                    FilterChip(featured, { featured = true }, label = { Text("Featured") })
                }
                Text(if (featured) "Built-in brushes to make your own. Community publishing and popularity rankings are not connected."
                    else "Create a brush, tune its stroke, and export a file to share.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, label = { Text("Search brushes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(enabled = ready && !busy, onClick = {
                        editing = BrushLibrary.copyOf(LibraryBrush(current.copy(displayName = "My ${current.displayName}".take(60))))
                    }) { Text("Create brush") }
                    TextButton(enabled = ready && !busy, onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import") }
                }
                if (busy || !ready && message == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val shown = (if (featured) BrushPreset.builtIns.map { LibraryBrush(it, "TipStroke") } else brushes)
                    .filter { it.preset.displayName.contains(query, true) || it.author.contains(query, true) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.isEmpty()) item { Text(if (query.isBlank()) "Your brushes will appear here. Create one from the current brush or import a shared file." else "No matching brushes.") }
                    items(shown, key = { it.preset.id.value }) { item ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(item.preset.displayName, style = MaterialTheme.typography.titleMedium)
                                Text("${item.preset.engine.name.lowercase()}${if (item.author.isNotBlank()) " · ${item.author}" else ""}")
                                if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(enabled = !busy, onClick = { onUse(item.preset); onDismiss() }) { Text("Use") }
                                    TextButton(enabled = ready && !busy, onClick = { editing = if (featured) BrushLibrary.copyOf(item) else item }) { Text(if (featured) "Customize" else "Edit") }
                                    TextButton(enabled = !busy, onClick = {
                                        pendingExport = item
                                        exporter.launch("${item.preset.displayName.replace(Regex("[^a-zA-Z0-9 _-]"), "_")}.tipbrush")
                                    }) { Text("Export") }
                                }
                                TextButton(enabled = !busy, onClick = {
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
                                                    clipData = ClipData.newRawUri("TipStroke brush", uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }, "Share brush"))
                                            }.onFailure { message = "No sharing app is available. Use Export instead." }
                                        }.onFailure { message = "Could not prepare brush for sharing." }
                                    }
                                }) { Text("Share") }
                                if (!featured) Row {
                                    TextButton(enabled = ready && !busy, onClick = {
                                        editing = BrushLibrary.copyOf(item).let { it.copy(preset = it.preset.copy(displayName = "${it.preset.displayName} copy".take(60))) }
                                    }) { Text("Duplicate") }
                                    TextButton(enabled = ready && !busy, onClick = { deleting = item }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
                TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Back to canvas") }
            }
        }
    }
    editing?.let { initial ->
        BrushCreatorDialog(initial, busy, message, onSave = { item ->
            val next = if (brushes.any { it.preset.id == item.preset.id }) brushes.map { if (it.preset.id == item.preset.id) item else it } else brushes + item
            save(next) { editing = null; onUse(item.preset); message = "Brush saved and selected." }
        }, onDismiss = { editing = null })
    }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("Delete ${item.preset.displayName}?") },
            text = { Text("This removes the brush from your library. Existing drawings keep their painted strokes.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { save(brushes.filterNot { it.preset.id == item.preset.id }) { deleting = null } }) { Text("Delete") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun BrushCreatorDialog(initial: LibraryBrush, busy: Boolean, message: String?, onSave: (LibraryBrush) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val p = draft.preset
    fun update(preset: BrushPreset) { draft = draft.copy(preset = preset) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(.96f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stroke creator", style = MaterialTheme.typography.headlineSmall)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                AndroidView(factory = { BrushStrokePreviewView(it) }, update = {
                    it.setPreview(p, false, p.opacity, RgbaColor(.25f, .25f, .28f), usePresetSize = true)
                }, modifier = Modifier.fillMaxWidth().height(110.dp).background(Color(0xFFFAFAFA)))
                Text("Native stroke preview · pressure and tilt vary across the sample", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(p.displayName, { update(p.copy(displayName = it.take(60))) }, label = { Text("Brush name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.author, { draft = draft.copy(author = it.take(80)) }, label = { Text("Creator name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.description, { draft = draft.copy(description = it.take(1000)) }, label = { Text("Description / sharing terms") }, modifier = Modifier.fillMaxWidth())
                    Text("Brush engine", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BrushEngine.entries.forEach { engine ->
                            FilterChip(p.engine == engine, { update(p.copy(engine = engine)) }, label = { Text(engine.name.lowercase().replaceFirstChar { it.uppercase() }) })
                        }
                    }
                    CreatorSlider("Size (px)", p.baseSizePx, 2f..180f) { update(p.copy(baseSizePx = it)) }
                    CreatorSlider("Opacity", p.opacity, .05f..1f) { update(p.copy(opacity = it)) }
                    if (p.engine == BrushEngine.AIRBRUSH) CreatorSlider("Edge hardness", p.hardness, 0f..1f) { update(p.copy(hardness = it)) }
                    Text("Pressure → size", style = MaterialTheme.typography.titleSmall)
                    CreatorSlider("Light-pressure size", p.pressureToSize.start, .01f..1f) { update(p.copy(pressureToSize = p.pressureToSize.copy(start = it))) }
                    CreatorSlider("Size response exponent", p.pressureToSize.exponent, .25f..2f) { update(p.copy(pressureToSize = p.pressureToSize.copy(exponent = it))) }
                    Text("Pressure → opacity", style = MaterialTheme.typography.titleSmall)
                    CreatorSlider("Light-pressure opacity", p.pressureToOpacity.start, .01f..1f) { update(p.copy(pressureToOpacity = p.pressureToOpacity.copy(start = it))) }
                    CreatorSlider("Opacity response exponent", p.pressureToOpacity.exponent, .25f..2f) { update(p.copy(pressureToOpacity = p.pressureToOpacity.copy(exponent = it))) }
                    Text("Set a light-pressure value to 1 to disable that pressure response. Lower exponents respond faster to light pressure.", style = MaterialTheme.typography.bodySmall)
                    CreatorSlider("Speed taper", p.speedTaper, 0f..1f) { update(p.copy(speedTaper = it)) }
                    if (p.engine == BrushEngine.PENCIL) {
                        Text("Pencil point, texture and tilt", style = MaterialTheme.typography.titleSmall)
                        CreatorSlider("Point size", p.pencilPointSize, .5f..2f) { update(p.copy(pencilPointSize = it)) }
                        CreatorSlider("Graphite grain", p.pencilGrain, 0f..1f) { update(p.copy(pencilGrain = it)) }
                        Row {
                            PencilTiltMode.entries.forEach { mode ->
                                FilterChip(p.pencilTiltMode == mode, { update(p.copy(pencilTiltMode = mode)) }, label = { Text(if (mode == PencilTiltMode.GRADUAL) "Gradual" else "Shading switch") })
                            }
                        }
                        if (p.pencilTiltMode == PencilTiltMode.GRADUAL) {
                            CreatorSlider("Tilt sensitivity", p.pencilTiltSensitivity, 0f..1f) { update(p.copy(pencilTiltSensitivity = it)) }
                        } else {
                            CreatorSlider("Shading starts (degrees)", Math.toDegrees(p.pencilShadeStartRadians.toDouble()).toFloat(),
                                Math.toDegrees(BrushPreset.MIN_PENCIL_SHADE_START_RADIANS.toDouble()).toFloat()..Math.toDegrees(BrushPreset.MAX_PENCIL_SHADE_START_RADIANS.toDouble()).toFloat()) {
                                update(p.copy(pencilShadeStartRadians = Math.toRadians(it.toDouble()).toFloat().coerceIn(BrushPreset.MIN_PENCIL_SHADE_START_RADIANS, BrushPreset.MAX_PENCIL_SHADE_START_RADIANS)))
                            }
                            CreatorSlider("Transition (degrees)", Math.toDegrees(p.pencilShadeTransitionRadians.toDouble()).toFloat(),
                                Math.toDegrees(BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS.toDouble()).toFloat()..Math.toDegrees(BrushPreset.MAX_PENCIL_SHADE_TRANSITION_RADIANS.toDouble()).toFloat()) {
                                update(p.copy(pencilShadeTransitionRadians = Math.toRadians(it.toDouble()).toFloat().coerceIn(BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS, BrushPreset.MAX_PENCIL_SHADE_TRANSITION_RADIANS)))
                            }
                            Text("Full shading at ${Math.toDegrees(p.pencilShadeEndRadians().toDouble()).roundToInt()}° (capped at 69°).", style = MaterialTheme.typography.bodySmall)
                        }
                        CreatorSlider("Tilted size boost", p.pencilShadeSize, 0f..4f) { update(p.copy(pencilShadeSize = it)) }
                        CreatorSlider("Side opacity", p.pencilShadeOpacity, .2f..1f) { update(p.copy(pencilShadeOpacity = it)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                    Button(enabled = !busy && p.displayName.isNotBlank(), onClick = { onSave(draft.copy(preset = p.copy(displayName = p.displayName.trim()))) }) { Text(if (busy) "Saving…" else "Save and use") }
                }
            }
        }
    }
}

@Composable
private fun CreatorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Column {
        Text("$label · ${"%.2f".format(value)}", style = MaterialTheme.typography.bodyMedium)
        Slider(value, onValue, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}
