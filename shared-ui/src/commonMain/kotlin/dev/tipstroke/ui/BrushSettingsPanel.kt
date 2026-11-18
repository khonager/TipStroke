package dev.tipstroke.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.model.*
import kotlin.math.roundToInt

/** Reusable settings surface. Platform hosts own persistence and native preview rendering. */
@Composable
fun BrushSettingsPanel(
    brush: BrushPreset,
    onBrush: (BrushPreset) -> Unit,
    fingerDrawing: Boolean,
    onFingerDrawing: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Brush", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrushPreset.builtIns.forEach { preset ->
                FilterChip(brush.id == preset.id, onClick = { onBrush(preset) }, label = { Text(preset.displayName) })
            }
        }
        PrecisionSlider("Size", "${brush.baseSizePx.roundToInt()} px", brush.baseSizePx, 2f..512f, 1f,
            { onBrush(brush.copy(baseSizePx = it)) }, {}, {})
        PrecisionSlider("Opacity", "${(brush.opacity * 100).roundToInt()}%", brush.opacity, .05f..1f, .01f,
            { onBrush(brush.copy(opacity = it)) }, {}, {})
        if (brush.engine == BrushEngine.AIRBRUSH) {
            PrecisionSlider("Edge hardness", "${(brush.hardness * 100).roundToInt()}%", brush.hardness, 0f..1f, .01f,
                { onBrush(brush.copy(hardness = it)) }, {}, {})
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Draw with finger", Modifier.weight(1f))
            Switch(fingerDrawing, onFingerDrawing)
        }
        Text("Pressure", style = MaterialTheme.typography.titleMedium)
        PrecisionSlider("Light-pressure size", "${(brush.pressureToSize.start * 100).roundToInt()}%",
            brush.pressureToSize.start, .01f..1f, .01f,
            { onBrush(brush.copy(pressureToSize = brush.pressureToSize.copy(start = it))) }, {}, {})
        PrecisionSlider("Light-pressure opacity", "${(brush.pressureToOpacity.start * 100).roundToInt()}%",
            brush.pressureToOpacity.start, .01f..1f, .01f,
            { onBrush(brush.copy(pressureToOpacity = brush.pressureToOpacity.copy(start = it))) }, {}, {})
        if (brush.engine == BrushEngine.PENCIL) {
            Text("Pencil tilt", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PencilTiltMode.entries.forEach { mode ->
                    FilterChip(brush.pencilTiltMode == mode, { onBrush(brush.copy(pencilTiltMode = mode)) },
                        label = { Text(if (mode == PencilTiltMode.GRADUAL) "Gradual" else "Shading switch") })
                }
            }
            PrecisionSlider("Tilted size boost", "${(brush.pencilShadeSize * 100).roundToInt()}%",
                brush.pencilShadeSize, 0f..BrushPreset.MAX_PENCIL_SHADE_SIZE, .1f,
                { onBrush(brush.copy(pencilShadeSize = it)) }, {}, {})
            PrecisionSlider("Side opacity", "${(brush.pencilShadeOpacity * 100).roundToInt()}%",
                brush.pencilShadeOpacity, .2f..1f, .01f,
                { onBrush(brush.copy(pencilShadeOpacity = it)) }, {}, {})
        }
        TextButton(onClick = { onBrush(BrushPreset.builtIns.first { it.id == brush.id }) }) { Text("Reset brush") }
    }
}
