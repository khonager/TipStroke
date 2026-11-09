package dev.tipstroke.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tipstroke.drawing.android.ExportWork

@Composable
internal fun BoxScope.ExportProgressOverlay() {
    var state by remember { mutableStateOf(ExportWork.state) }
    var dismissed by remember { mutableStateOf<ExportWork.State?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    DisposableEffect(Unit) {
        val listener: (ExportWork.State) -> Unit = { state = it }
        ExportWork.observe(listener)
        onDispose { ExportWork.removeObserver(listener) }
    }
    LaunchedEffect(state.running) {
        if (state.running) dismissed = null
        if (state.running && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    if ((state.running || state.message.isNotBlank()) && state != dismissed) {
        Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp).widthIn(max = 420.dp), tonalElevation = 8.dp) {
            Column(Modifier.padding(12.dp)) {
                Text(state.message)
                if (state.running) {
                    state.progress?.let { progress ->
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}%")
                    } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                } else TextButton(onClick = { dismissed = state }) { Text("Dismiss") }
            }
        }
    }
}
