package dev.tipstroke.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun BrushStudioDialog(
    toolName: String,
    supportsHardness: Boolean,
    hardness: Float,
    pressureSize: Boolean,
    pressureOpacity: Boolean,
    speedTaper: Boolean,
    onHardness: (Float) -> Unit,
    onPressureSize: (Boolean) -> Unit,
    onPressureOpacity: (Boolean) -> Unit,
    onSpeedTaper: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust $toolName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (supportsHardness) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Edge hardness")
                        Text("${(hardness * 100).roundToInt()}%")
                    }
                    Slider(hardness, onHardness, valueRange = 0f..1f)
                }
                StudioSwitch("Pressure changes size", pressureSize, onPressureSize)
                StudioSwitch("Pressure changes opacity", pressureOpacity, onPressureOpacity)
                StudioSwitch("Faster strokes taper", speedTaper, onSpeedTaper)
                Text("These are the useful everyday adjustments. Size and opacity stay on the canvas.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onReset) { Text("Reset") } },
    )
}

@Composable
private fun StudioSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange)
    }
}
