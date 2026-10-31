package dev.tipstroke.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tipstroke.core.model.*
import dev.tipstroke.drawing.android.BrushDrawingPad
import dev.tipstroke.drawing.android.BrushSourcePad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun BrushCreatorDialog(initial: LibraryBrush, busy: Boolean, message: String?, onSave: (LibraryBrush) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    if (draft.preset.stamp == null) {
        LegacyBrushCreatorDialog(draft, busy, message, onSave, onDismiss, onConvert = {
            draft = draft.copy(preset = draft.preset.copy(schemaVersion = BrushPreset.CURRENT_SCHEMA_VERSION,
                stamp = StampBrush(), spacing = .15f))
        })
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("About") }
    var sourceTarget by remember { mutableStateOf("Shape") }
    var sourceDrawing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pad by remember { mutableStateOf<BrushDrawingPad?>(null) }
    val p = draft.preset
    val s = requireNotNull(p.stamp)
    fun update(value: BrushPreset) { draft = draft.copy(preset = value) }
    fun stamp(value: StampBrush) { update(draft.preset.copy(stamp = value)) }
    fun source(texture: BrushTexture, target: String = sourceTarget) {
        val current = requireNotNull(draft.preset.stamp)
        stamp(if (target == "Shape") current.copy(shape = StampShape.IMAGE, shapeTexture = texture)
            else current.copy(grain = StampGrain.IMAGE, grainTexture = texture))
    }
    val importSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            val target = sourceTarget
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    BrushSourceImporter.read(requireNotNull(context.contentResolver.openInputStream(uri)))
                } }
                importing = false
                result.onSuccess { source(it, target); error = null }.onFailure { error = it.message ?: "Could not read image." }
            }
        }
    }
    Dialog(onDismissRequest = { if (!busy && !importing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 1000.dp).fillMaxWidth().fillMaxHeight(.97f), shape = MaterialTheme.shapes.large) {
            BoxWithConstraints(Modifier.padding(16.dp)) {
                val compact = maxHeight < 450.dp
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Brush Studio", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Draw to test", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { pad?.showSample() }, contentPadding = PaddingValues(0.dp)) { Text("Sample") }
                        TextButton(onClick = { pad?.clear() }, contentPadding = PaddingValues(0.dp)) { Text("Clear pad") }
                    }
                    AndroidView(factory = { BrushDrawingPad(it).also { view -> pad = view; view.setBrush(p) } },
                        update = { it.setBrush(p) }, modifier = Modifier.fillMaxWidth().height(if (compact) 72.dp else 140.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("About", "Shape", "Grain", "Stroke path", "Dynamics", "Color").forEach { name ->
                            FilterChip(tab == name, { tab = name }, label = { Text(name) })
                        }
                    }
                    if (busy || importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    (error ?: message)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    key(tab) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (tab) {
                                "About" -> {
                                    OutlinedTextField(p.displayName, { update(p.copy(displayName = it.take(60))) }, label = { Text("Brush name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(draft.author, { draft = draft.copy(author = it.take(80)) }, label = { Text("Creator name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(draft.description, { draft = draft.copy(description = it.take(1000)) }, label = { Text("Description / sharing terms") }, modifier = Modifier.fillMaxWidth())
                                    Text("Start with a shape, add grain, then tune how the marks travel along your stroke. Draw your own sources or import images.", style = MaterialTheme.typography.bodyMedium)
                                    StampSlider("Size (px)", p.baseSizePx, 2f..180f) { update(p.copy(baseSizePx = it)) }
                                    StampSlider("Opacity", p.opacity, .05f..1f) { update(p.copy(opacity = it)) }
                                }
                                "Shape" -> {
                                    ChoiceRow(StampShape.entries.filter { it != StampShape.IMAGE || s.shapeTexture != null }, s.shape, { stamp(s.copy(shape = it)) })
                                    s.shapeTexture?.takeIf { s.shape == StampShape.IMAGE }?.let { TextureSwatch(it) }
                                    SourceActions(importing, { sourceTarget = "Shape"; importSource.launch(arrayOf("image/*")) },
                                        { sourceTarget = "Shape"; sourceDrawing = true }, s.shapeTexture?.let { { stamp(s.copy(shapeTexture = it.inverted())) } },
                                        s.shapeTexture?.let { { stamp(s.copy(shapeTexture = it.rotated())) } })
                                    Text("White paints; black cuts out. Transparent images use their alpha channel. Invert opaque dark-on-white artwork.", style = MaterialTheme.typography.bodySmall)
                                    if (s.shape != StampShape.IMAGE) StampSlider("Edge hardness", p.hardness, 0f..1f) { update(p.copy(hardness = it)) }
                                    StampSlider("Width / aspect", s.aspectRatio, .1f..1f) { stamp(s.copy(aspectRatio = it)) }
                                    StampSlider("Rotation (degrees)", s.rotationDegrees, -180f..180f) { stamp(s.copy(rotationDegrees = it)) }
                                    Row { Text("Follow stroke direction", Modifier.weight(1f)); Switch(s.followDirection, { stamp(s.copy(followDirection = it)) }) }
                                    StampSlider("Rotation scatter (degrees)", s.rotationJitter, 0f..180f) { stamp(s.copy(rotationJitter = it)) }
                                    StampSlider("Stamps at each step", s.count.toFloat(), 1f..8f, steps = 6) { stamp(s.copy(count = it.roundToInt())) }
                                }
                                "Grain" -> {
                                    ChoiceRow(StampGrain.entries.filter { it != StampGrain.IMAGE || s.grainTexture != null }, s.grain, { stamp(s.copy(grain = it)) })
                                    s.grainTexture?.takeIf { s.grain == StampGrain.IMAGE }?.let { TextureSwatch(it) }
                                    SourceActions(importing, { sourceTarget = "Grain"; importSource.launch(arrayOf("image/*")) },
                                        { sourceTarget = "Grain"; sourceDrawing = true }, s.grainTexture?.let { { stamp(s.copy(grainTexture = it.inverted())) } },
                                        s.grainTexture?.let { { stamp(s.copy(grainTexture = it.rotated())) } })
                                    Text("Grain repeats inside the shape. Canvas keeps it fixed like paper; Stamp moves it with each mark.", style = MaterialTheme.typography.bodySmall)
                                    ChoiceRow(GrainPlacement.entries, s.grainPlacement, { stamp(s.copy(grainPlacement = it)) })
                                    StampSlider("Grain scale (px)", s.grainScale, 2f..512f) { stamp(s.copy(grainScale = it)) }
                                    StampSlider("Grain depth", s.grainDepth, 0f..1f) { stamp(s.copy(grainDepth = it)) }
                                }
                                "Stroke path" -> {
                                    StampSlider("Spacing (tip widths)", p.spacing, .02f..2f) { update(p.copy(spacing = it)) }
                                    StampSlider("Position scatter", s.scatter, 0f..2f) { stamp(s.copy(scatter = it)) }
                                    StampSlider("Flow per stamp", s.flow, .01f..1f) { stamp(s.copy(flow = it)) }
                                    StampSlider("Start taper length (px)", s.startTaperPx, 0f..500f) { stamp(s.copy(startTaperPx = it)) }
                                    StampSlider("Fade-out distance (px)", s.fadeOutPx, 0f..2000f) { stamp(s.copy(fadeOutPx = it)) }
                                    Text("Zero taper or fade disables it. Flow builds as stamps overlap.", style = MaterialTheme.typography.bodySmall)
                                }
                                "Dynamics" -> {
                                    StampSlider("Light-pressure size", p.pressureToSize.start, .01f..1f) { update(p.copy(pressureToSize = p.pressureToSize.copy(start = it))) }
                                    StampSlider("Size response exponent", p.pressureToSize.exponent, .25f..2f) { update(p.copy(pressureToSize = p.pressureToSize.copy(exponent = it))) }
                                    StampSlider("Light-pressure opacity", p.pressureToOpacity.start, .01f..1f) { update(p.copy(pressureToOpacity = p.pressureToOpacity.copy(start = it))) }
                                    StampSlider("Opacity response exponent", p.pressureToOpacity.exponent, .25f..2f) { update(p.copy(pressureToOpacity = p.pressureToOpacity.copy(exponent = it))) }
                                    StampSlider("Speed taper", p.speedTaper, 0f..1f) { update(p.copy(speedTaper = it)) }
                                    StampSlider("Tilt size boost", s.tiltSize, 0f..3f) { stamp(s.copy(tiltSize = it)) }
                                    StampSlider("Size randomness", s.sizeJitter, 0f..1f) { stamp(s.copy(sizeJitter = it)) }
                                    StampSlider("Opacity randomness", s.opacityJitter, 0f..1f) { stamp(s.copy(opacityJitter = it)) }
                                    Text("Set a light-pressure value to 1 to disable that response. Fingers use full pressure.", style = MaterialTheme.typography.bodySmall)
                                }
                                "Color" -> {
                                    StampSlider("Hue randomness", s.hueJitter, 0f..1f) { stamp(s.copy(hueJitter = it)) }
                                    StampSlider("Saturation randomness", s.saturationJitter, 0f..1f) { stamp(s.copy(saturationJitter = it)) }
                                    Text("Variation is applied to the selected canvas color for each stamp.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !busy && !importing, onClick = onDismiss) { Text("Cancel") }
                        Button(enabled = !busy && !importing && p.displayName.isNotBlank(), onClick = { onSave(draft.copy(preset = p.copy(displayName = p.displayName.trim()))) }) { Text("Save and use") }
                    }
                }
            }
        }
    }
    if (sourceDrawing) {
        var view by remember { mutableStateOf<BrushSourcePad?>(null) }
        var erasing by remember { mutableStateOf(false) }
        var sourceSize by remember { mutableFloatStateOf(18f) }
        AlertDialog(onDismissRequest = { sourceDrawing = false }, title = { Text("Draw $sourceTarget source") }, text = {
            Column {
                AndroidView(factory = { BrushSourcePad(it).also { view = it; it.setTexture(if (sourceTarget == "Shape") s.shapeTexture else s.grainTexture) } }, update = { it.erasing = erasing; it.sizePx = sourceSize }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                Text("Paint width · ${sourceSize.roundToInt()} px")
                Slider(sourceSize, { sourceSize = it }, valueRange = 1f..64f)
                Row { TextButton(onClick = { erasing = !erasing }) { Text(if (erasing) "Paint" else "Erase") }; TextButton(onClick = { view?.clear() }) { Text("Clear") } }
            }
        }, confirmButton = { TextButton(onClick = { view?.texture()?.let { source(it) }; sourceDrawing = false }) { Text("Use source") } },
            dismissButton = { TextButton(onClick = { sourceDrawing = false }) { Text("Cancel") } })
    }
}

@Composable
private fun SourceActions(busy: Boolean, onImport: () -> Unit, onDraw: () -> Unit, onInvert: (() -> Unit)?, onRotate: (() -> Unit)?) {
    Row(Modifier.horizontalScroll(rememberScrollState())) { TextButton(enabled = !busy, onClick = onImport) { Text("Import image") }; TextButton(onClick = onDraw) { Text("Draw source") }; onInvert?.let { TextButton(onClick = it) { Text("Invert") } }; onRotate?.let { TextButton(onClick = it) { Text("Rotate") } } }
}

@Composable
private fun <T> ChoiceRow(values: List<T>, selected: T, onValue: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value -> FilterChip(selected == value, { onValue(value) }, label = { Text(value.toString().lowercase().replaceFirstChar { it.uppercase() }) }) }
    }
}

@Composable
private fun TextureSwatch(texture: BrushTexture) {
    val bitmap = remember(texture) {
        android.graphics.Bitmap.createBitmap(texture.width, texture.height, android.graphics.Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(texture.width * texture.height) { i -> val v = texture.coverage(i % texture.width, i / texture.width); android.graphics.Color.rgb(v, v, v) }
            setPixels(pixels, 0, texture.width, 0, 0, texture.width, texture.height)
        }
    }
    DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
    Image(bitmap.asImageBitmap(), "Brush source", modifier = Modifier.size(96.dp).background(Color.Black))
}

@Composable
private fun StampSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onValue: (Float) -> Unit) {
    Text("$label · ${"%.2f".format(value)}", style = MaterialTheme.typography.bodyMedium)
    Slider(value.coerceIn(range), onValue, valueRange = range, steps = steps, modifier = Modifier.fillMaxWidth())
}
