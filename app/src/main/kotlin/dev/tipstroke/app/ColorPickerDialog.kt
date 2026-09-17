package dev.tipstroke.app

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tipstroke.core.model.RgbaColor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class ColorPickerMode(val label: String) {
    HSV_WHEEL("Wheel"),
    HSV_SLIDERS("Sliders"),
    VALUES("Values"),
}

@Composable
internal fun ColorPickerPanel(
    initialColor: RgbaColor,
    drawingPalette: List<RgbaColor>,
    paletteColorCount: Int,
    colorHistory: List<RgbaColor>,
    onColorSelected: (RgbaColor) -> Unit,
    onPaletteColorCountChanged: (Int) -> Unit,
    onClearHistory: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val previousColor = remember { initialColor }
    val initialHsv = remember { previousColor.toHsv() }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    var mode by remember { mutableStateOf(ColorPickerMode.HSV_WHEEL) }
    val selected = remember(hue, saturation, value) { hsvColor(hue, saturation, value) }
    val panelInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(selected) { onColorSelected(selected) }

    fun choose(color: RgbaColor) {
        val hsv = color.toHsv()
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
    }

    Surface(
        modifier = modifier.width(if (compact) 320.dp else 380.dp)
            .heightIn(max = if (compact) 540.dp else 720.dp)
            .clickable(interactionSource = panelInteraction, indication = null) {},
        color = Color(0xF51C1D20),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF45474D)),
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.padding(horizontal = if (compact) 14.dp else 18.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Colors", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                ColorSwatch(previousColor, 30.dp, "Previous color", onClick = { choose(previousColor) })
                Spacer(Modifier.width(6.dp))
                ColorSwatch(selected, 38.dp, "Selected color preview")
            }

            Spacer(Modifier.height(14.dp))
            PickerModeTabs(mode, onMode = { mode = it })
            Spacer(Modifier.height(if (compact) 12.dp else 16.dp))

            when (mode) {
                ColorPickerMode.HSV_WHEEL -> DiscPicker(
                    hue, saturation, value,
                    pickerSize = if (compact) 190.dp else 242.dp,
                    onChange = { newHue, newSaturation, newValue ->
                        hue = newHue
                        saturation = newSaturation
                        value = newValue
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                ColorPickerMode.HSV_SLIDERS -> ClassicPicker(
                    hue, saturation, value, compact,
                    onChange = { newHue, newSaturation, newValue ->
                        hue = newHue
                        saturation = newSaturation
                        value = newValue
                    },
                )
                ColorPickerMode.VALUES -> ValuesPicker(
                    hue, saturation, value,
                    onChange = { newHue, newSaturation, newValue ->
                        hue = newHue
                        saturation = newSaturation
                        value = newValue
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            DrawingPaletteSection(
                colors = drawingPalette.take(paletteColorCount),
                requestedCount = paletteColorCount,
                onCountChanged = onPaletteColorCountChanged,
                onColor = ::choose,
            )
            Spacer(Modifier.height(18.dp))
            HistorySection(colorHistory.take(10), onColor = ::choose, onClear = onClearHistory)
        }
    }
}

@Composable
private fun PickerModeTabs(mode: ColorPickerMode, onMode: (ColorPickerMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF292A2F), RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ColorPickerMode.entries.forEach { option ->
            val selected = option == mode
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (selected) Color(0xFF45474E) else Color.Transparent)
                    .clickable { onMode(option) }
                    .padding(vertical = 9.dp)
                    .semantics { contentDescription = "${option.label} color picker" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label,
                    color = if (selected) Color.White else Color(0xFFB8B9BE),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun DiscPicker(
    hue: Float,
    saturation: Float,
    value: Float,
    pickerSize: Dp,
    onChange: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentHue = rememberUpdatedState(hue)
    val currentSaturation = rememberUpdatedState(saturation)
    val currentValue = rememberUpdatedState(value)
    val currentOnChange = rememberUpdatedState(onChange)
    val whiteOverlay = remember { hsvDiscOverlay(320, white = true) }
    val blackOverlay = remember { hsvDiscOverlay(320, white = false) }

    Canvas(
        modifier.size(pickerSize)
            .semantics { contentDescription = "HSV wheel hue saturation and brightness picker" }
            .pointerInput(Unit) {
                fun update(position: Offset, editingHue: Boolean) {
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    var dx = position.x - center.x
                    var dy = position.y - center.y
                    val radius = minOf(this.size.width, this.size.height) / 2f
                    val innerRadius = radius * .70f
                    val distance = hypot(dx, dy)
                    if (editingHue) {
                        val newHue = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
                        currentOnChange.value(newHue, currentSaturation.value, currentValue.value)
                    } else {
                        if (distance > innerRadius) {
                            dx *= innerRadius / distance
                            dy *= innerRadius / distance
                        }
                        val square = discToSquare(dx / innerRadius, dy / innerRadius)
                        val newSaturation = ((square.x + 1f) / 2f).coerceIn(0f, 1f)
                        val newValue = (1f - (square.y + 1f) / 2f).coerceIn(0f, 1f)
                        currentOnChange.value(currentHue.value, newSaturation, newValue)
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2f
                    // Lock the whole gesture to its starting control. Dragging the inner
                    // handle to its edge can never spill into the hue ring, and vice versa.
                    val editingHue = (down.position - center).getDistance() > radius * .755f
                    update(down.position, editingHue)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        update(change.position, editingHue)
                        change.consume()
                    }
                }
            },
    ) {
        val radius = this.size.minDimension / 2f
        val ringWidth = radius * .19f
        val ringRadius = radius - ringWidth / 2f
        val innerRadius = radius * .70f
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
            ),
            radius = ringRadius,
            style = Stroke(ringWidth),
        )
        drawCircle(Color(0xFF151619), radius = innerRadius + 4.dp.toPx())
        drawCircle(hsvColor(hue, 1f, 1f).toCompose(), radius = innerRadius)
        val overlayOffset = IntOffset(
            (center.x - innerRadius).roundToInt(),
            (center.y - innerRadius).roundToInt(),
        )
        val overlaySize = IntSize((innerRadius * 2f).roundToInt(), (innerRadius * 2f).roundToInt())
        drawImage(whiteOverlay, dstOffset = overlayOffset, dstSize = overlaySize)
        drawImage(blackOverlay, dstOffset = overlayOffset, dstSize = overlaySize)

        val hueRadians = hue / 180f * PI.toFloat()
        val hueMarker = center + Offset(cos(hueRadians), sin(hueRadians)) * ringRadius
        drawCircle(Color.Black.copy(alpha = .52f), ringWidth * .64f, hueMarker)
        drawCircle(hsvColor(hue, 1f, 1f).toCompose(), ringWidth * .52f, hueMarker)
        drawCircle(Color.White.copy(alpha = .92f), ringWidth * .52f, hueMarker, style = Stroke(1.5.dp.toPx()))

        val discPosition = squareToDisc(saturation * 2f - 1f, (1f - value) * 2f - 1f)
        val selector = center + Offset(discPosition.x * innerRadius, discPosition.y * innerRadius)
        drawCircle(Color.Black.copy(alpha = .6f), 11.dp.toPx(), selector)
        drawCircle(Color.White, 9.dp.toPx(), selector, style = Stroke(2.dp.toPx()))
    }
}

internal fun squareToDisc(x: Float, y: Float): Offset {
    return Offset(
        x * kotlin.math.sqrt((1f - y * y / 2f).coerceAtLeast(0f)),
        y * kotlin.math.sqrt((1f - x * x / 2f).coerceAtLeast(0f)),
    )
}

internal fun discToSquare(x: Float, y: Float): Offset {
    val safeX = x.coerceIn(-1f, 1f)
    val safeY = y.coerceIn(-1f, 1f)
    val rootTwo = kotlin.math.sqrt(2f)
    val horizontalBase = 2f + safeX * safeX - safeY * safeY
    val horizontalTerm = 2f * rootTwo * safeX
    val verticalBase = 2f - safeX * safeX + safeY * safeY
    val verticalTerm = 2f * rootTwo * safeY
    return Offset(
        .5f * (
            kotlin.math.sqrt((horizontalBase + horizontalTerm).coerceAtLeast(0f)) -
                kotlin.math.sqrt((horizontalBase - horizontalTerm).coerceAtLeast(0f))
            ),
        .5f * (
            kotlin.math.sqrt((verticalBase + verticalTerm).coerceAtLeast(0f)) -
                kotlin.math.sqrt((verticalBase - verticalTerm).coerceAtLeast(0f))
            ),
    )
}

private fun hsvDiscOverlay(size: Int, white: Boolean) = IntArray(size * size).also { pixels ->
    for (pixelY in 0 until size) {
        val y = ((pixelY + .5f) / size * 2f) - 1f
        for (pixelX in 0 until size) {
            val x = ((pixelX + .5f) / size * 2f) - 1f
            if (x * x + y * y > 1f) continue
            val square = discToSquare(x, y)
            val amount = if (white) {
                1f - ((square.x + 1f) / 2f).coerceIn(0f, 1f)
            } else {
                1f - (1f - (square.y + 1f) / 2f).coerceIn(0f, 1f)
            }
            val alpha = (amount * 255f).roundToInt().coerceIn(0, 255)
            pixels[pixelY * size + pixelX] = if (white) {
                AndroidColor.argb(alpha, 255, 255, 255)
            } else {
                AndroidColor.argb(alpha, 0, 0, 0)
            }
        }
    }
}.let { pixels -> Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap() }

@Composable
private fun ClassicPicker(
    hue: Float,
    saturation: Float,
    value: Float,
    compact: Boolean,
    onChange: (Float, Float, Float) -> Unit,
) {
    ClassicColorField(
        hue, saturation, value,
        height = if (compact) 128.dp else 176.dp,
        onChange = { newSaturation, newValue -> onChange(hue, newSaturation, newValue) },
    )
    Spacer(Modifier.height(12.dp))
    GradientSlider(
        label = "Hue",
        value = hue / 360f,
        brush = Brush.horizontalGradient(
            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
        ),
        onValue = { onChange(it * 360f, saturation, value) },
    )
    GradientSlider(
        label = "Saturation",
        value = saturation,
        brush = Brush.horizontalGradient(
            listOf(hsvColor(hue, 0f, value).toCompose(), hsvColor(hue, 1f, value).toCompose()),
        ),
        onValue = { onChange(hue, it, value) },
    )
    GradientSlider(
        label = "Brightness",
        value = value,
        brush = Brush.horizontalGradient(
            listOf(Color.Black, hsvColor(hue, saturation, 1f).toCompose()),
        ),
        onValue = { onChange(hue, saturation, it) },
    )
}

@Composable
private fun ValuesPicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float, Float) -> Unit,
) {
    var hexText by remember { mutableStateOf("") }
    var redText by remember { mutableStateOf("") }
    var greenText by remember { mutableStateOf("") }
    var blueText by remember { mutableStateOf("") }
    var hueText by remember { mutableStateOf("") }
    var saturationText by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }

    LaunchedEffect(hue, saturation, value) {
        val color = hsvColor(hue, saturation, value)
        hexText = colorToHex(color)
        redText = color.channelText { red }
        greenText = color.channelText { green }
        blueText = color.channelText { blue }
        hueText = hue.roundToInt().coerceIn(0, 360).toString()
        saturationText = (saturation * 100f).roundToInt().coerceIn(0, 100).toString()
        valueText = (value * 100f).roundToInt().coerceIn(0, 100).toString()
    }

    Text(
        "Enter a standard color value",
        color = Color(0xFFBFC0C4),
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = hexText,
        onValueChange = { entered ->
            val digits = entered.removePrefix("#").filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
                .take(6).uppercase()
            hexText = "#$digits"
            parseHexColor(hexText)?.toHsv()?.let { onChange(it[0], it[1], it[2]) }
        },
        label = { Text("HEX") },
        placeholder = { Text("#RRGGBB") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
        ),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Hex color value" },
    )

    Spacer(Modifier.height(12.dp))
    ValueSectionLabel("RGB", "0–255")
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueField("R", redText, 255, Modifier.weight(1f)) { text, number ->
            redText = text
            number?.let {
                val current = hsvColor(hue, saturation, value)
                RgbaColor(it / 255f, current.green, current.blue).toHsv()
                    .let { hsv -> onChange(hsv[0], hsv[1], hsv[2]) }
            }
        }
        ValueField("G", greenText, 255, Modifier.weight(1f)) { text, number ->
            greenText = text
            number?.let {
                val current = hsvColor(hue, saturation, value)
                RgbaColor(current.red, it / 255f, current.blue).toHsv()
                    .let { hsv -> onChange(hsv[0], hsv[1], hsv[2]) }
            }
        }
        ValueField("B", blueText, 255, Modifier.weight(1f)) { text, number ->
            blueText = text
            number?.let {
                val current = hsvColor(hue, saturation, value)
                RgbaColor(current.red, current.green, it / 255f).toHsv()
                    .let { hsv -> onChange(hsv[0], hsv[1], hsv[2]) }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    ValueSectionLabel("HSV", "H 0–360 · S/V 0–100")
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueField("H", hueText, 360, Modifier.weight(1f)) { text, number ->
            hueText = text
            number?.let { onChange(it.toFloat(), saturation, value) }
        }
        ValueField("S", saturationText, 100, Modifier.weight(1f)) { text, number ->
            saturationText = text
            number?.let { onChange(hue, it / 100f, value) }
        }
        ValueField("V", valueText, 100, Modifier.weight(1f)) { text, number ->
            valueText = text
            number?.let { onChange(hue, saturation, it / 100f) }
        }
    }
}

@Composable
private fun ValueSectionLabel(name: String, range: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(7.dp))
        Text(range, color = Color(0xFF8E9096), fontSize = 11.sp)
    }
}

@Composable
private fun ValueField(
    label: String,
    value: String,
    maximum: Int,
    modifier: Modifier = Modifier,
    onValue: (String, Int?) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { entered ->
            val digits = entered.filter(Char::isDigit).take(3)
            onValue(digits, digits.toIntOrNull()?.takeIf { it in 0..maximum })
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.semantics { contentDescription = "$label color value" },
    )
}

@Composable
private fun ClassicColorField(
    hue: Float,
    saturation: Float,
    value: Float,
    height: Dp,
    onChange: (Float, Float) -> Unit,
) {
    val currentOnChange = rememberUpdatedState(onChange)
    Canvas(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = "HSV sliders saturation and brightness field" }
            .pointerInput(Unit) {
                fun update(position: Offset) {
                    currentOnChange.value(
                        (position.x / size.width).coerceIn(0f, 1f),
                        (1f - position.y / size.height).coerceIn(0f, 1f),
                    )
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    update(down.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        update(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        drawRect(hsvColor(hue, 1f, 1f).toCompose())
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val marker = Offset(saturation * size.width, (1f - value) * size.height)
        drawCircle(Color.Black.copy(alpha = .55f), 11.dp.toPx(), marker)
        drawCircle(Color.White, 8.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun GradientSlider(label: String, value: Float, brush: Brush, onValue: (Float) -> Unit) {
    val currentOnValue = rememberUpdatedState(onValue)
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFBFC0C4), fontSize = 11.sp, modifier = Modifier.width(68.dp))
        Canvas(
            Modifier.weight(1f).height(24.dp)
                .semantics { contentDescription = "$label slider" }
                .pointerInput(Unit) {
                    fun update(x: Float) = currentOnValue.value((x / size.width).coerceIn(0f, 1f))
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        update(down.position.x)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            update(change.position.x)
                            change.consume()
                        }
                    }
                },
        ) {
            val y = size.height / 2f
            drawLine(brush, Offset(5.dp.toPx(), y), Offset(size.width - 5.dp.toPx(), y), 8.dp.toPx(), StrokeCap.Round)
            val x = 5.dp.toPx() + value.coerceIn(0f, 1f) * (size.width - 10.dp.toPx())
            drawCircle(Color.Black.copy(alpha = .6f), 8.dp.toPx(), Offset(x, y))
            drawCircle(Color.White, 6.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
private fun DrawingPaletteSection(
    colors: List<RgbaColor>,
    requestedCount: Int,
    onCountChanged: (Int) -> Unit,
    onColor: (RgbaColor) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("Drawing palette", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("$requestedCount most-used average colors", color = Color(0xFF96989E), fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        PaletteCountButton(
            icon = { Icon(Icons.Rounded.Remove, contentDescription = null, tint = if (requestedCount > 1) Color(0xFFE5E5E8) else Color(0xFF717278)) },
            description = "Show fewer drawing colors",
            enabled = requestedCount > 1,
            onClick = { onCountChanged(requestedCount - 1) },
        )
        PaletteCountButton(
            icon = { Icon(Icons.Rounded.Add, contentDescription = null, tint = if (requestedCount < 8) Color(0xFFE5E5E8) else Color(0xFF717278)) },
            description = "Show more drawing colors",
            enabled = requestedCount < 8,
            onClick = { onCountChanged(requestedCount + 1) },
        )
    }
    Spacer(Modifier.height(10.dp))
    if (colors.isEmpty()) {
        Surface(color = Color(0xFF25262A), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                "Draw or import an image to build this palette.",
                color = Color(0xFF9D9FA5), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            )
        }
    } else {
        colors.chunked(4).forEachIndexed { rowIndex, rowColors ->
            if (rowIndex > 0) Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowColors.forEachIndexed { index, color ->
                    Box(
                        Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(10.dp))
                            .background(color.toCompose())
                            .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(10.dp))
                            .clickable { onColor(color) }
                            .semantics { contentDescription = "Drawing palette color ${rowIndex * 4 + index + 1}" },
                    )
                }
                repeat(4 - rowColors.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PaletteCountButton(
    icon: @Composable () -> Unit,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2C2D32))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
private fun HistorySection(colors: List<RgbaColor>, onColor: (RgbaColor) -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("History", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Last 10 colors drawn with", color = Color(0xFF96989E), fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        if (colors.isNotEmpty()) {
            OutlinedButton(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) { Text("Clear", fontSize = 11.sp) }
        }
    }
    Spacer(Modifier.height(10.dp))
    if (colors.isEmpty()) {
        Text("Your drawn colors will appear here.", color = Color(0xFF8E9096), fontSize = 12.sp)
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            colors.forEachIndexed { index, color ->
                Box(
                    Modifier.weight(1f).height(31.dp).clip(RoundedCornerShape(7.dp))
                        .background(color.toCompose())
                        .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(7.dp))
                        .clickable { onColor(color) }
                        .semantics { contentDescription = "Drawn color ${index + 1}" },
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: RgbaColor, size: Dp, description: String, onClick: (() -> Unit)? = null) {
    val base = Modifier.size(size).clip(CircleShape).background(color.toCompose())
        .border(1.5.dp, Color.White.copy(alpha = .9f), CircleShape)
        .semantics { contentDescription = description }
    Box(if (onClick == null) base else base.clickable(onClick = onClick))
}

private inline fun RgbaColor.channelText(channel: RgbaColor.() -> Float): String =
    (channel().coerceIn(0f, 1f) * 255f).roundToInt().toString()

internal fun colorToHex(color: RgbaColor): String = "#%02X%02X%02X".format(
    (color.red.coerceIn(0f, 1f) * 255f).roundToInt(),
    (color.green.coerceIn(0f, 1f) * 255f).roundToInt(),
    (color.blue.coerceIn(0f, 1f) * 255f).roundToInt(),
)

internal fun parseHexColor(input: String): RgbaColor? {
    val digits = input.trim().removePrefix("#")
    if (digits.length != 6 || digits.any { !it.isDigit() && it.uppercaseChar() !in 'A'..'F' }) return null
    val packed = digits.toIntOrNull(16) ?: return null
    return RgbaColor(
        ((packed shr 16) and 0xFF) / 255f,
        ((packed shr 8) and 0xFF) / 255f,
        (packed and 0xFF) / 255f,
    )
}

private fun RgbaColor.toHsv(): FloatArray = FloatArray(3).also { hsv ->
    AndroidColor.RGBToHSV(
        (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt(), hsv,
    )
}

private fun hsvColor(hue: Float, saturation: Float, value: Float): RgbaColor {
    val argb = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
    return RgbaColor(
        AndroidColor.red(argb) / 255f,
        AndroidColor.green(argb) / 255f,
        AndroidColor.blue(argb) / 255f,
    )
}

private fun RgbaColor.toCompose() = Color(red, green, blue, alpha)
