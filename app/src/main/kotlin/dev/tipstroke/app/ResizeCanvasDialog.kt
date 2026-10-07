package dev.tipstroke.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

@Composable
internal fun ResizeCanvasDialog(
    width: Int,
    height: Int,
    onDismiss: () -> Unit,
    onResize: (left: Int, top: Int, right: Int, bottom: Int) -> Unit,
) {
    var left by remember(width, height) { mutableStateOf("0") }
    var top by remember(width, height) { mutableStateOf("0") }
    var right by remember(width, height) { mutableStateOf("0") }
    var bottom by remember(width, height) { mutableStateOf("0") }
    val deltas = listOf(left, top, right, bottom).map(String::toIntOrNull)
    val newWidth = if (deltas[0] != null && deltas[2] != null) width.toLong() + deltas[0]!! + deltas[2]!! else null
    val newHeight = if (deltas[1] != null && deltas[3] != null) height.toLong() + deltas[1]!! + deltas[3]!! else null
    val valid = newWidth != null && newHeight != null &&
        newWidth in MIN_CANVAS_SIDE.toLong()..MAX_CANVAS_SIDE.toLong() &&
        newHeight in MIN_CANVAS_SIDE.toLong()..MAX_CANVAS_SIDE.toLong() && deltas.any { it != null && it != 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resize canvas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add pixels with positive numbers. Crop with negative numbers. Artwork keeps its pixel size.")
                listOf("Left" to left, "Top" to top, "Right" to right, "Bottom" to bottom).forEach { (label, value) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = { input ->
                            if (input.length > 6 || input.any { !it.isDigit() && it != '-' } || input.count { it == '-' } > 1 ||
                                ('-' in input && !input.startsWith('-'))) return@OutlinedTextField
                            when (label) {
                                "Left" -> left = input
                                "Top" -> top = input
                                "Right" -> right = input
                                else -> bottom = input
                            }
                        },
                        label = { Text("$label px") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("${width} × ${height} → ${newWidth ?: "?"} × ${newHeight ?: "?"} px")
                if (deltas.any { it != null && it < 0 }) Text("Raster pixels outside the new canvas are removed. Raster stroke undo history is cleared.")
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onResize(deltas[0]!!, deltas[1]!!, deltas[2]!!, deltas[3]!!)
            }) { Text("Resize") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
