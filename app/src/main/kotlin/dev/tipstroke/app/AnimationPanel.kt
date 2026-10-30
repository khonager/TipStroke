package dev.tipstroke.app

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
    onReorderFrame: (Int, Int) -> Unit,
    thumbnailForFrame: (FrameId) -> Bitmap?,
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
    var fpsText by remember(state.fps) { mutableStateOf(state.fps.toString()) }
    val validFps = fpsText.toIntOrNull()?.takeIf { it in 1..60 }
    fun applyFps() { validFps?.let { onFps(it); menu = null } }
    val timeline = remember(state.frames, state.fps, state.playbackMode) {
        AnimationTimeline(state.frames, state.fps, state.playbackMode)
    }
    val selectedTick = state.frames.take(state.selectedIndex).sumOf { it.exposure }
    val frameBounds = remember { mutableStateMapOf<FrameId, Rect>() }
    var draggedFrame by remember { mutableStateOf<FrameId?>(null) }
    var dragPositionX by remember { mutableFloatStateOf(0f) }
    var scrubTick by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    val dragTarget = if (draggedFrame != null) frameBounds.minByOrNull { (_, rect) ->
        kotlin.math.abs(rect.center.x - dragPositionX)
    }?.key else null
    val accent = Color(0xFFED6A5A)
    Surface(
        modifier.fillMaxWidth(.96f).widthIn(max = 860.dp), color = Color(0xF0202125),
        shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Color(0x885D626B)), shadowElevation = 8.dp,
    ) {
        Column {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.height(58.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPlayPause, modifier = Modifier.width(48.dp).semantics {
                contentDescription = if (state.playing) "Pause animation" else "Play animation"
            }, contentPadding = PaddingValues(0.dp)) { Text(if (state.playing) "Ⅱ" else "▶", fontSize = 19.sp) }
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(state.frames, key = { _, frame -> frame.id.value }) { index, frame ->
                    val thumbnail = remember(frame.id, state.thumbnailRevision) { thumbnailForFrame(frame.id) }
                    DisposableEffect(frame.id) { onDispose { frameBounds.remove(frame.id) } }
                    val selected = index == state.selectedIndex
                    val shape = RoundedCornerShape(7.dp)
                    Box(Modifier.size(49.dp)
                        .onGloballyPositioned { frameBounds[frame.id] = it.boundsInRoot() }
                        .clickable { onSelectFrame(index) }
                        .pointerInput(frame.id, state.frames) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    draggedFrame = frame.id
                                    dragPositionX = (frameBounds[frame.id]?.left ?: 0f) + offset.x
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragPositionX += amount.x
                                },
                                onDragEnd = {
                                    val source = state.frames.indexOfFirst { it.id == frame.id }
                                    val targetId = frameBounds.minByOrNull { (_, rect) ->
                                        kotlin.math.abs(rect.center.x - dragPositionX)
                                    }?.key
                                    val target = state.frames.indexOfFirst { it.id == targetId }
                                    draggedFrame = null
                                    if (source >= 0 && target >= 0 && source != target) onReorderFrame(source, target)
                                },
                                onDragCancel = { draggedFrame = null },
                            )
                        }
                        .clip(shape)
                        .background(Color(0xFF34363C), shape)
                        .then(if (selected || frame.id == dragTarget) Modifier.border(
                            BorderStroke(2.dp, if (frame.id == dragTarget) Color.White else accent), shape,
                        ) else Modifier)
                        .semantics { contentDescription = "Frame ${index + 1}, hold ${frame.exposure}. Hold to reorder" }) {
                        if (thumbnail != null) Image(thumbnail.asImageBitmap(), null,
                            Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                        Text("${index + 1}${if (frame.exposure > 1) " ·${frame.exposure}" else ""}",
                            Modifier.align(Alignment.BottomEnd).background(Color(0xCC202125), RoundedCornerShape(topStart = 4.dp))
                                .padding(horizontal = 3.dp), color = Color.White, fontSize = 10.sp)
                    }
                }
            }
            TextButton(onClick = onAddFrame, modifier = Modifier.width(36.dp).semantics {
                contentDescription = "Add blank animation frame"
            }, contentPadding = PaddingValues(0.dp)) { Text("+", fontSize = 22.sp) }
            TextButton(onClick = onDuplicateFrame, modifier = Modifier.width(36.dp).semantics {
                contentDescription = "Duplicate selected animation frame"
            }, contentPadding = PaddingValues(0.dp)) { Text("⧉", fontSize = 20.sp) }
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
                            DropdownMenuItem(text = { Text("Playback") }, onClick = {
                                fpsText = state.fps.toString(); menu = "playback"
                            })
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
                            Row(Modifier.padding(horizontal = 12.dp).width(190.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = fpsText, onValueChange = { value ->
                                    if (value.length <= 2 && value.all(Char::isDigit)) fpsText = value
                                }, label = { Text("FPS (1–60)") }, singleLine = true,
                                    isError = fpsText.isNotEmpty() && validFps == null,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { applyFps() }),
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = { applyFps() }, enabled = validFps != null) { Text("Set") }
                            }
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
        if (timeline.totalTicks > 1) {
            Slider(value = if (scrubbing) scrubTick else selectedTick.toFloat(), onValueChange = { tick ->
                scrubbing = true
                scrubTick = tick
                onSelectFrame(timeline.frameAtTick(tick.toInt()))
            }, onValueChangeFinished = { scrubbing = false },
                valueRange = 0f..(timeline.totalTicks - 1).toFloat(),
                modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp)
                    .semantics { contentDescription = "Scrub animation, frame ${state.selectedIndex + 1} of ${state.frames.size}" })
        }
        }
    }
}
