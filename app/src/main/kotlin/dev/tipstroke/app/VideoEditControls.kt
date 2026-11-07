package dev.tipstroke.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tipstroke.core.model.*
import dev.tipstroke.drawing.android.AnimationUiState
import java.util.Locale
import java.util.UUID

internal fun secondsText(us: Long) = String.format(Locale.ROOT, "%.3f", us / 1_000_000.0)
private fun parseSeconds(text: String): Long? = text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= 86_400 }?.let { (it * 1_000_000).toLong() }

@Composable
internal fun VideoEditControls(
    state: AnimationUiState, onImport: () -> Unit, onEnable: () -> Unit,
    onEdit: (VideoEdit, Long?) -> Unit, onSeek: (Long) -> Unit, onUndo: (Boolean) -> Unit,
) {
    val edit = state.videoEdit
    var selectedId by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var keyTime by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun editSafely(action: () -> Unit) {
        runCatching(action).onFailure { error = "This edit exceeds the timeline limits (1,000 clips, 10,000 keyframes, 24 hours)." }
    }
    val enabled = !state.loading && !state.playing
    if (edit == null) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TipAction("Add photo / video", Icons.Outlined.AddPhotoAlternate, onClick = onImport, enabled = enabled)
            if (state.hasMedia) TipAction("Edit clips & keyframes", Icons.Outlined.VideoSettings, onClick = onEnable, enabled = enabled)
        }
        return
    }
    val index = edit.clips.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: edit.indexAt(state.mediaPositionUs)
    val clip = edit.clips[index]
    val start = edit.startUs(index)
    val local = (state.mediaPositionUs - start).coerceIn(0, clip.durationUs)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(edit.clips, key = { _, c -> c.id }) { i, c ->
            FilterChip(selected = index == i, onClick = { selectedId = c.id; onSeek(edit.startUs(i)) },
                enabled = !state.loading, label = {
                    Text("${i + 1}. ${c.name.take(24)} · ${secondsText(c.durationUs)}s", fontSize = 11.sp)
                })
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        TipAction("Add photo / video", Icons.Outlined.AddPhotoAlternate, onClick = onImport, enabled = enabled)
        TipAction("Split here", Icons.Outlined.ContentCut, onClick = { editSafely {
            val id = UUID.randomUUID().toString()
            selectedId = id
            onEdit(edit.split(index, local, id), state.mediaPositionUs)
        } }, enabled = enabled && edit.clips.size < 1000 && local in 1 until clip.durationUs)
        TipAction("Go to time", Icons.Outlined.Schedule, onClick = { dialog = "seek" }, enabled = enabled)
        TipAction("Trim / duration", Icons.Outlined.Timer, onClick = { dialog = "trim" }, enabled = enabled)
        TipAction("Keyframe here", Icons.Outlined.Diamond, onClick = { keyTime = null; dialog = "key" }, enabled = enabled)
        TipAction("Transition", Icons.Outlined.Compare, onClick = { dialog = "transition" }, enabled = enabled && index > 0)
        TipAction("Move earlier", Icons.AutoMirrored.Outlined.KeyboardArrowLeft, onClick = { editSafely {
            val next = edit.move(index, index - 1); onEdit(next, next.startUs(index - 1))
        } }, enabled = enabled && index > 0)
        TipAction("Move later", Icons.AutoMirrored.Outlined.KeyboardArrowRight, onClick = { editSafely {
            val next = edit.move(index, index + 1); onEdit(next, next.startUs(index + 1))
        } }, enabled = enabled && index < edit.clips.lastIndex)
        TipAction("Duplicate clip", Icons.Outlined.ContentCopy, onClick = { editSafely {
            val copy = clip.copy(id = UUID.randomUUID().toString(), transitionUs = 0)
            val next = edit.normalized(edit.clips.take(index + 1) + copy + edit.clips.drop(index + 1))
            selectedId = copy.id; onEdit(next, next.startUs(index + 1))
        } }, enabled = enabled && edit.clips.size < 1000)
        TipAction("Delete clip", Icons.Outlined.DeleteOutline, onClick = { editSafely { val next = edit.delete(index); selectedId = null; onEdit(next, start.coerceAtMost(next.durationUs - 1)) } },
            enabled = enabled && edit.clips.size > 1)
        TipAction("Undo edit", Icons.AutoMirrored.Outlined.Undo, onClick = { onUndo(false) }, enabled = enabled && state.canUndoVideoEdit)
        TipAction("Redo edit", Icons.AutoMirrored.Outlined.Redo, onClick = { onUndo(true) }, enabled = enabled && state.canRedoVideoEdit)
    }
    if (clip.keyframes.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(clip.keyframes.filter { it.timeUs in 0..clip.durationUs }, key = { _, key -> key.timeUs }) { _, key ->
            AssistChip(onClick = { onSeek((start + key.timeUs).coerceAtMost(edit.durationUs - 1)); keyTime = key.timeUs; dialog = "key" },
                label = { Text("◆ ${secondsText(key.timeUs)}s", fontSize = 11.sp) })
        }
    }
    when (dialog) {
        "seek" -> {
            var position by remember { mutableStateOf(secondsText(local)) }
            val value = parseSeconds(position)?.takeIf { it < clip.durationUs }
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Go to clip time") }, text = {
                DecimalField(position, { position = it }, "Seconds within selected clip")
            }, confirmButton = { TextButton(onClick = { onSeek(start + requireNotNull(value)); dialog = null }, enabled = value != null) { Text("Seek") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        }
        "key" -> ClipKeyframeDialog(clip, keyTime ?: local, { dialog = null }) { replacement -> editSafely { onEdit(edit.replace(index, replacement), null); dialog = null } }
        "trim" -> ClipTrimDialog(clip, clip.assetId in state.photoAssetIds, state.clipSourceDurationsUs[clip.assetId] ?: (clip.sourceInUs + clip.durationUs), { dialog = null }) { replacement -> editSafely { onEdit(edit.replace(index, replacement), start); dialog = null } }
        "transition" -> {
            var duration by remember(clip.id) { mutableStateOf(secondsText(clip.transitionUs)) }
            val maximum = minOf(clip.durationUs, edit.clips[index - 1].durationUs) / 2
            val value = parseSeconds(duration)?.takeIf { it <= maximum }
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Crossfade into clip") }, text = {
                Column {
                    Text("Overlap the previous clip. Use 0 for a cut. Maximum ${secondsText(maximum)} seconds.")
                    DecimalField(duration, { duration = it }, "Crossfade seconds")
                }
            }, confirmButton = { TextButton(onClick = { editSafely {
                val next = edit.replace(index, clip.copy(transitionUs = requireNotNull(value)))
                onEdit(next, next.startUs(index)); dialog = null
            } }, enabled = value != null) { Text("Apply transition") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        }
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Cannot edit timeline") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}

@Composable
private fun DecimalField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, { if (it.length <= 16) onChange(it) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ClipTrimDialog(clip: MediaClip, photo: Boolean, sourceDurationUs: Long, dismiss: () -> Unit, apply: (MediaClip) -> Unit) {
    fun exact(us: Long) = String.format(Locale.ROOT, "%.6f", us / 1_000_000.0)
    var from by remember { mutableStateOf(exact(clip.sourceInUs)) }
    var to by remember { mutableStateOf(exact(if (photo) clip.durationUs else clip.sourceInUs + clip.durationUs)) }
    val first = parseSeconds(from)
    val last = parseSeconds(to)
    val replacement = runCatching {
        if (photo) {
            require(last != null && last > 0)
            clip.copy(durationUs = last, transitionUs = minOf(clip.transitionUs, last / 2))
        } else {
            require(first != null && last != null && first < last && last <= sourceDurationUs)
            clip.trimSource(first, last)
        }
    }.getOrNull()
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (photo) "Photo duration" else "Trim clip") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (photo) "Choose how long to show this photo. Values beyond the last keyframe hold that pose." else
                "Keep this range of the original source (${secondsText(sourceDurationUs)}s). You can restore trimmed footage here after reopening. Split twice and delete the middle clip to remove an interior section.")
            if (!photo) DecimalField(from, { from = it }, "Source in (seconds)")
            DecimalField(to, { to = it }, if (photo) "Photo duration (seconds)" else "Source out (seconds)")
        }
    }, confirmButton = { TextButton(onClick = { apply(requireNotNull(replacement)) }, enabled = replacement != null) { Text("Apply trim") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun ClipKeyframeDialog(clip: MediaClip, time: Long, dismiss: () -> Unit, apply: (MediaClip) -> Unit) {
    val initial = clip.poseAt(time)
    var x by remember { mutableStateOf((initial.x * 100).toString()) }
    var y by remember { mutableStateOf((initial.y * 100).toString()) }
    var scale by remember { mutableStateOf((initial.scale * 100).toString()) }
    var rotation by remember { mutableStateOf(initial.rotation.toString()) }
    var opacity by remember { mutableStateOf((initial.opacity * 100).toString()) }
    var easing by remember { mutableStateOf(clip.keyframes.lastOrNull { it.timeUs <= time }?.easing ?: ClipEasing.LINEAR) }
    val pose = runCatching { ClipPose(requireNotNull(x.toFloatOrNull()) / 100, requireNotNull(y.toFloatOrNull()) / 100,
        requireNotNull(scale.toFloatOrNull()) / 100, requireNotNull(rotation.toFloatOrNull()), requireNotNull(opacity.toFloatOrNull()) / 100) }.getOrNull()
    AlertDialog(onDismissRequest = dismiss, title = { Text("Keyframe · ${secondsText(time)}s") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set two keyframes to animate between their values. Position is relative to the canvas center; 100% scale fits the source.")
            DecimalField(x, { x = it }, "Horizontal position (%)")
            DecimalField(y, { y = it }, "Vertical position (%)")
            DecimalField(scale, { scale = it }, "Scale (%)")
            DecimalField(rotation, { rotation = it }, "Rotation (degrees)")
            DecimalField(opacity, { opacity = it }, "Opacity (%)")
            Text("Interpolation to next keyframe")
            Row(Modifier.horizontalScroll(rememberScrollState())) { ClipEasing.entries.forEach { option ->
                FilterChip(easing == option, { easing = option }, label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) })
            } }
            if (clip.keyframes.any { it.timeUs == time }) TextButton(onClick = {
                apply(clip.copy(keyframes = clip.keyframes.filterNot { it.timeUs == time }))
            }) { Text("Delete keyframe") }
        }
    }, confirmButton = { TextButton(onClick = { apply(clip.withKeyframe(ClipKeyframe(time, requireNotNull(pose), easing))) },
        enabled = pose != null && (clip.keyframes.size < 1000 || clip.keyframes.any { it.timeUs == time })) { Text("Save keyframe") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
