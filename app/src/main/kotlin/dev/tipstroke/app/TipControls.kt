package dev.tipstroke.app

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.tipstroke.core.model.RgbaColor
import kotlin.math.roundToInt

@Composable
internal fun TipControls(
    size: Float,
    opacity: Float,
    color: RgbaColor,
    frequentColors: List<RgbaColor>,
    onSize: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
    onColor: (RgbaColor) -> Unit,
    onOpenColorPicker: () -> Unit,
    onAdjustmentStart: () -> Unit = {},
    onAdjustmentEnd: () -> Unit = {},
    horizontal: Boolean,
    compactVertical: Boolean = false,
    verticalTrackHeight: Dp = if (compactVertical) 154.dp else 205.dp,
    showColorSwitcher: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showColorSwitcher) {
            ColorSwitcher(color, frequentColors, onColor, onOpenColorPicker)
            Spacer(Modifier.height(if (compactVertical) 6.dp else 14.dp))
        }
        if (horizontal) {
            Row(
                Modifier.widthIn(max = 390.dp).fillMaxWidth(.62f)
                    .background(Color(0xD9202125), RoundedCornerShape(20.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PrecisionSlider("Size", "${size.roundToInt()} px", size, 2f..180f, 1f, onSize, onAdjustmentStart, onAdjustmentEnd, Modifier.weight(1f))
                PrecisionSlider("Opacity", "${(opacity * 100).roundToInt()}%", opacity, .05f..1f, .01f, onOpacity, onAdjustmentStart, onAdjustmentEnd, Modifier.weight(1f))
            }
        } else {
            Column(
                Modifier.width(if (compactVertical) 74.dp else 86.dp)
                    .background(Color(0xD9202125), RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0xB345474D), RoundedCornerShape(24.dp))
                    .padding(horizontal = 7.dp, vertical = if (compactVertical) 4.dp else 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VerticalPrecisionSlider(
                    "Size", "${size.roundToInt()} px", size, 2f..180f, 1f, onSize,
                    onAdjustmentStart, onAdjustmentEnd,
                    trackHeight = verticalTrackHeight,
                    compact = compactVertical,
                )
                HorizontalDivider(
                    Modifier.padding(vertical = if (compactVertical) 2.dp else 8.dp),
                    color = Color(0xFF3A3C41),
                )
                VerticalPrecisionSlider(
                    "Opacity", "${(opacity * 100).roundToInt()}%", opacity, .05f..1f, .01f, onOpacity,
                    onAdjustmentStart, onAdjustmentEnd,
                    trackHeight = verticalTrackHeight,
                    compact = compactVertical,
                )
            }
        }
    }
}

@Composable
internal fun ColorSwitcher(
    color: RgbaColor,
    frequentColors: List<RgbaColor>,
    onColor: (RgbaColor) -> Unit,
    onOpenColorPicker: () -> Unit,
    compact: Boolean = false,
) {
    val defaults = listOf(
        RgbaColor(.05f, .05f, .06f), RgbaColor(.93f, .42f, .35f),
        RgbaColor(.2f, .45f, .9f), RgbaColor(.18f, .65f, .48f), RgbaColor(1f, 1f, 1f),
    )
    val choices = (frequentColors + defaults).distinctBy(::colorKey).filter { colorKey(it) != colorKey(color) }.take(4)
    Row(
        Modifier.width(if (compact) 100.dp else 116.dp).height(if (compact) 46.dp else 58.dp)
            .background(Color(0xD9202125), RoundedCornerShape(if (compact) 16.dp else 20.dp))
            .border(1.dp, Color(0xB345474D), RoundedCornerShape(if (compact) 16.dp else 20.dp))
            .padding(if (compact) 4.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
    ) {
        Box(
            Modifier.size(if (compact) 36.dp else 46.dp).clip(CircleShape).background(color.toCompose())
                .border(2.dp, Color.White, CircleShape)
                .clickable(onClick = onOpenColorPicker)
                .semantics { contentDescription = "Open color picker" },
        )
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) {
            choices.chunked(2).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) {
                    row.forEachIndexed { columnIndex, option ->
                        val position = rowIndex * 2 + columnIndex + 1
                        Box(
                            Modifier.size(if (compact) 17.dp else 22.dp).clip(CircleShape).background(option.toCompose())
                                .border(1.dp, Color.White.copy(alpha = .82f), CircleShape)
                                .clickable { onColor(option) }
                                .semantics { contentDescription = "Used color $position" },
                        )
                    }
                }
            }
        }
    }
}

private fun colorKey(color: RgbaColor): Int =
    ((color.alpha * 255).roundToInt() shl 24) or ((color.red * 255).roundToInt() shl 16) or
        ((color.green * 255).roundToInt() shl 8) or (color.blue * 255).roundToInt()

@Composable
private fun PrecisionSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValue: (Float) -> Unit,
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFE7E7E5), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(valueLabel, color = Color(0xFFED6A5A), fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = { onAdjustmentStart(); onValue(it) },
            onValueChangeFinished = onAdjustmentEnd,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
        StepButtons(value, range, step, onValue, onAdjustmentStart, onAdjustmentEnd)
    }
}

@Composable
private fun VerticalPrecisionSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValue: (Float) -> Unit,
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
    trackHeight: Dp,
    compact: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFFE7E7E5), fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Medium)
        Text(valueLabel, color = Color(0xFFED6A5A), fontSize = if (compact) 10.sp else 12.sp)
        Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
        SmallStepButton("+", compact) {
            onAdjustmentStart(); onValue((value + step).coerceIn(range)); onAdjustmentEnd()
        }
        VerticalDragTrack(value, range, trackHeight, onValue, onAdjustmentStart, onAdjustmentEnd)
        SmallStepButton("−", compact) {
            onAdjustmentStart(); onValue((value - step).coerceIn(range)); onAdjustmentEnd()
        }
    }
}

@Composable
private fun VerticalDragTrack(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    height: Dp,
    onValue: (Float) -> Unit,
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
) {
    fun valueAt(y: Float, height: Float): Float {
        val fraction = (1f - ((y - 14f) / (height - 28f))).coerceIn(0f, 1f)
        return range.start + fraction * (range.endInclusive - range.start)
    }
    Canvas(
        Modifier.width(48.dp).height(height)
            .semantics { contentDescription = "Vertical adjustment" }
            .pointerInput(range) {
                detectDragGestures(
                    onDragStart = { onAdjustmentStart(); onValue(valueAt(it.y, size.height.toFloat())) },
                    onDragEnd = onAdjustmentEnd,
                    onDragCancel = onAdjustmentEnd,
                    onDrag = { change, _ -> onValue(valueAt(change.position.y, size.height.toFloat())); change.consume() },
                )
            },
    ) {
        val x = size.width / 2f
        val top = minOf(14.dp.toPx(), size.height * .14f)
        val bottom = size.height - top
        val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        val thumbY = bottom - fraction * (bottom - top)
        drawLine(Color(0xFF4A4758), Offset(x, top), Offset(x, bottom), 8.dp.toPx(), StrokeCap.Round)
        drawLine(Color(0xFFED6A5A), Offset(x, thumbY), Offset(x, bottom), 8.dp.toPx(), StrokeCap.Round)
        drawCircle(Color(0xFFF8F8F5), 10.dp.toPx(), Offset(x, thumbY))
        drawCircle(Color(0xFFED6A5A), 3.dp.toPx(), Offset(x, thumbY))
    }
}

@Composable
private fun StepButtons(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValue: (Float) -> Unit,
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallStepButton("−") {
            onAdjustmentStart(); onValue((value - step).coerceIn(range)); onAdjustmentEnd()
        }
        SmallStepButton("+") {
            onAdjustmentStart(); onValue((value + step).coerceIn(range)); onAdjustmentEnd()
        }
    }
}

@Composable
private fun SmallStepButton(label: String, compact: Boolean = false, onClick: () -> Unit) {
    FilledTonalButton(
        onClick,
        modifier = Modifier.size(if (compact) 26.dp else 36.dp),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(if (compact) 9.dp else 11.dp),
    ) {
        Text(label, fontSize = if (compact) 15.sp else 18.sp)
    }
}

private fun RgbaColor.toCompose() = Color(red, green, blue, alpha)
