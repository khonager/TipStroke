package dev.tipstroke.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tipstroke.drawing.android.ExportWork

@Composable
internal fun BoxScope.ExportProgressOverlay() {
    val accessibility = LocalAccessibilityManager.current
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
    LaunchedEffect(state) {
        if (!state.running && state.message.isNotBlank()) {
            val timeout = if (state.message == "Export saved.") 1_800L else 6_000L
            delay(accessibility?.calculateRecommendedTimeoutMillis(timeout, containsText = true) ?: timeout)
            dismissed = state
        }
    }
    if ((state.running || state.message.isNotBlank()) && state != dismissed) {
        Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 68.dp, start = 12.dp, end = 12.dp)
            .widthIn(max = 300.dp).clickable(enabled = !state.running) { dismissed = state },
            color = Color(0xF2202125), contentColor = Color(0xFFE7E7E5),
            shape = RoundedCornerShape(14.dp), shadowElevation = 4.dp) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.message + if (state.running && state.progress != null) " ${(state.progress!! * 100).toInt()}%" else "", fontSize = 12.sp)
                if (state.running) {
                    state.progress?.let { progress ->
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(220.dp).height(3.dp))
                    } ?: LinearProgressIndicator(Modifier.width(220.dp).height(3.dp))
                }
            }
        }
    }
}
