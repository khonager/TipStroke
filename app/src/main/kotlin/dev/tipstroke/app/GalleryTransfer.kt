package dev.tipstroke.app

import android.content.Intent
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.tipstroke.drawing.android.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GalleryTransfer(library: DrawingLibrary, initialIds: List<String>?, onDismiss: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projectIo = remember { ProjectTransfer.ioExecutor.asCoroutineDispatcher() }
    var drawings by remember { mutableStateOf<List<DrawingSummary>>(emptyList()) }
    LaunchedEffect(library) { drawings = withContext(projectIo) { library.list() } }
    var selected by remember(initialIds) { mutableStateOf(initialIds?.toSet() ?: emptySet()) }
    var format by remember { mutableStateOf("TipStroke") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<List<String>, String>?>(null) }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pending
        pending = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && request != null) {
            busy = true
            scope.launch {
                val outcome = withContext(projectIo) { runCatching {
                    if (request.second == "TipStroke") {
                        ProjectTransfer.exportProjects(library, request.first, requireNotNull(context.contentResolver.openOutputStream(uri, "w")))
                    } else {
                        ProjectTransfer.exportMedia(context, library, request.first, uri,
                            ExportFormat.entries.firstOrNull { it.name == request.second },
                            AnimationExportFormat.entries.firstOrNull { it.name == request.second })
                    }
                } }
                busy = false
                message = outcome.fold({ "Export saved." }, { it.message ?: "Export failed." })
            }
        }
    }
    val source = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val outcome = withContext(projectIo) { runCatching {
                    ProjectTransfer.importProjects(library, requireNotNull(context.contentResolver.openInputStream(uri)))
                } }
                busy = false
                outcome.onSuccess { onImported() }
                message = outcome.fold({ "Imported $it drawings." }, { it.message ?: "Import failed." })
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initialIds == null) "Import drawings" else "Export drawings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (initialIds == null) {
                    Text("Open a .tipstroke file to add editable drawings, layers, and animation to this gallery. Existing drawings are kept.")
                } else {
                    TextButton(onClick = { selected = drawings.map { it.id }.toSet() }, enabled = !busy) { Text("Select whole gallery") }
                    drawings.forEach { drawing ->
                        Row {
                            Checkbox(drawing.id in selected, { checked -> selected = if (checked) selected + drawing.id else selected - drawing.id }, enabled = !busy)
                            TextButton(onClick = { selected = if (drawing.id in selected) selected - drawing.id else selected + drawing.id }, enabled = !busy) { Text(drawing.name) }
                        }
                    }
                    Text("${selected.size} selected • Format")
                    (listOf("TipStroke") + ExportFormat.entries.map { it.name } + AnimationExportFormat.entries.map { it.name }).forEach { option ->
                        Row {
                            RadioButton(format == option, { format = option }, enabled = !busy)
                            TextButton(onClick = { format = option }, enabled = !busy) { Text(if (option == "TipStroke") "TipStroke (editable .tipstroke)" else option.replace('_', ' ')) }
                        }
                    }
                    Text(if (format == "TipStroke") "Preserves layers, animation, image sources, and drawing progress."
                        else "Images export the selected frame. Animation formats require every selected drawing to have animation. Multiple files are saved together as a ZIP.")
                }
                if (busy) { LinearProgressIndicator(); Text("Working…") }
                message?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (initialIds == null || drawings.any { it.id in selected }), onClick = {
                message = null
                if (initialIds == null) source.launch(arrayOf("*/*")) else {
                    val ids = drawings.filter { it.id in selected }.map { it.id }
                    pending = ids to format
                    val image = ExportFormat.entries.firstOrNull { it.name == format }
                    val animation = AnimationExportFormat.entries.firstOrNull { it.name == format }
                    val extension = if (format == "TipStroke") "tipstroke" else if (ids.size > 1) "zip" else image?.extension ?: animation!!.extension
                    val mime = if (format == "TipStroke") ProjectTransfer.MIME else if (ids.size > 1) "application/zip" else image?.mimeType ?: animation!!.mimeType
                    destination.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = mime
                        putExtra(Intent.EXTRA_TITLE, "TipStroke.$extension")
                    })
                }
            }) { Text(if (initialIds == null) "Choose file" else "Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
}
