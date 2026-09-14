package dev.tipstroke.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import dev.tipstroke.core.model.*
import dev.tipstroke.drawing.android.*
import kotlin.math.roundToInt

@Composable
fun CanvasScreen() {
    var surface by remember { mutableStateOf<DrawingSurface?>(null) }
    var brush by remember { mutableStateOf(BrushPreset.Ink) }
    var erasing by remember { mutableStateOf(false) }
    var size by remember { mutableFloatStateOf(28f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var color by remember { mutableStateOf(RgbaColor(.05f, .05f, .06f)) }
    var debug by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(CanvasDiagnostics()) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }

    fun sync() { surface?.settings?.apply { this.brush = brush; sizePx = size; this.opacity = opacity; this.color = color; this.erasing = erasing; this.debug = debug } }
    LaunchedEffect(brush, erasing, size, opacity, color, debug, surface) { sync() }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val portrait = maxHeight > maxWidth
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> DrawingSurface(context).also { view ->
                surface = view
                view.diagnosticsListener = { diagnostics = it }
                view.historyListener = { undo, redo -> canUndo = undo; canRedo = redo }
            } },
        )

        TopBar(
            canUndo = canUndo, canRedo = canRedo, debug = debug,
            onUndo = { surface?.undo() }, onRedo = { surface?.redo() },
            onReset = { surface?.resetView() }, onDebug = { debug = !debug },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
        )

        BrushRail(
            selected = brush, erasing = erasing,
            onBrush = { brush = it; erasing = false; size = it.baseSizePx; opacity = it.opacity },
            onEraser = { erasing = !erasing },
            modifier = Modifier.align(if (portrait) Alignment.BottomStart else Alignment.CenterStart)
                .then(if (portrait) Modifier.navigationBarsPadding().padding(12.dp) else Modifier.padding(start = 20.dp)),
            horizontal = portrait,
        )

        Controls(
            size = size, opacity = opacity, color = color,
            onSize = { size = it }, onOpacity = { opacity = it }, onColor = { color = it },
            horizontal = portrait,
            modifier = Modifier.align(if (portrait) Alignment.BottomEnd else Alignment.CenterEnd)
                .then(if (portrait) Modifier.navigationBarsPadding().padding(12.dp) else Modifier.padding(end = 20.dp)),
        )

        Text("${(diagnostics.zoom * 100).roundToInt()}%", color = Color(0xFFD8D9DC), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 22.dp, bottom = if (portrait) 116.dp else 16.dp))

        if (debug) DebugOverlay(diagnostics, Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 22.dp, bottom = if (portrait) 116.dp else 16.dp))
    }
}

@Composable private fun TopBar(canUndo: Boolean, canRedo: Boolean, debug: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, onReset: () -> Unit, onDebug: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(54.dp).background(Color(0xE617181B)).border(0.5.dp, Color(0xFF34363A)), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tip", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF4F4F2))
            Text("Stroke", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFED6A5A))
        }
        Spacer(Modifier.weight(1f))
        IconAction("Undo", enabled = canUndo, onClick = onUndo) { UndoIcon() }
        IconAction("Redo", enabled = canRedo, onClick = onRedo) { RedoIcon() }
        TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Fit", fontSize = 13.sp) }
        Switch(checked = debug, onCheckedChange = { onDebug() }, modifier = Modifier.scale(.72f).semantics { contentDescription = "Debug overlay" })
        Spacer(Modifier.width(18.dp))
    }
}

@Composable private fun BrushRail(selected: BrushPreset, erasing: Boolean, onBrush: (BrushPreset) -> Unit, onEraser: () -> Unit, horizontal: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val content: @Composable RowScope.() -> Unit = {
        BrushPreset.builtIns.forEach { preset -> ToolButton(preset.displayName, selected.id == preset.id && !erasing, { onBrush(preset) }, icon = when (preset.engine) { BrushEngine.PENCIL -> ToolGlyph.PENCIL; BrushEngine.INK -> ToolGlyph.INK; BrushEngine.AIRBRUSH -> ToolGlyph.AIRBRUSH }) }
        ToolButton("Eraser", erasing, onEraser, ToolGlyph.ERASER)
    }
    if (horizontal) Row(modifier.background(Color(0xED202125), shape).border(1.dp, Color(0xFF45474D), shape).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
    else Column(modifier.background(Color(0xED202125), shape).border(1.dp, Color(0xFF45474D), shape).padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BrushPreset.builtIns.forEach { preset -> ToolButton(preset.displayName, selected.id == preset.id && !erasing, { onBrush(preset) }, icon = when (preset.engine) { BrushEngine.PENCIL -> ToolGlyph.PENCIL; BrushEngine.INK -> ToolGlyph.INK; BrushEngine.AIRBRUSH -> ToolGlyph.AIRBRUSH }) }
        ToolButton("Eraser", erasing, onEraser, ToolGlyph.ERASER)
    }
}

private enum class ToolGlyph { PENCIL, INK, AIRBRUSH, ERASER }

@Composable private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit, icon: ToolGlyph) {
    val bg by animateColorAsState(if (selected) Color(0xFFF4F4F2) else Color.Transparent, label = "tool")
    val fg = if (selected) Color(0xFF17181B) else Color(0xFFF1F1EF)
    Column(Modifier.size(width = 68.dp, height = 72.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable(onClick = onClick).semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.size(28.dp)) {
            when (icon) {
                ToolGlyph.PENCIL -> { rotate(-40f) { drawRoundRect(fg, Offset(size.width*.42f, 1f), androidx.compose.ui.geometry.Size(size.width*.2f, size.height*.82f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)); drawPath(Path().apply { moveTo(size.width*.42f, size.height*.82f); lineTo(size.width*.62f, size.height*.82f); lineTo(size.width*.52f, size.height); close() }, fg) } }
                ToolGlyph.INK -> { drawPath(Path().apply { moveTo(size.width*.18f,size.height*.82f); cubicTo(size.width*.25f,size.height*.35f,size.width*.7f,size.height*.2f,size.width*.82f,size.height*.08f); cubicTo(size.width*.74f,size.height*.5f,size.width*.55f,size.height*.9f,size.width*.18f,size.height*.82f); close() }, fg) }
                ToolGlyph.AIRBRUSH -> { for (i in 0..4) for (j in 0..4) drawCircle(fg.copy(alpha = .25f + .1f*j), 1.4.dp.toPx(), Offset(5.dp.toPx()+i*4.dp.toPx(), 5.dp.toPx()+j*4.dp.toPx())) }
                ToolGlyph.ERASER -> { rotate(-40f) { drawRoundRect(fg, Offset(size.width*.25f,size.height*.18f), androidx.compose.ui.geometry.Size(size.width*.5f,size.height*.65f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx())) } }
            }
        }
        Spacer(Modifier.height(5.dp)); Text(label, color = fg, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable private fun Controls(size: Float, opacity: Float, color: RgbaColor, onSize: (Float) -> Unit, onOpacity: (Float) -> Unit, onColor: (RgbaColor) -> Unit, horizontal: Boolean, modifier: Modifier = Modifier) {
    var paletteOpen by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(color.toCompose()).border(2.dp, Color.White, CircleShape).clickable { paletteOpen = !paletteOpen }.semantics { contentDescription = "Current color" })
        if (paletteOpen) {
            Spacer(Modifier.height(8.dp)); Row(Modifier.background(Color(0xED202125), RoundedCornerShape(18.dp)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(RgbaColor(.05f,.05f,.06f), RgbaColor(.93f,.42f,.35f), RgbaColor(.2f,.45f,.9f), RgbaColor(.18f,.65f,.48f), RgbaColor(1f,1f,1f)).forEach { option -> Box(Modifier.size(30.dp).clip(CircleShape).background(option.toCompose()).border(if (option == color) 2.dp else 1.dp, Color.White, CircleShape).clickable { onColor(option); paletteOpen = false }) }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (horizontal) Row(Modifier.width(220.dp).background(Color(0xED202125), RoundedCornerShape(18.dp)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledSlider("Size", size, 2f..180f, onSize, Modifier.weight(1f)); LabeledSlider("Opacity", opacity, .05f..1f, onOpacity, Modifier.weight(1f))
        } else Column(Modifier.width(88.dp).background(Color(0xED202125), RoundedCornerShape(22.dp)).padding(vertical = 14.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            VerticalSlider("Size", size, 2f..180f, onSize); Spacer(Modifier.height(18.dp)); VerticalSlider("Opacity", opacity, .05f..1f, onOpacity)
        }
    }
}

@Composable private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit, modifier: Modifier = Modifier) { Column(modifier) { Text(label, fontSize = 10.sp, color = Color(0xFFC3C5CA)); Slider(value, onValue, valueRange = range) } }
@Composable private fun VerticalSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) { Text(label, fontSize = 11.sp, color = Color(0xFFE7E7E5)); Slider(value, onValue, valueRange = range, modifier = Modifier.height(170.dp).width(40.dp).graphicsLayer { rotationZ = -90f }) }

@Composable private fun IconAction(label: String, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) { IconButton(onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) { icon() } }
@Composable private fun UndoIcon() = ArcArrow(false)
@Composable private fun RedoIcon() = ArcArrow(true)
@Composable private fun ArcArrow(mirror: Boolean) { Canvas(Modifier.size(23.dp).graphicsLayer { scaleX = if (mirror) -1f else 1f }) { val path = Path().apply { moveTo(size.width*.85f,size.height*.72f); cubicTo(size.width*.85f,size.height*.3f,size.width*.45f,size.height*.22f,size.width*.24f,size.height*.42f); moveTo(size.width*.24f,size.height*.42f); lineTo(size.width*.28f,size.height*.17f); moveTo(size.width*.24f,size.height*.42f); lineTo(size.width*.48f,size.height*.43f) }; drawPath(path, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) } }

@Composable private fun DebugOverlay(d: CanvasDiagnostics, modifier: Modifier = Modifier) { Text("${d.fps.roundToInt()} fps  •  ${d.tool.lowercase()}  •  p ${"%.2f".format(d.pressure)}  •  tilt ${"%.2f".format(d.tiltRadians)}\n${d.sampleRateHz.roundToInt()} Hz  •  ${d.allocatedTiles} tiles  •  ${d.dirtyTiles} dirty  •  ${d.undoBytes / 1024} KiB undo", modifier.background(Color(0xE617181B), RoundedCornerShape(10.dp)).padding(10.dp), color = Color(0xFFD8D9DC), fontSize = 11.sp, lineHeight = 16.sp) }

private fun RgbaColor.toCompose() = Color(red, green, blue, alpha)
