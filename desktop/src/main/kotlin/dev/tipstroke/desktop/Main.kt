package dev.tipstroke.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.tipstroke.core.model.BrushPreset
import java.awt.FileDialog
import java.io.File
import javax.swing.JOptionPane
import kotlin.math.roundToInt

private val Workspace = Color(0xFF16171A)
private val Panel = Color(0xFF202126)
private val PanelRaised = Color(0xFF292A30)
private val Accent = Color(0xFFFF6659)
private val MutedText = Color(0xFFA9AAB2)

fun main() = application {
    val windowState = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.Center),
        size = DpSize(1280.dp, 820.dp),
    )
    Window(
        onCloseRequest = ::exitApplication,
        title = "TipStroke Desktop",
        state = windowState,
    ) {
        window.minimumSize = java.awt.Dimension(900, 620)
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent,
                background = Workspace,
                surface = Panel,
                onPrimary = Color.White,
                onBackground = Color.White,
                onSurface = Color.White,
            ),
        ) {
            TipStrokeDesktop(window)
        }
    }
}

@Composable
private fun TipStrokeDesktop(window: ComposeWindow) {
    val canvasHandle = remember { CanvasHandle() }
    var selectedTool by remember { mutableStateOf(DesktopTool.PENCIL) }
    var selectedColor by remember { mutableStateOf(Color(0xFF1C1C1E)) }
    var history by remember { mutableStateOf(HistoryState(canUndo = false, canRedo = false)) }
    var documentLabel by remember { mutableStateOf("2048 × 2048") }
    val sizes = remember {
        mutableStateMapOf(
            DesktopTool.PENCIL to BrushPreset.Pencil.baseSizePx,
            DesktopTool.INK to BrushPreset.Ink.baseSizePx,
            DesktopTool.AIRBRUSH to BrushPreset.Airbrush.baseSizePx,
            DesktopTool.ERASER to 48f,
        )
    }
    val opacities = remember {
        mutableStateMapOf(
            DesktopTool.PENCIL to BrushPreset.Pencil.opacity,
            DesktopTool.INK to BrushPreset.Ink.opacity,
            DesktopTool.AIRBRUSH to BrushPreset.Airbrush.opacity,
            DesktopTool.ERASER to 1f,
        )
    }

    Column(Modifier.fillMaxSize().background(Workspace)) {
        TopBar(
            documentLabel = documentLabel,
            history = history,
            onNew = {
                val result = JOptionPane.showConfirmDialog(
                    window,
                    "Start a new 2048 × 2048 canvas? Unsaved pixels will be discarded.",
                    "New canvas",
                    JOptionPane.OK_CANCEL_OPTION,
                )
                if (result == JOptionPane.OK_OPTION) canvasHandle.canvas?.newDocument()
            },
            onOpen = {
                chooseFile(window, FileDialog.LOAD, "Open PNG")?.let { file ->
                    runCatching { canvasHandle.canvas?.openImage(file) }
                        .onFailure { showError(window, "Could not open image", it) }
                }
            },
            onExport = {
                chooseFile(window, FileDialog.SAVE, "Export PNG", "tipstroke.png")?.let { chosen ->
                    val file = if (chosen.extension.equals("png", ignoreCase = true)) chosen
                    else File(chosen.parentFile, "${chosen.name}.png")
                    runCatching { check(canvasHandle.canvas?.exportPng(file) == true) }
                        .onFailure { showError(window, "Could not export PNG", it) }
                }
            },
            onUndo = { canvasHandle.canvas?.undo() },
            onRedo = { canvasHandle.canvas?.redo() },
            onFit = { canvasHandle.canvas?.fitToView() },
        )

        Row(Modifier.weight(1f).fillMaxWidth()) {
            ToolRail(selectedTool = selectedTool, onSelected = { selectedTool = it })

            SwingPanel(
                factory = {
                    DesktopDrawingCanvas().also { canvas ->
                        canvasHandle.canvas = canvas
                        canvas.onHistoryChanged = { history = it }
                        canvas.onDocumentChanged = { width, height -> documentLabel = "$width × $height" }
                    }
                },
                update = { canvas ->
                    canvas.selectedTool = selectedTool
                    canvas.brushSize = sizes.getValue(selectedTool)
                    canvas.brushOpacity = opacities.getValue(selectedTool)
                    canvas.brushColor = java.awt.Color(
                        selectedColor.red,
                        selectedColor.green,
                        selectedColor.blue,
                        selectedColor.alpha,
                    )
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )

            BrushPanel(
                size = sizes.getValue(selectedTool),
                opacity = opacities.getValue(selectedTool),
                selectedColor = selectedColor,
                erasing = selectedTool == DesktopTool.ERASER,
                onSizeChanged = { sizes[selectedTool] = it },
                onOpacityChanged = { opacities[selectedTool] = it },
                onColorChanged = { selectedColor = it },
            )
        }

        StatusBar()
    }
}

@Composable
private fun TopBar(
    documentLabel: String,
    history: HistoryState,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp).background(Panel).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("TipStroke", color = Color.White, fontSize = 19.sp)
        Text("Desktop", color = Accent, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        CompactButton("New", onClick = onNew)
        CompactButton("Open PNG", onClick = onOpen)
        CompactButton("Export PNG", onClick = onExport, accent = true)
        Spacer(Modifier.weight(1f))
        Text(documentLabel, color = MutedText, fontSize = 12.sp)
        CompactButton("Undo", enabled = history.canUndo, onClick = onUndo)
        CompactButton("Redo", enabled = history.canRedo, onClick = onRedo)
        CompactButton("Fit", onClick = onFit)
    }
}

@Composable
private fun ToolRail(selectedTool: DesktopTool, onSelected: (DesktopTool) -> Unit) {
    Column(
        modifier = Modifier.width(112.dp).fillMaxHeight().background(Panel).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("TOOLS", color = MutedText, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
        DesktopTool.entries.forEach { tool ->
            val selected = selectedTool == tool
            Surface(
                color = if (selected) Color(0xFFF2F2F4) else PanelRaised,
                contentColor = if (selected) Color(0xFF202126) else Color.White,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp).clickable { onSelected(tool) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(tool.label, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Space + drag\nto pan", color = MutedText, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun BrushPanel(
    size: Float,
    opacity: Float,
    selectedColor: Color,
    erasing: Boolean,
    onSizeChanged: (Float) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onColorChanged: (Color) -> Unit,
) {
    val swatches = listOf(
        Color(0xFF1C1C1E), Color(0xFF6C6D73), Color(0xFFF4F4F2),
        Color(0xFFFF6659), Color(0xFFFFB84D), Color(0xFF4CCB8A),
        Color(0xFF54A8FF), Color(0xFF956DFF), Color(0xFFE75BA8),
    )
    Column(
        modifier = Modifier.width(208.dp).fillMaxHeight().background(Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (erasing) "ERASER" else "BRUSH", color = MutedText, fontSize = 11.sp)
        LabelValue("Size", "${size.roundToInt()} px")
        Slider(value = size, onValueChange = onSizeChanged, valueRange = 1f..256f)
        LabelValue("Opacity", "${(opacity * 100).roundToInt()}%")
        Slider(value = opacity, onValueChange = onOpacityChanged, valueRange = .01f..1f)

        Text("COLOR", color = if (erasing) MutedText.copy(alpha = .45f) else MutedText, fontSize = 11.sp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { color ->
                        val selected = color == selectedColor && !erasing
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(color, CircleShape)
                                .then(
                                    if (selected) Modifier.border(3.dp, Accent, CircleShape)
                                    else Modifier.border(1.dp, Color.White.copy(alpha = .16f), CircleShape),
                                )
                                .clickable(enabled = !erasing) { onColorChanged(color) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "Scroll: pan\nCtrl + scroll: zoom\nMiddle/right drag: pan",
            color = MutedText,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, fontSize = 13.sp)
        Text(value, color = Accent, fontSize = 13.sp)
    }
}

@Composable
private fun StatusBar() {
    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp).background(Color(0xFF111215)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(Color(0xFF4CCB8A), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("Native desktop preview", color = MutedText, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("Mouse • trackpad • tablet pointer", color = MutedText, fontSize = 11.sp)
    }
}

@Composable
private fun CompactButton(
    label: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accent) Accent else PanelRaised,
            contentColor = Color.White,
            disabledContainerColor = PanelRaised.copy(alpha = .45f),
            disabledContentColor = MutedText.copy(alpha = .45f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.sp)
    }
}

private class CanvasHandle {
    var canvas: DesktopDrawingCanvas? = null
}

private fun chooseFile(owner: ComposeWindow, mode: Int, title: String, defaultName: String? = null): File? {
    val dialog = FileDialog(owner, title, mode)
    if (defaultName != null) dialog.file = defaultName
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(directory, file)
}

private fun showError(owner: ComposeWindow, title: String, throwable: Throwable) {
    JOptionPane.showMessageDialog(
        owner,
        throwable.message ?: throwable::class.simpleName ?: "Unknown error",
        title,
        JOptionPane.ERROR_MESSAGE,
    )
}
