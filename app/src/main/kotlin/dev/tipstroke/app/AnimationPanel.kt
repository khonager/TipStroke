package dev.tipstroke.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tipstroke.core.model.*
import dev.tipstroke.drawing.android.AnimationRecordingKind
import dev.tipstroke.drawing.android.AnimationUiState

/** A narrow timeline strip; actions appear only while their menu is open. */
@Composable
internal fun AnimationPanel(
    state: AnimationUiState,
    layers: List<LayerSummary>,
    selectedLayerId: LayerId?,
    hasSelection: Boolean,
    onSelectFrame: (Int) -> Unit,
    onAddFrame: () -> Unit,
    onDuplicateFrame: () -> Unit,
    onDeleteFrame: () -> Unit,
    onMoveFrame: (Int) -> Unit,
    onExposure: (Int) -> Unit,
    onFps: (Int) -> Unit,
    onPlaybackMode: (PlaybackMode) -> Unit,
    onPlayPause: () -> Unit,
    onOnion: (Int, Int, Float) -> Unit,
    onBackground: (LayerId, Boolean) -> Unit,
    onCopyCel: () -> Unit,
    onMoveCel: () -> Unit,
    onCopySelection: () -> Unit,
    onMoveSelection: () -> Unit,
    onRecordMotion: (Int, RecordingTiming) -> Unit,
    onRecordLine: () -> Unit,
    onCancelRecording: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedLayer = layers.firstOrNull { it.id == selectedLayerId }
    var menu by remember { mutableStateOf<String?>(null) }
    var recordingMenu by remember { mutableStateOf(false) }
    var timing by remember { mutableStateOf(RecordingTiming.FIT_RANGE) }
    var recordEnd by remember(state.frames.size, state.selectedIndex) { mutableIntStateOf(state.frames.lastIndex) }
    val accent = Color(0xFFED6A5A)
    Surface(
        modifier.fillMaxWidth(.96f).widthIn(max = 860.dp), color = Color(0xF0202125),
        shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Color(0x885D626B)), shadowElevation = 8.dp,
    ) {
        Row(Modifier.height(52.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPlayPause, modifier = Modifier.width(48.dp).semantics {
                contentDescription = if (state.playing) "Pause animation" else "Play animation"
            }, contentPadding = PaddingValues(0.dp)) { Text(if (state.playing) "Ⅱ" else "▶", fontSize = 19.sp) }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                state.frames.forEachIndexed { index, frame ->
                    TextButton(onClick = { onSelectFrame(index) },
                        modifier = Modifier.height(36.dp)
                            .background(if (index == state.selectedIndex) accent else Color(0xFF34363C), RoundedCornerShape(9.dp))
                            .semantics { contentDescription = "Frame ${index + 1}, hold ${frame.exposure}" },
                        contentPadding = PaddingValues(horizontal = 11.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                        Text("${index + 1}${if (frame.exposure > 1) "·${frame.exposure}" else ""}", fontSize = 12.sp)
                    }
                }
            }
            TextButton(onClick = onAddFrame, modifier = Modifier.width(36.dp).semantics {
                contentDescription = "Add blank animation frame"
            }, contentPadding = PaddingValues(0.dp)) { Text("+", fontSize = 22.sp) }
            Box {
                TextButton(onClick = {
                    when {
                        state.recording != null -> onCancelRecording()
                        selectedLayer?.kind == LayerKind.RASTER -> onRecordLine()
                        else -> recordingMenu = true
                    }
                }, modifier = Modifier.width(44.dp).semantics {
                    contentDescription = when {
                        state.recording != null -> "Stop animation recording"
                        selectedLayer?.kind == LayerKind.RASTER -> "Record drawing live"
                        else -> "Animation recording"
                    }
                }, contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = if (state.recording != null) accent else Color.White)) {
                    Text(if (state.recording == AnimationRecordingKind.LIVE_DRAWING) "■" else if (state.recording != null) "●" else "Rec",
                        fontSize = if (state.recording != null) 17.sp else 11.sp)
                }
                DropdownMenu(expanded = recordingMenu, onDismissRequest = { recordingMenu = false }) {
                    if (state.recording != null) {
                        DropdownMenuItem(text = { Text("Stop recording") }, onClick = {
                            recordingMenu = false; onCancelRecording()
                        })
                    } else {
                        DropdownMenuItem(text = { Text("Frame ${state.selectedIndex + 1} to ${recordEnd + 1}") }, onClick = {}, enabled = false)
                        DropdownMenuItem(text = { Text("End frame −") }, onClick = {
                            recordEnd = (recordEnd - 1).coerceAtLeast(state.selectedIndex)
                        })
                        DropdownMenuItem(text = { Text("End frame +") }, onClick = {
                            recordEnd = (recordEnd + 1).coerceAtMost(state.frames.lastIndex)
                        })
                        DropdownMenuItem(text = { Text(if (timing == RecordingTiming.FIT_RANGE) "Timing: Fit range" else "Timing: Real time") },
                            onClick = { timing = if (timing == RecordingTiming.FIT_RANGE) RecordingTiming.REAL_TIME else RecordingTiming.FIT_RANGE })
                        DropdownMenuItem(text = { Text("Record image movement") }, enabled = selectedLayer?.kind == LayerKind.IMAGE,
                            onClick = { recordingMenu = false; onRecordMotion(recordEnd, timing) })
                    }
                }
            }
            Box {
                TextButton(onClick = { menu = "main" }, modifier = Modifier.width(36.dp).semantics {
                    contentDescription = "Animation options"
                }, contentPadding = PaddingValues(0.dp)) { Text("⋯", fontSize = 21.sp) }
                DropdownMenu(expanded = menu != null, onDismissRequest = { menu = null }) {
                    when (menu) {
                        "main" -> {
                            DropdownMenuItem(text = { Text("Frame actions") }, onClick = { menu = "frame" })
                            DropdownMenuItem(text = { Text("Selected layer") }, onClick = { menu = "layer" }, enabled = selectedLayer != null)
                            DropdownMenuItem(text = { Text("Playback") }, onClick = { menu = "playback" })
                            DropdownMenuItem(text = { Text("Onion skins") }, onClick = { menu = "onion" })
                        }
                        "frame" -> {
                            DropdownMenuItem(text = { Text("← Options") }, onClick = { menu = "main" })
                            DropdownMenuItem(text = { Text("Duplicate frame") }, onClick = { menu = null; onDuplicateFrame() })
                            DropdownMenuItem(text = { Text("Delete frame") }, onClick = { menu = null; onDeleteFrame() }, enabled = state.frames.size > 1)
                            DropdownMenuItem(text = { Text("Move frame left") }, onClick = { menu = null; onMoveFrame(-1) }, enabled = state.selectedIndex > 0)
                            DropdownMenuItem(text = { Text("Move frame right") }, onClick = { menu = null; onMoveFrame(1) }, enabled = state.selectedIndex < state.frames.lastIndex)
                            DropdownMenuItem(text = { Text("Hold −") }, onClick = { onExposure(state.frames[state.selectedIndex].exposure - 1) })
                            DropdownMenuItem(text = { Text("Hold +") }, onClick = { onExposure(state.frames[state.selectedIndex].exposure + 1) })
                        }
                        "layer" -> {
                            DropdownMenuItem(text = { Text("← Options") }, onClick = { menu = "main" })
                            selectedLayer?.let { layer ->
                                DropdownMenuItem(text = { Text(if (layer.id in state.backgroundLayerIds)
                                    "Make animated cel" else "Use as shared background") },
                                    onClick = { menu = null; onBackground(layer.id, layer.id !in state.backgroundLayerIds) })
                                if (layer.kind == LayerKind.RASTER) {
                                    DropdownMenuItem(text = { Text("Copy cel to new layer") }, onClick = { menu = null; onCopyCel() })
                                    DropdownMenuItem(text = { Text("Move cel to new layer") }, onClick = { menu = null; onMoveCel() })
                                    if (hasSelection) {
                                        DropdownMenuItem(text = { Text("Copy selection to new layer") }, onClick = { menu = null; onCopySelection() })
                                        DropdownMenuItem(text = { Text("Move selection to new layer") }, onClick = { menu = null; onMoveSelection() })
                                    }
                                }
                            }
                        }
                        "playback" -> {
                            DropdownMenuItem(text = { Text("← Options") }, onClick = { menu = "main" })
                            DropdownMenuItem(text = { Text("FPS − (${state.fps})") }, onClick = { onFps(state.fps - 1) })
                            DropdownMenuItem(text = { Text("FPS + (${state.fps})") }, onClick = { onFps(state.fps + 1) })
                            PlaybackMode.entries.forEach { mode ->
                                DropdownMenuItem(text = { Text("${if (state.playbackMode == mode) "✓ " else ""}${mode.name.replace('_', ' ')}") },
                                    onClick = { menu = null; onPlaybackMode(mode) })
                            }
                        }
                        "onion" -> {
                            DropdownMenuItem(text = { Text("← Options") }, onClick = { menu = "main" })
                            DropdownMenuItem(text = { Text("Previous − (${state.onionBefore})") }, onClick = {
                                onOnion(state.onionBefore - 1, state.onionAfter, state.onionOpacity)
                            })
                            DropdownMenuItem(text = { Text("Previous + (${state.onionBefore})") }, onClick = {
                                onOnion(state.onionBefore + 1, state.onionAfter, state.onionOpacity)
                            })
                            DropdownMenuItem(text = { Text("Next − (${state.onionAfter})") }, onClick = {
                                onOnion(state.onionBefore, state.onionAfter - 1, state.onionOpacity)
                            })
                            DropdownMenuItem(text = { Text("Next + (${state.onionAfter})") }, onClick = {
                                onOnion(state.onionBefore, state.onionAfter + 1, state.onionOpacity)
                            })
                            DropdownMenuItem(text = { Text("Opacity − (${(state.onionOpacity * 100).toInt()}%)") }, onClick = {
                                onOnion(state.onionBefore, state.onionAfter, state.onionOpacity - .1f)
                            })
                            DropdownMenuItem(text = { Text("Opacity + (${(state.onionOpacity * 100).toInt()}%)") }, onClick = {
                                onOnion(state.onionBefore, state.onionAfter, state.onionOpacity + .1f)
                            })
                        }
                    }
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.width(32.dp).semantics {
                contentDescription = "Close animation timeline"
            }, contentPadding = PaddingValues(0.dp)) { Text("×", fontSize = 21.sp) }
        }
    }
}
