package dev.tipstroke.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tipstroke.drawing.android.LocalDiagnostics

@Composable
internal fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf("Loading diagnostics…") }
    var busy by remember { mutableStateOf(true) }
    fun refresh() {
        busy = true
        LocalDiagnostics.readReport { report = it; busy = false }
    }
    LaunchedEffect(Unit) { refresh() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crash and debug log") },
        text = {
            Column {
                Text("Stored only on this device. Nothing is sent automatically. Copy the log to include it in a bug report.",
                    fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = ::refresh, enabled = !busy) { Text("Refresh") }
                    TextButton(onClick = {
                        busy = true
                        LocalDiagnostics.clear { refresh() }
                    }, enabled = !busy) { Text("Clear log") }
                }
                SelectionContainer {
                    Text(report, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("TipStroke diagnostics", report))
            }, enabled = !busy) { Text("Copy log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
