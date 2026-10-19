package dev.tipstroke.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.tipstroke.drawing.android.*
import kotlinx.coroutines.delay

@Composable
internal fun ProcessRecordingDialog(surface: DrawingSurface, onDismiss: () -> Unit, onExport: (AnimationExportFormat) -> Unit) {
    var recording by remember { mutableStateOf<ProcessRecording?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var rendering by remember { mutableStateOf(false) }
    LaunchedEffect(surface) {
        surface.getProcessRecording { it.fold({ value -> recording = value }, { failure -> error = failure.message }) }
    }
    val preview = remember(recording) { recording?.let(::ProcessPreview) }
    DisposableEffect(preview) { onDispose { preview?.close() } }
    LaunchedEffect(preview, index) {
        val active = preview ?: return@LaunchedEffect
        rendering = true
        active.frame(index) { result ->
            rendering = false
            result.fold({ bitmap = it }, { error = it.message; playing = false })
        }
    }
    LaunchedEffect(playing, rendering, index) {
        if (playing && !rendering) {
            delay(83)
            if (index < (recording?.actionCount ?: 0) - 1) index++ else playing = false
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Drawing progress", style = MaterialTheme.typography.titleLarge)
                Text(recording?.stoppedReason ?: surface.processRecordingStatus(), style = MaterialTheme.typography.bodySmall)
                bitmap?.let { Image(it.asImageBitmap(), "Drawing progress replay", Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                recording?.let { data ->
                    val size = if (data.bytes < 1024 * 1024) "${"%.1f".format(data.bytes / 1024.0)} KiB"
                        else "${"%.2f".format(data.bytes / 1048576.0)} MiB"
                    Text("${index + 1} / ${data.actionCount} steps · $size", style = MaterialTheme.typography.bodySmall)
                    if (data.actionCount > 1) Slider(index.toFloat(), { playing = false; index = it.toInt() },
                        valueRange = 0f..(data.actionCount - 1).toFloat(), enabled = !rendering)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { playing = false; index = 0 }, enabled = !rendering) { Text("Restart") }
                        Button(onClick = { if (index == data.actionCount - 1) index = 0; playing = !playing }, enabled = error == null) {
                            Text(if (playing) "Pause" else "Play")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onExport(AnimationExportFormat.MP4) }, enabled = error == null) { Text("Export MP4") }
                        OutlinedButton(onClick = { onExport(AnimationExportFormat.GIF) }, enabled = error == null) { Text("Export GIF") }
                    }
                    Text("Exports at up to 1280 px, 12 steps per second. Image layers, animation and resizing stop recording in this preview version.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
