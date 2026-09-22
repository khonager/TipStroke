package dev.tipstroke.app

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.pencilFullTiltRadians
import dev.tipstroke.core.model.pencilTiltSensitivityForFullAngle
import kotlin.math.PI
import kotlin.math.roundToInt

@Composable
internal fun BrushStudioDialog(
    toolName: String,
    supportsHardness: Boolean,
    hardness: Float,
    pressureSize: Boolean,
    pressureOpacity: Boolean,
    speedTaper: Boolean,
    supportsPencilTilt: Boolean,
    pencilPointSize: Float,
    pencilTiltSensitivity: Float,
    pencilShadeSize: Float,
    pencilShadeOpacity: Float,
    pencilGrain: Float,
    onHardness: (Float) -> Unit,
    onPressureSize: (Boolean) -> Unit,
    onPressureOpacity: (Boolean) -> Unit,
    onSpeedTaper: (Boolean) -> Unit,
    onPencilPointSize: (Float) -> Unit,
    onPencilTiltSensitivity: (Float) -> Unit,
    onPencilShadeSize: (Float) -> Unit,
    onPencilShadeOpacity: (Float) -> Unit,
    onPencilGrain: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust $toolName") },
        text = {
            Column(
                Modifier.heightIn(max = 590.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                if (supportsPencilTilt) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("Pencil point and shading", style = MaterialTheme.typography.titleSmall)
                    StudioSlider("Point size", "${(pencilPointSize * 100).roundToInt()}%", pencilPointSize, .5f..2f, onPencilPointSize)
                    StudioSlider("Tilt sensitivity", "${(pencilTiltSensitivity * 100).roundToInt()}%", pencilTiltSensitivity, 0f..1f, onPencilTiltSensitivity)
                    StudioSlider("Side width", "${(pencilShadeSize * 100).roundToInt()}%", pencilShadeSize, .4f..1.6f, onPencilShadeSize)
                    StudioSlider("Side opacity", "${(pencilShadeOpacity * 100).roundToInt()}%", pencilShadeOpacity, .2f..1f, onPencilShadeOpacity)
                    StudioSlider("Graphite grain", "${(pencilGrain * 100).roundToInt()}%", pencilGrain, 0f..1f, onPencilGrain)
                    TiltCalibrationPad(
                        pointSize = pencilPointSize,
                        sensitivity = pencilTiltSensitivity,
                        shadeSize = pencilShadeSize,
                        shadeOpacity = pencilShadeOpacity,
                        grain = pencilGrain,
                        onSensitivity = onPencilTiltSensitivity,
                    )
                }
                Text(
                    if (supportsPencilTilt) {
                        "Size and opacity stay on the canvas. Pencil tilt settings are remembered locally."
                    } else {
                        "Size and opacity stay on the canvas."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onReset) { Text("Reset") } },
    )
}

@Composable
private fun StudioSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.bodySmall)
        }
        Slider(value, onValue, valueRange = range)
    }
}

@Composable
private fun TiltCalibrationPad(
    pointSize: Float,
    sensitivity: Float,
    shadeSize: Float,
    shadeOpacity: Float,
    grain: Float,
    onSensitivity: (Float) -> Unit,
) {
    var measuredTilt by remember { mutableFloatStateOf(0f) }
    var hasStylusSample by remember { mutableStateOf(false) }
    val samples = remember { mutableStateListOf<PencilPreviewSample>() }
    val fullTilt = remember(sensitivity) {
        BrushPreset.Pencil.copy(pencilTiltSensitivity = sensitivity).pencilFullTiltRadians()
    }
    val measuredDegrees = Math.toDegrees(measuredTilt.toDouble()).roundToInt()
    val fullDegrees = Math.toDegrees(fullTilt.toDouble()).roundToInt()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Tilt calibration", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
        Text(
            "Draw in the square while holding the pencil at your preferred shading angle.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(148.dp)
                .background(Color(0xFFFAFAFA), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF777A82), RoundedCornerShape(14.dp))
                .semantics { contentDescription = "Pencil tilt calibration pad" }
                .pointerInteropFilter { event ->
                    val index = (0 until event.pointerCount).firstOrNull { pointerIndex ->
                        event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS ||
                            event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER
                    }
                    if (index != null) {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) samples.clear()
                        for (historyIndex in 0 until event.historySize) {
                            samples.add(
                                PencilPreviewSample(
                                    Offset(
                                        event.getHistoricalX(index, historyIndex),
                                        event.getHistoricalY(index, historyIndex),
                                    ),
                                    event.getHistoricalAxisValue(
                                        MotionEvent.AXIS_TILT,
                                        index,
                                        historyIndex,
                                    ).coerceIn(0f, (PI / 2).toFloat()),
                                ),
                            )
                        }
                        measuredTilt = event.getAxisValue(MotionEvent.AXIS_TILT, index)
                            .coerceIn(0f, (PI / 2).toFloat())
                        samples.add(PencilPreviewSample(Offset(event.getX(index), event.getY(index)), measuredTilt))
                        while (samples.size > MAX_PREVIEW_SAMPLES) samples.removeAt(0)
                        hasStylusSample = true
                    }
                    index != null
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                samples.zipWithNext().forEachIndexed { sampleIndex, (start, end) ->
                    val response = (end.tilt / fullTilt.coerceAtLeast(.01f)).coerceIn(0f, 1f)
                    val pointWidth = 2.5f * pointSize
                    val shadeWidth = 30f * shadeSize
                    val strokeWidth = pointWidth + (shadeWidth - pointWidth) * response
                    val alpha = .9f + (shadeOpacity - .9f) * response
                    drawLine(
                        color = Color(0xFF3F4147).copy(alpha = alpha),
                        start = start.position,
                        end = end.position,
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                    if (grain > 0f && response > 0f) {
                        val delta = end.position - start.position
                        val length = delta.getDistance().coerceAtLeast(1f)
                        val normal = Offset(-delta.y / length, delta.x / length)
                        val seed = previewNoise(sampleIndex)
                        val along = .2f + .6f * previewNoise(sampleIndex + 31)
                        val across = (seed - .5f) * strokeWidth * .72f
                        drawCircle(
                            color = Color(0xFFFAFAFA).copy(alpha = grain * response * .72f),
                            radius = (.5f + previewNoise(sampleIndex + 67) * 1.3f) * grain,
                            center = start.position + delta * along + normal * across,
                        )
                    }
                }
                if (samples.size == 1) {
                    drawCircle(
                        color = Color(0xFF3F4147),
                        radius = 1.25f * pointSize,
                        center = samples.first().position,
                    )
                }
            }
            if (!hasStylusSample) {
                Text(
                    "Touch with pencil",
                    color = Color(0xFF202125),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Text(
            if (hasStylusSample) {
                "$measuredDegrees° reported · full shade at $fullDegrees°"
            } else {
                "Current full-shade angle: $fullDegrees°"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            enabled = hasStylusSample && measuredTilt >= BrushPreset.MIN_PENCIL_FULL_TILT_RADIANS,
            onClick = { onSensitivity(pencilTiltSensitivityForFullAngle(measuredTilt)) },
        ) {
            Text(if (hasStylusSample) "Use $measuredDegrees° as full shade" else "Use measured angle")
        }
    }
}

private data class PencilPreviewSample(
    val position: Offset,
    val tilt: Float,
)

private fun previewNoise(seed: Int): Float {
    val mixed = seed * 1_103_515_245 + 12_345
    return ((mixed ushr 8) and 0xFFFF) / 65_535f
}

private const val MAX_PREVIEW_SAMPLES = 400

@Composable
private fun StudioSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange)
    }
}
