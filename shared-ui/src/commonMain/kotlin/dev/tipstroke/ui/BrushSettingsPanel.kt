package dev.tipstroke.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BrushPreset.libraryBuiltIns, key = { it.id.value }) { preset ->
                FilterChip(brush.id == preset.id, onClick = { onBrush(preset) }, leadingIcon = { TipSwatch(preset, Modifier.size(36.dp)) }, label = { Text(preset.displayName) })
            }
        }
        PrecisionSlider("Size", "${brush.baseSizePx.roundToInt()} px", brush.baseSizePx, 2f..512f, 1f,
            { onBrush(brush.copy(baseSizePx = it)) }, {}, {})
        PrecisionSlider("Opacity", "${(brush.opacity * 100).roundToInt()}%", brush.opacity, .05f..1f, .01f,
            { onBrush(brush.copy(opacity = it)) }, {}, {})
        if (brush.engine == BrushEngine.AIRBRUSH || brush.stamp != null) {
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
        PrecisionSlider("Spacing", "${(brush.spacing * 100).roundToInt()}%", brush.spacing, .001f..2f, .01f,
            { onBrush(brush.copy(spacing = it)) }, {}, {})
        PrecisionSlider("Stabilization", "${(brush.stabilization * 100).roundToInt()}%", brush.stabilization, 0f..1f, .01f,
            { onBrush(brush.copy(stabilization = it)) }, {}, {})
        PrecisionSlider("Speed taper", "${(brush.speedTaper * 100).roundToInt()}%", brush.speedTaper, 0f..1f, .01f,
            { onBrush(brush.copy(speedTaper = it)) }, {}, {})
        brush.stamp?.let { stamp ->
            Text("Shape and grain", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                StampShape.entries.filter { it != StampShape.IMAGE || stamp.shapeTexture != null }.forEach { shape ->
                    FilterChip(stamp.shape == shape, { onBrush(brush.copy(stamp = stamp.copy(shape = shape))) }, label = { Text(shape.name.lowercase()) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                StampGrain.entries.filter { it != StampGrain.IMAGE || stamp.grainTexture != null }.forEach { grain ->
                    FilterChip(stamp.grain == grain, { onBrush(brush.copy(stamp = stamp.copy(grain = grain))) }, label = { Text(grain.name.lowercase()) })
                }
            }
            fun changed(value: StampBrush) = onBrush(brush.copy(stamp = value))
            @Composable fun parameter(label: String, value: Float, range: ClosedFloatingPointRange<Float>, update: (Float) -> Unit) {
                PrecisionSlider(label, "${(value * 100).roundToInt() / 100f}", value, range, .01f, update, {}, {})
            }
            parameter("Aspect ratio", stamp.aspectRatio, .1f..1f) { changed(stamp.copy(aspectRatio = it)) }
            parameter("Rotation", stamp.rotationDegrees, -180f..180f) { changed(stamp.copy(rotationDegrees = it)) }
            parameter("Rotation jitter", stamp.rotationJitter, 0f..180f) { changed(stamp.copy(rotationJitter = it)) }
            parameter("Scatter", stamp.scatter, 0f..2f) { changed(stamp.copy(scatter = it)) }
            parameter("Size jitter", stamp.sizeJitter, 0f..1f) { changed(stamp.copy(sizeJitter = it)) }
            parameter("Opacity jitter", stamp.opacityJitter, 0f..1f) { changed(stamp.copy(opacityJitter = it)) }
            parameter("Flow", stamp.flow, .01f..1f) { changed(stamp.copy(flow = it)) }
            parameter("Tilt size", stamp.tiltSize, 0f..3f) { changed(stamp.copy(tiltSize = it)) }
            parameter("Start taper", stamp.startTaperPx, 0f..500f) { changed(stamp.copy(startTaperPx = it)) }
            parameter("Fade out", stamp.fadeOutPx, 0f..2000f) { changed(stamp.copy(fadeOutPx = it)) }
            parameter("Hue jitter", stamp.hueJitter, 0f..1f) { changed(stamp.copy(hueJitter = it)) }
            parameter("Saturation jitter", stamp.saturationJitter, 0f..1f) { changed(stamp.copy(saturationJitter = it)) }
            parameter("Grain scale", stamp.grainScale, 2f..512f) { changed(stamp.copy(grainScale = it)) }
            parameter("Grain depth", stamp.grainDepth, 0f..1f) { changed(stamp.copy(grainDepth = it)) }
            Row { Text("Follow direction", Modifier.weight(1f)); Switch(stamp.followDirection, { changed(stamp.copy(followDirection = it)) }) }
            Row { Text("Grain moves with stamp", Modifier.weight(1f)); Switch(stamp.grainPlacement == GrainPlacement.STAMP,
                { changed(stamp.copy(grainPlacement = if (it) GrainPlacement.STAMP else GrainPlacement.CANVAS)) }) }
            Slider(stamp.count.toFloat(), { changed(stamp.copy(count = it.roundToInt().coerceIn(1, 8))) }, valueRange = 1f..8f, steps = 6)
            Text("${stamp.count} stamps per step")
        }
        brush.ink?.let { ink ->
            Text("Ink tip", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                InkTexture.entries.forEach { texture -> FilterChip(ink.texture == texture,
                    { onBrush(brush.copy(ink = ink.copy(texture = texture, particles = ink.particles || texture == InkTexture.SOFT))) }, label = { Text(texture.name.lowercase()) }) }
            }
            PrecisionSlider("Aspect ratio", "${ink.aspectRatio}", ink.aspectRatio, .1f..1f, .01f, { onBrush(brush.copy(ink = ink.copy(aspectRatio = it))) }, {}, {})
            PrecisionSlider("Flow", "${ink.flow}", ink.flow, .01f..1f, .01f, { onBrush(brush.copy(ink = ink.copy(flow = it))) }, {}, {})
        }
        TextButton(onClick = { onBrush(BrushPreset.libraryBuiltIns.firstOrNull { it.id == brush.id }
            ?: BrushPreset.Blank.copy(id = brush.id, displayName = brush.displayName)) }) { Text("Reset brush") }
    }
}
