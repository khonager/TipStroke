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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.tipstroke.core.model.PressureCurve
import dev.tipstroke.core.model.RgbaColor
import dev.tipstroke.core.model.BrushPreset
import dev.tipstroke.core.model.BrushEngine
import dev.tipstroke.core.model.PencilTiltMode
import dev.tipstroke.drawing.android.BrushStrokePreviewView
import dev.tipstroke.core.model.pencilFullTiltRadians
import dev.tipstroke.core.model.pencilShadeEndRadians
import dev.tipstroke.core.model.pencilTiltResponse
import dev.tipstroke.core.model.pencilTiltSensitivityForFullAngle
import kotlin.math.PI
import kotlin.math.roundToInt

@Composable
internal fun BrushStudioDialog(
    toolName: String,
    brush: BrushPreset,
    erasing: Boolean,
    opacity: Float,
    supportsHardness: Boolean,
    hardness: Float,
    pressureSize: Boolean,
    pressureOpacity: Boolean,
    speedTaper: Boolean,
    pressureSizeStart: Float,
    pressureSizeExponent: Float,
    pressureOpacityStart: Float,
    pressureOpacityExponent: Float,
    speedTaperAmount: Float,
    supportsPencilTilt: Boolean,
    pencilPointSize: Float,
    pencilTiltSensitivity: Float,
    pencilShadeSize: Float,
    pencilShadeOpacity: Float,
    pencilGrain: Float,
    pencilTiltMode: PencilTiltMode,
    pencilShadeStartRadians: Float,
    pencilShadeTransitionRadians: Float,
    onHardness: (Float) -> Unit,
    onPressureSize: (Boolean) -> Unit,
    onPressureOpacity: (Boolean) -> Unit,
    onSpeedTaper: (Boolean) -> Unit,
    onPressureSizeStart: (Float) -> Unit,
    onPressureSizeExponent: (Float) -> Unit,
    onPressureOpacityStart: (Float) -> Unit,
    onPressureOpacityExponent: (Float) -> Unit,
    onSpeedTaperAmount: (Float) -> Unit,
    onPencilPointSize: (Float) -> Unit,
    onPencilTiltSensitivity: (Float) -> Unit,
    onPencilShadeSize: (Float) -> Unit,
    onPencilShadeOpacity: (Float) -> Unit,
    onPencilGrain: (Float) -> Unit,
    onPencilTiltMode: (PencilTiltMode) -> Unit,
    onPencilShadeStartRadians: (Float) -> Unit,
    onPencilShadeTransitionRadians: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust $toolName") },
        text = {
            Column(
                Modifier.heightIn(max = 590.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrushTipPreview(
                    toolName = toolName,
                    brush = brush,
                    erasing = erasing,
                    opacity = opacity,
                    hardness = hardness,
                    pressureSize = pressureSize,
                    pressureOpacity = pressureOpacity,
                    speedTaper = speedTaper,
                    pressureSizeStart = pressureSizeStart,
                    pressureSizeExponent = pressureSizeExponent,
                    pressureOpacityStart = pressureOpacityStart,
                    pressureOpacityExponent = pressureOpacityExponent,
                    speedTaperAmount = speedTaperAmount,
                    pencilPointSize = pencilPointSize,
                    pencilTiltSensitivity = pencilTiltSensitivity,
                    pencilShadeSize = pencilShadeSize,
                    pencilShadeOpacity = pencilShadeOpacity,
                    pencilGrain = pencilGrain,
                    pencilTiltMode = pencilTiltMode,
                    pencilShadeStartRadians = pencilShadeStartRadians,
                    pencilShadeTransitionRadians = pencilShadeTransitionRadians,
                )
                HorizontalDivider()
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (supportsHardness) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (!erasing && brush.engine == BrushEngine.AIRBRUSH) "Spray edge hardness" else "Edge hardness")
                            Text("${(hardness * 100).roundToInt()}%")
                        }
                        Slider(hardness, onHardness, valueRange = 0f..1f)
                    }
                    StudioSwitch("Pressure changes size", pressureSize, onPressureSize)
                    StudioSwitch("Pressure changes opacity", pressureOpacity, onPressureOpacity)
                    StudioSwitch("Faster strokes taper", speedTaper, onSpeedTaper)
                    if (!erasing && brush.engine != BrushEngine.PENCIL) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        val airbrush = brush.engine == BrushEngine.AIRBRUSH
                        Text(
                            if (airbrush) "Spray dynamics" else "Ink dynamics",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (pressureSize) {
                            StudioSlider(
                                if (airbrush) "Minimum spray size" else "Light-pressure size",
                                "${(pressureSizeStart * 100).roundToInt()}%",
                                pressureSizeStart,
                                .01f..1f,
                                onPressureSizeStart,
                            )
                            PressureResponseChips("Size response", pressureSizeExponent, onPressureSizeExponent)
                        }
                        if (pressureOpacity) {
                            StudioSlider(
                                if (airbrush) "Minimum flow" else "Light-pressure opacity",
                                "${(pressureOpacityStart * 100).roundToInt()}%",
                                pressureOpacityStart,
                                .01f..1f,
                                onPressureOpacityStart,
                            )
                            PressureResponseChips(
                                if (airbrush) "Flow response" else "Opacity response",
                                pressureOpacityExponent,
                                onPressureOpacityExponent,
                            )
                        }
                        if (speedTaper) {
                            StudioSlider(
                                "Speed taper strength",
                                "${(speedTaperAmount * 100).roundToInt()}%",
                                speedTaperAmount,
                                0f..1f,
                                onSpeedTaperAmount,
                            )
                        }
                        Text(
                            if (airbrush) {
                                "Tune how quickly spray diameter and flow build as pressure increases."
                            } else {
                                "Tune light-pressure line weight, transparency, and fast-stroke taper."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (supportsPencilTilt) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("Pencil point and shading", style = MaterialTheme.typography.titleSmall)
                        StudioSlider("Point size", "${(pencilPointSize * 100).roundToInt()}%", pencilPointSize, .5f..2f, onPencilPointSize)
                        Text("Tilt behavior", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = pencilTiltMode == PencilTiltMode.SHADING_SWITCH,
                                onClick = { onPencilTiltMode(PencilTiltMode.SHADING_SWITCH) },
                                label = { Text("Shading switch") },
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = pencilTiltMode == PencilTiltMode.GRADUAL,
                                onClick = { onPencilTiltMode(PencilTiltMode.GRADUAL) },
                                label = { Text("Gradual") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pencilTiltMode == PencilTiltMode.SHADING_SWITCH) {
                            val shadeEnd = (pencilShadeStartRadians + pencilShadeTransitionRadians)
                                .coerceAtMost(BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS)
                            val shadeStartUpper = pencilShadeStartUpperBound(shadeEnd)
                            StudioSlider(
                                "Shading starts",
                                "${degrees(pencilShadeStartRadians)}°",
                                pencilShadeStartRadians.coerceIn(
                                    BrushPreset.MIN_PENCIL_SHADE_START_RADIANS,
                                    shadeStartUpper,
                                ),
                                BrushPreset.MIN_PENCIL_SHADE_START_RADIANS..shadeStartUpper,
                            ) { start ->
                                val safeStart = start.coerceIn(
                                    BrushPreset.MIN_PENCIL_SHADE_START_RADIANS,
                                    shadeStartUpper,
                                )
                                onPencilShadeStartRadians(safeStart)
                                onPencilShadeTransitionRadians(pencilShadeTransition(safeStart, shadeEnd))
                            }
                            StudioSlider(
                                "Full shading",
                                "${degrees(shadeEnd)}°",
                                shadeEnd,
                                (pencilShadeStartRadians + BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS)..
                                    BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS,
                            ) { end ->
                                onPencilShadeTransitionRadians(
                                    pencilShadeTransition(pencilShadeStartRadians, end),
                                )
                            }
                            Text(
                                "Normal point drawing stays stable until the start angle, then changes to side shading.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            val fullTilt = BrushPreset.Pencil.copy(
                                pencilTiltSensitivity = pencilTiltSensitivity,
                            ).pencilFullTiltRadians()
                            StudioSlider(
                                "Full shading",
                                "${degrees(fullTilt)}°",
                                fullTilt,
                                BrushPreset.MIN_PENCIL_FULL_TILT_RADIANS..
                                    BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS,
                            ) { onPencilTiltSensitivity(pencilTiltSensitivityForFullAngle(it)) }
                            Text(
                                "Contact size changes continuously with the amount of tilt.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        StudioSlider(
                            "Tilted size boost",
                            "${(pencilShadeSize * 100).roundToInt()}%",
                            pencilShadeSize,
                            0f..BrushPreset.MAX_PENCIL_SHADE_SIZE,
                            onPencilShadeSize,
                        )
                        StudioSlider("Side opacity", "${(pencilShadeOpacity * 100).roundToInt()}%", pencilShadeOpacity, .2f..1f, onPencilShadeOpacity)
                        StudioSlider("Graphite grain", "${(pencilGrain * 100).roundToInt()}%", pencilGrain, 0f..1f, onPencilGrain)
                        TiltCalibrationPad(
                            pointSize = pencilPointSize,
                            sensitivity = pencilTiltSensitivity,
                            tiltMode = pencilTiltMode,
                            shadeStartRadians = pencilShadeStartRadians,
                            shadeTransitionRadians = pencilShadeTransitionRadians,
                            shadeSize = pencilShadeSize,
                            shadeOpacity = pencilShadeOpacity,
                            grain = pencilGrain,
                            onSensitivity = onPencilTiltSensitivity,
                            onShadeStart = onPencilShadeStartRadians,
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
private fun PressureResponseChips(
    label: String,
    exponent: Float,
    onExponent: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Soft" to .55f, "Balanced" to 1f, "Firm" to 1.65f).forEachIndexed { index, (name, value) ->
                val selected = when (index) {
                    0 -> exponent < .78f
                    1 -> exponent in .78f..<1.3f
                    else -> exponent >= 1.3f
                }
                FilterChip(
                    selected = selected,
                    onClick = { onExponent(value) },
                    label = { Text(name) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BrushTipPreview(
    toolName: String,
    brush: BrushPreset,
    erasing: Boolean,
    opacity: Float,
    hardness: Float,
    pressureSize: Boolean,
    pressureOpacity: Boolean,
    speedTaper: Boolean,
    pressureSizeStart: Float,
    pressureSizeExponent: Float,
    pressureOpacityStart: Float,
    pressureOpacityExponent: Float,
    speedTaperAmount: Float,
    pencilPointSize: Float,
    pencilTiltSensitivity: Float,
    pencilShadeSize: Float,
    pencilShadeOpacity: Float,
    pencilGrain: Float,
    pencilTiltMode: PencilTiltMode,
    pencilShadeStartRadians: Float,
    pencilShadeTransitionRadians: Float,
) {
    val previewBrush = brush.copy(
        hardness = hardness,
        pressureToSize = if (pressureSize) PressureCurve(pressureSizeStart, 1f, pressureSizeExponent) else PressureCurve(1f, 1f, 1f),
        pressureToOpacity = if (pressureOpacity) PressureCurve(pressureOpacityStart, 1f, pressureOpacityExponent) else PressureCurve(1f, 1f, 1f),
        speedTaper = if (speedTaper) speedTaperAmount else 0f,
        pencilPointSize = pencilPointSize,
        pencilTiltSensitivity = pencilTiltSensitivity,
        pencilShadeSize = pencilShadeSize,
        pencilShadeOpacity = pencilShadeOpacity,
        pencilGrain = pencilGrain,
        pencilTiltMode = pencilTiltMode,
        pencilShadeStartRadians = pencilShadeStartRadians,
        pencilShadeTransitionRadians = pencilShadeTransitionRadians,
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Live tip preview", style = MaterialTheme.typography.titleSmall)
            Text(toolName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Box(
            Modifier.fillMaxWidth().height(104.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFFAFAFA), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF777A82), RoundedCornerShape(14.dp))
                .semantics { contentDescription = "$toolName live tip preview" },
        ) {
            AndroidView(
                factory = { BrushStrokePreviewView(it) },
                update = { view ->
                    view.setPreview(
                        brush = previewBrush,
                        erasing = erasing,
                        opacity = opacity,
                        color = RgbaColor(.25f, .25f, .28f),
                    )
                },
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            )
            if (!erasing && previewBrush.engine == BrushEngine.PENCIL) {
                Canvas(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    val left = 5f
                    val right = size.width - 5f
                    val markerAngles = if (pencilTiltMode == PencilTiltMode.SHADING_SWITCH) {
                        listOf(pencilShadeStartRadians, previewBrush.pencilShadeEndRadians())
                    } else {
                        listOf(previewBrush.pencilFullTiltRadians())
                    }
                    markerAngles.forEachIndexed { index, angle ->
                        val x = left + (right - left) *
                            (angle / BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS).coerceIn(0f, 1f)
                        drawLine(
                            color = if (index == 0 && markerAngles.size > 1) {
                                Color(0xFFED6A5A).copy(alpha = .72f)
                            } else {
                                Color(0xFF8A6DE9).copy(alpha = .72f)
                            },
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.5f,
                        )
                    }
                }
            }
            if (!erasing && brush.engine == BrushEngine.PENCIL) {
                Text(
                    "0°",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF55575D),
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 2.dp),
                )
                Text(
                    "69° tilt",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF55575D),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 2.dp),
                )
            }
        }
        Text(
            if (!erasing && brush.engine == BrushEngine.PENCIL) {
                if (pencilTiltMode == PencilTiltMode.SHADING_SWITCH) {
                    "Tilt crosses shading start and full-shading markers from left to right."
                } else {
                    "Tilt increases from upright to full shading from left to right."
                }
            } else if (erasing) {
                "The dark sample makes the eraser's edge softness easy to compare."
            } else if (pressureSize || pressureOpacity || speedTaper) {
                "The stroke shows the current pressure response${if (speedTaper) " and speed taper" else ""}."
            } else {
                "The stroke shows the tip with pressure and speed response disabled."
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun TiltCalibrationPad(
    pointSize: Float,
    sensitivity: Float,
    tiltMode: PencilTiltMode,
    shadeStartRadians: Float,
    shadeTransitionRadians: Float,
    shadeSize: Float,
    shadeOpacity: Float,
    grain: Float,
    onSensitivity: (Float) -> Unit,
    onShadeStart: (Float) -> Unit,
) {
    var measuredTilt by remember { mutableFloatStateOf(0f) }
    var hasStylusSample by remember { mutableStateOf(false) }
    val samples = remember { mutableStateListOf<PencilPreviewSample>() }
    val previewBrush = remember(sensitivity, tiltMode, shadeStartRadians, shadeTransitionRadians) {
        BrushPreset.Pencil.copy(
            pencilTiltSensitivity = sensitivity.coerceIn(0f, 1f),
            pencilTiltMode = tiltMode,
            pencilShadeStartRadians = shadeStartRadians.coerceIn(
                BrushPreset.MIN_PENCIL_SHADE_START_RADIANS,
                BrushPreset.MAX_PENCIL_SHADE_START_RADIANS,
            ),
            pencilShadeTransitionRadians = shadeTransitionRadians.coerceIn(
                BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS,
                BrushPreset.MAX_PENCIL_SHADE_TRANSITION_RADIANS,
            ),
        )
    }
    val fullTilt = if (tiltMode == PencilTiltMode.SHADING_SWITCH) {
        previewBrush.pencilShadeEndRadians()
    } else previewBrush.pencilFullTiltRadians()
    val measuredDegrees = Math.toDegrees(measuredTilt.toDouble()).roundToInt()
    val fullDegrees = Math.toDegrees(fullTilt.toDouble()).roundToInt()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Hover tilt calibration", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
        Text(
            "Hover the pencil over the square at your preferred shading angle. You do not need to touch the screen.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(148.dp)
                .background(Color(0xFFFAFAFA), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF777A82), RoundedCornerShape(14.dp))
                .semantics { contentDescription = "Pencil hover tilt calibration pad" }
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
                    val response = previewBrush.pencilTiltResponse(end.tilt)
                    val pointWidth = 2.5f * pointSize
                    val shadeWidth = pointWidth * (1f + 9f * shadeSize)
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
                    "Hover pencil here",
                    color = Color(0xFF202125),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Text(
            if (hasStylusSample) {
                if (tiltMode == PencilTiltMode.SHADING_SWITCH) {
                    "$measuredDegrees° reported · shading starts at ${degrees(shadeStartRadians)}°"
                } else {
                    "$measuredDegrees° reported · full shading at $fullDegrees°"
                }
            } else {
                if (tiltMode == PencilTiltMode.SHADING_SWITCH) {
                    "Shading starts at ${degrees(shadeStartRadians)}° · full at $fullDegrees°"
                } else {
                    "Current full-shading angle: $fullDegrees°"
                }
            },
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            enabled = hasStylusSample && measuredTilt >= BrushPreset.MIN_PENCIL_FULL_TILT_RADIANS &&
                measuredTilt <= BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS,
            onClick = {
                if (tiltMode == PencilTiltMode.SHADING_SWITCH) {
                    onShadeStart(
                        measuredTilt.coerceIn(
                            BrushPreset.MIN_PENCIL_SHADE_START_RADIANS,
                            minOf(
                                BrushPreset.MAX_PENCIL_SHADE_START_RADIANS,
                                BrushPreset.MAX_PENCIL_FULL_TILT_RADIANS - shadeTransitionRadians,
                            ),
                        ),
                    )
                } else {
                    onSensitivity(pencilTiltSensitivityForFullAngle(measuredTilt))
                }
            },
        ) {
            Text(
                if (!hasStylusSample) "Use measured angle"
                else if (tiltMode == PencilTiltMode.SHADING_SWITCH) {
                    "Use $measuredDegrees° as shading start"
                } else {
                    "Use $measuredDegrees° as full shading"
                },
            )
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

private fun degrees(radians: Float): Int = Math.toDegrees(radians.toDouble()).roundToInt()

internal fun pencilShadeStartUpperBound(fullShadeRadians: Float): Float = minOf(
    BrushPreset.MAX_PENCIL_SHADE_START_RADIANS,
    fullShadeRadians - BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS,
).coerceAtLeast(BrushPreset.MIN_PENCIL_SHADE_START_RADIANS)

internal fun pencilShadeTransition(startRadians: Float, fullShadeRadians: Float): Float =
    (fullShadeRadians - startRadians).coerceIn(
        BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS,
        BrushPreset.MAX_PENCIL_SHADE_TRANSITION_RADIANS,
    )

@Composable
private fun StudioSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange)
    }
}
