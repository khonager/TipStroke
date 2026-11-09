package dev.tipstroke.app

import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tipstroke.core.model.*
import dev.tipstroke.drawing.android.InkBrushDrawingPad

@Composable
internal fun InkBrushCreatorDialog(initial: LibraryBrush, busy: Boolean, message: String?,
    onSave: (LibraryBrush) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var tab by remember { mutableStateOf("About") }
    var sampleMillis by remember(initial) { mutableStateOf<Double?>(null) }
    var pad by remember { mutableStateOf<InkBrushDrawingPad?>(null) }
    val p = draft.preset
    val ink = requireNotNull(p.ink)
    val forcedStamps = ink.texture == InkTexture.SOFT || ink.accumulate || p.pressureToOpacity.start != p.pressureToOpacity.end
    val usesStamps = ink.particles || forcedStamps
    fun update(value: BrushPreset) { sampleMillis = null; draft = draft.copy(preset = value) }
    @Composable fun Tune(label: String, value: Float, range: ClosedFloatingPointRange<Float>, at: (Float) -> BrushPreset) {
        TipPreviewSlider(label, value, range, at) { update(at(it)) }
    }
    @Composable fun DrawingPad(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Draw to compare", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                TipAction("Sample", Icons.Outlined.Gesture, onClick = { pad?.showSample() })
                TipAction("Clear pad", Icons.Outlined.DeleteSweep, onClick = { pad?.clear() })
            }
            if (Build.VERSION.SDK_INT >= 34) AndroidView(
                factory = { InkBrushDrawingPad(it).also { view -> pad = view; view.sampleTimingListener = { sampleMillis = it }; view.setBrush(p) } },
                update = { it.setBrush(p) }, modifier = Modifier.fillMaxWidth().weight(1f))
            else Text("Drawing with Jetpack Ink tips requires Android 14 or newer.")
        }
    }
    @Composable fun Settings(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("About", "Tip", "Stroke", "Dynamics").forEach { name ->
                    FilterChip(tab == name, { tab = name }, label = { Text(name) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            key(tab) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (usesStamps && p.spacing < .1f) {
                        Text("Dense overlapping stamps can lag during fast, broad strokes. Increasing spacing changes the mark but reduces the work.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { update(p.copy(spacing = .12f)) }) { Text("Try wider spacing") }
                    }
                    sampleMillis?.let { elapsed -> Text(
                        "Sample render: ${elapsed.toInt()} ms on this device. This small preview does not test full-canvas drawing latency.",
                        style = MaterialTheme.typography.bodySmall) }
                    when (tab) {
                        "About" -> {
                            OutlinedTextField(p.displayName, { update(p.copy(displayName = it.take(60))) }, label = { Text("Tip name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(draft.author, { draft = draft.copy(author = it.take(80)) }, label = { Text("Creator name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(draft.description, { draft = draft.copy(description = it.take(1000)) }, label = { Text("Description / sharing terms") }, modifier = Modifier.fillMaxWidth())
                            Text("Create a Jetpack Ink tip alongside your existing custom tips. Try it on the canvas to compare feel and appearance.")
                            Tune("Size (px)", p.baseSizePx, 2f..180f) { p.copy(baseSizePx = it) }
                            Tune("Opacity", p.opacity, .05f..1f) { p.copy(opacity = it) }
                        }
                        "Tip" -> {
                            Text("Texture", style = MaterialTheme.typography.titleSmall)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                InkTexture.entries.forEach { texture -> FilterChip(ink.texture == texture, {
                                    update(p.copy(ink = ink.copy(texture = texture,
                                        particles = ink.particles || texture == InkTexture.SOFT,
                                        accumulate = ink.accumulate || texture == InkTexture.SOFT)))
                                }, label = { Text(texture.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                            }
                            Tune("Width / aspect", ink.aspectRatio, .1f..1f) { p.copy(ink = ink.copy(aspectRatio = it)) }
                            Tune("Roundness", ink.rounding, 0f..1f) { p.copy(ink = ink.copy(rounding = it)) }
                            Tune("Rotation (degrees)", ink.rotationDegrees, -180f..180f) { p.copy(ink = ink.copy(rotationDegrees = it)) }
                            Text("Soft uses a smooth stamp texture. Graphite uses repeating paper grain. Image-source brushes remain available in Custom tip.", style = MaterialTheme.typography.bodySmall)
                        }
                        "Stroke" -> {
                            Row { Text("Separate stamps", Modifier.weight(1f)); Switch(usesStamps, {
                                update(p.copy(ink = ink.copy(particles = it)))
                            }, enabled = !forcedStamps) }
                            if (usesStamps) Tune("Spacing (tip widths)", p.spacing, .02f..2f) { p.copy(spacing = it) }
                            Row { Text("Build up overlapping marks", Modifier.weight(1f)); Switch(ink.accumulate || ink.texture == InkTexture.SOFT || p.pressureToOpacity.start != p.pressureToOpacity.end, {
                                update(p.copy(ink = ink.copy(accumulate = it)))
                            }, enabled = ink.texture != InkTexture.SOFT && p.pressureToOpacity.start == p.pressureToOpacity.end) }
                            Tune("Flow", ink.flow, .01f..1f) { p.copy(ink = ink.copy(flow = it)) }
                            Tune("Smoothing", p.stabilization, 0f..1f) { p.copy(stabilization = it) }
                            Text("Soft texture, buildup and pressure opacity use overlapping stamps. Higher smoothing can feel slower.", style = MaterialTheme.typography.bodySmall)
                        }
                        "Dynamics" -> {
                            Tune("Light-pressure size", p.pressureToSize.start, .01f..1f) { p.copy(pressureToSize = p.pressureToSize.copy(start = it)) }
                            Tune("Size response exponent", p.pressureToSize.exponent, .25f..2f) { p.copy(pressureToSize = p.pressureToSize.copy(exponent = it)) }
                            Tune("Light-pressure opacity", p.pressureToOpacity.start, .01f..1f) { p.copy(pressureToOpacity = p.pressureToOpacity.copy(start = it)) }
                            Tune("Opacity response exponent", p.pressureToOpacity.exponent, .25f..2f) { p.copy(pressureToOpacity = p.pressureToOpacity.copy(exponent = it)) }
                            Tune("Tilt size boost", ink.tiltSize, 0f..3f) { p.copy(ink = ink.copy(tiltSize = it)) }
                            Tune("Speed taper", p.speedTaper, 0f..1f) { p.copy(speedTaper = it) }
                            Text("Set a light-pressure value to 1 to disable it. Fingers use full pressure.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    @Composable fun Actions(modifier: Modifier = Modifier) {
        Row(modifier, horizontalArrangement = Arrangement.End) {
            TipAction("Cancel", Icons.Outlined.Close, enabled = !busy, onClick = onDismiss)
            TipAction("Save and use", Icons.Outlined.Check, enabled = !busy && p.displayName.isNotBlank(), onClick = {
                onSave(draft.copy(preset = p.copy(displayName = p.displayName.trim())))
            })
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth().fillMaxHeight(.97f), shape = MaterialTheme.shapes.large) {
            BoxWithConstraints(Modifier.padding(12.dp)) {
                val landscape = maxHeight < 450.dp && maxWidth > 480.dp
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Ink Studio", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (landscape) Actions()
                    }
                    if (landscape) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DrawingPad(Modifier.weight(.38f).fillMaxHeight())
                        Settings(Modifier.weight(.62f).fillMaxHeight())
                    } else {
                        DrawingPad(Modifier.fillMaxWidth().height(188.dp))
                        Settings(Modifier.weight(1f).fillMaxWidth())
                        Actions(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
