package dev.tipstroke.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.tipstroke.drawing.android.*
import kotlinx.coroutines.delay

@Composable
internal fun ProcessRecordingDialog(surface: DrawingSurface, onDismiss: () -> Unit, onExport: (AnimationExportFormat) -> Unit) {
    var recording by remember { mutableStateOf<ProcessRecording?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val cacheDirectory = LocalContext.current.cacheDir
    LaunchedEffect(surface) {
        surface.getProcessRecording { it.fold({ value -> recording = value }, { failure -> error = failure.message ?: "Could not load recording" }) }
    }
    val preview = remember(recording) { recording?.let { ProcessPreview(it, cacheDirectory) } }
    DisposableEffect(preview) { onDispose { preview?.close() } }
    val requestFrame = remember(preview) { { index: Int, callback: (Result<android.graphics.Bitmap>) -> Unit ->
        preview?.frame(index, callback)
        Unit
    } }
    ProcessRecordingPlayer(recording?.actionCount ?: 0, recording?.bytes ?: 0, recording?.stoppedReason,
        error ?: if (recording?.hasRecoveryGap == true) "Recording resumed from current artwork. Earlier unrecorded edits appear as a jump." else surface.processRecordingStatus(), requestFrame, onDismiss, onExport)
}

@Composable
internal fun ProcessRecordingPlayer(
    actionCount: Int,
    bytes: Long,
    stoppedReason: String?,
    status: String,
    requestFrame: (Int, (Result<android.graphics.Bitmap>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onExport: (AnimationExportFormat) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var displayedIndex by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var rendering by remember { mutableStateOf(false) }
    LaunchedEffect(requestFrame, actionCount, index, playing) {
        if (actionCount == 0) return@LaunchedEffect
        val requestedIndex = index
        val startedAt = SystemClock.uptimeMillis()
        rendering = true
        error = null
        val result = suspendCancellableCoroutine<Result<android.graphics.Bitmap>> { continuation ->
            requestFrame(requestedIndex) { value ->
                if (continuation.isActive) continuation.resume(value) else value.getOrNull()?.recycle()
            }
        }
        rendering = false
        result.fold({ bitmap = it; displayedIndex = requestedIndex }, { error = it.message ?: "Could not render this step"; playing = false })
        if (playing && result.isSuccess) {
            // Rendering time is part of the frame interval, not an extra delay on every step.
            delay((83L - (SystemClock.uptimeMillis() - startedAt)).coerceAtLeast(0L))
            if (index < actionCount - 1) index++ else playing = false
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Drawing progress", style = MaterialTheme.typography.titleLarge)
                Text(stoppedReason ?: status, style = MaterialTheme.typography.bodySmall)
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    bitmap?.let { Image(it.asImageBitmap(), "Drawing progress replay", Modifier.fillMaxSize()) }
                    // Keep loading feedback inside the preview so controls never move between frames.
                    if (rendering && !playing) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (actionCount > 0) {
                    val size = if (bytes < 1024 * 1024) "${"%.1f".format(bytes / 1024.0)} KiB"
                        else "${"%.2f".format(bytes / 1048576.0)} MiB"
                    Text("${(displayedIndex + 1).coerceAtLeast(0)} / ${actionCount} steps · $size", style = MaterialTheme.typography.bodySmall)
                    if (actionCount > 1) Slider(index.toFloat(), { playing = false; index = it.toInt() },
                        valueRange = 0f..(actionCount - 1).toFloat())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { playing = false; index = 0 }, enabled = true) { Text("Restart") }
                        Button(onClick = { if (index == actionCount - 1) index = 0; playing = !playing }, enabled = error == null) {
                            Text(if (playing) "Pause" else "Play")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onExport(AnimationExportFormat.MP4) }, enabled = error == null) { Text("Export MP4") }
                        OutlinedButton(onClick = { onExport(AnimationExportFormat.GIF) }, enabled = error == null) { Text("Export GIF") }
                    }
                    Text("Exports at up to 1280 px, 12 steps per second. Animation and resizing stop recording in this preview version.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
