package dev.tipstroke.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable
fun PrecisionSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValue: (Float) -> Unit,
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
    modifier: Modifier = Modifier,
    valueContent: (@Composable () -> Unit)? = null,
    endpoint: (@Composable (Float) -> Unit)? = null,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFE7E7E5), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (valueContent != null) valueContent() else Text(valueLabel, color = Color(0xFFED6A5A), fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            endpoint?.invoke(range.start)
            Slider(
                value = sliderFraction(value, range),
                onValueChange = { onAdjustmentStart(); onValue(sliderValue(it, range)) },
                onValueChangeFinished = onAdjustmentEnd,
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f).height(44.dp),
            )
            endpoint?.invoke(range.endInclusive)
        }
        StepButtons(value, range, step, onValue, onAdjustmentStart, onAdjustmentEnd)
    }
}

@Composable
fun VerticalPrecisionSlider(
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
    valueContent: (@Composable () -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFFE7E7E5), fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Medium)
        if (valueContent != null) valueContent() else Text(valueLabel, color = Color(0xFFED6A5A), fontSize = if (compact) 10.sp else 12.sp)
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
    val latestValue by rememberUpdatedState(onValue)
    val latestStart by rememberUpdatedState(onAdjustmentStart)
    val latestEnd by rememberUpdatedState(onAdjustmentEnd)
    Canvas(
        Modifier.width(48.dp).height(height)
            .semantics { contentDescription = "Vertical adjustment" }
            .pointerInput(range) {
                fun valueAt(y: Float): Float = sliderValue(
                    verticalSliderFraction(y, size.height.toFloat(), density), range,
                )
                detectDragGestures(
                    onDragStart = { latestStart(); latestValue(valueAt(it.y)) },
                    onDragEnd = { latestEnd() },
                    onDragCancel = { latestEnd() },
                    onDrag = { change, _ -> latestValue(valueAt(change.position.y)); change.consume() },
                )
            },
    ) {
        val x = size.width / 2f
        val top = verticalSliderInset(size.height, density)
        val bottom = size.height - top
        val fraction = sliderFraction(value, range)
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
