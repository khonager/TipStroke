package dev.tipstroke.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.brush.BrushCategory
import dev.tipstroke.core.model.BrushPreset

class TipPickerPosition {
    var section by mutableStateOf("Favorites")
    var directory by mutableStateOf(false)
    var query by mutableStateOf("")
    var groupQuery by mutableStateOf("")
    val groupsScroll = LazyListState()
    var tipsScroll by mutableStateOf(LazyGridState())
    fun open(value: String) {
        if (section != value) { query = ""; tipsScroll = LazyGridState() }
        section = value
        directory = false
    }
}

@Composable
fun TipPicker(position: TipPickerPosition, eraser: Boolean, selectedId: String, tips: List<BrushPreset>,
    favorites: List<String>, pins: List<String>, limit: Int, groups: List<BrushCategory>,
    saveFavorites: (List<String>) -> Unit, savePins: (List<String>) -> Unit, saveGroups: (List<BrushCategory>) -> Unit,
    onPick: (BrushPreset) -> Unit, onLibrary: () -> Unit, onClose: () -> Unit,
    canRename: (BrushPreset) -> Boolean, onRename: (BrushPreset, String) -> Unit) {
    val byId = remember(tips) { tips.associateBy { it.id.value } }
    var replacement by remember { mutableStateOf<BrushPreset?>(null) }
    var editGroup by remember { mutableStateOf<BrushCategory?>(null) }
    var rename by remember { mutableStateOf<BrushPreset?>(null) }
    var membership by remember { mutableStateOf<BrushPreset?>(null) }
    val currentGroup = groups.find { it.id == position.section }
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (position.directory) "Tip groups" else if (eraser) "Choose eraser tip" else "Choose drawing tip",
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onLibrary) { Text("Library") }
            TextButton(onClick = onClose) { Text("Close") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("All","Favorites","Pinned","Groups").forEach { tab ->
                val active = if (tab == "Groups") position.directory || currentGroup != null else !position.directory && position.section == tab
                TextButton(onClick = { if (tab == "Groups") position.directory = true else position.open(tab) }) {
                    Text(tab, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (position.directory) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(position.groupQuery, { position.groupQuery = it }, Modifier.weight(1f), singleLine = true, label = { Text("Search groups") })
                TextButton(enabled = groups.size < 64, onClick = { editGroup = BrushCategory(name = "") }) { Text("New group") }
            }
            LazyColumn(state = position.groupsScroll, modifier = Modifier.weight(1f)) {
                items(groups.filter { it.name.contains(position.groupQuery, true) }, key = { it.id }) { group ->
                    Row(Modifier.fillMaxWidth().clickable { position.open(group.id) }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, Modifier.weight(1f))
                        group.brushIds.mapNotNull(byId::get).take(2).forEach { TipSwatch(it, Modifier.size(28.dp)) }
                        Text("  ${group.brushIds.size}")
                        TextButton(onClick = { editGroup = group }) { Text("Edit") }
                    }
                }
            }
        } else {
            currentGroup?.let { group ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { position.directory = true }) { Text("‹ Groups") }
                    Text(group.name, Modifier.weight(1f))
                    TextButton(onClick = { editGroup = group }) { Text("Edit group") }
                }
            }
            OutlinedTextField(position.query, { position.query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search tips") })
            val ids = when (position.section) { "Favorites" -> favorites; "Pinned" -> pins; else -> currentGroup?.brushIds }
            val shown = (ids?.mapNotNull(byId::get) ?: tips).filter { it.displayName.contains(position.query, true) }
            if (shown.isEmpty()) Text(if (position.query.isBlank()) "No tips here yet" else "No matching tips", Modifier.padding(12.dp))
            LazyVerticalGrid(GridCells.Adaptive(145.dp), state = position.tipsScroll, modifier = Modifier.weight(1f)) {
                items(shown, key = { it.id.value }) { tip ->
                    var menu by remember { mutableStateOf(false) }
                    val id = tip.id.value
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable { onPick(tip) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            TipSwatch(tip, Modifier.size(36.dp))
                            Text(tip.displayName + if (selectedId == id) " ✓" else "", Modifier.padding(start = 5.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.width(36.dp).semantics { contentDescription = "Actions for ${tip.displayName}" }) { Text("⋯") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(enabled = id in favorites || favorites.size < 200, text = { Text(if (id in favorites) "Remove from favorites" else "Add to favorites") }, onClick = {
                                    menu = false; saveFavorites(if (id in favorites) favorites - id else favorites + id)
                                })
                                DropdownMenuItem(text = { Text(if (id in pins) "Unpin from dock" else "Pin to dock") }, onClick = {
                                    menu = false
                                    if (id in pins) savePins(pins - id) else if (pins.size >= limit) replacement = tip else savePins(pins + id)
                                })
                                if (id in pins) {
                                    val index = pins.indexOf(id)
                                    for (delta in listOf(-1,1)) DropdownMenuItem(text = { Text(if (delta < 0) "Pin earlier" else "Pin later") },
                                        enabled = index + delta in pins.indices, onClick = {
                                            menu = false; savePins(pins.toMutableList().apply { add(index+delta, removeAt(index)) })
                                        })
                                }
                                DropdownMenuItem(text = { Text("Edit groups") }, onClick = { menu = false; membership = tip })
                                if (canRename(tip)) DropdownMenuItem(text = { Text("Rename tip") }, onClick = { menu = false; rename = tip })
                            }
                        }
                    }
                }
            }
        }
    }
    replacement?.let { next ->
        AlertDialog(onDismissRequest = { replacement = null }, title = { Text("Replace a pinned tip") }, text = {
            LazyColumn {
                item { Text("Choose a pin to replace with ${next.displayName}.") }
                items(pins.mapNotNull(byId::get)) { old ->
                    TipRow(old) { savePins(pins.map { if (it == old.id.value) next.id.value else it }); replacement = null }
                }
            }
        }, confirmButton = { TextButton(onClick = { replacement = null }) { Text("Cancel") } })
    }
    rename?.let { tip ->
        var name by remember(tip) { mutableStateOf(tip.displayName) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename tip") }, text = {
            Column { TipSwatch(tip, Modifier.size(48.dp)); OutlinedTextField(name, { name = it.take(60) }, singleLine = true) }
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onRename(tip, name.trim()); rename = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }
    membership?.let { tip ->
        val id = tip.id.value
        var included by remember(tip) { mutableStateOf(groups.filter { id in it.brushIds }.map { it.id }.toSet()) }
        AlertDialog(onDismissRequest = { membership = null }, title = { Row { TipSwatch(tip, Modifier.size(32.dp)); Text(tip.displayName) } },
            text = { LazyColumn { items(groups) { group -> GroupToggle(group.name, group.id in included, id in group.brushIds || group.brushIds.size < 200) {
                included = if (it) included + group.id else included - group.id
            } } } }, confirmButton = { TextButton(onClick = {
                saveGroups(groups.map { g -> g.copy(brushIds = if (g.id !in included) g.brushIds-id else (g.brushIds+id).distinct()) }); membership = null
            }) { Text("Done") } }, dismissButton = { TextButton(onClick = { membership = null }) { Text("Cancel") } })
    }
    editGroup?.let { group ->
        TipGroupEditor(group, tips, groups, { editGroup = null }, { next ->
            saveGroups(next); editGroup = null
            if (currentGroup != null && next.none { it.id == currentGroup.id }) position.directory = true
        })
    }
}

@Composable
private fun TipRow(tip: BrushPreset, suffix: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        TipSwatch(tip, Modifier.size(36.dp)); Text(tip.displayName, Modifier.weight(1f).padding(start = 6.dp)); Text(suffix)
    }
}

@Composable
private fun TipGroupEditor(original: BrushCategory, tips: List<BrushPreset>, groups: List<BrushCategory>,
    close: () -> Unit, save: (List<BrushCategory>) -> Unit) {
    var draft by remember(original) { mutableStateOf(original) }
    var query by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val originalIndex = groups.indexOfFirst { it.id == original.id }
    val existing = originalIndex >= 0
    var groupIndex by remember(original) { mutableIntStateOf(originalIndex) }
    val cancel = { if (draft != original || groupIndex != originalIndex) discard = true else close() }
    val byId = remember(tips) { tips.associateBy { it.id.value } }
    AlertDialog(onDismissRequest = cancel, title = { Text(if (existing) "Edit group" else "New group") }, text = {
        LazyColumn {
            item {
                OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(80)) }, label = { Text("Group name") }, singleLine = true)
                if (existing) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Group ${groupIndex + 1} of ${groups.size}", Modifier.weight(1f))
                    TextButton(enabled = groupIndex > 0, onClick = { groupIndex-- }) { Text("Earlier") }
                    TextButton(enabled = groupIndex < groups.lastIndex, onClick = { groupIndex++ }) { Text("Later") }
                }
                OutlinedTextField(query, { query = it }, label = { Text("Search tips") }, singleLine = true)
                Text("In this group (${draft.brushIds.size})")
            }
            items(draft.brushIds.mapNotNull(byId::get).filter { it.displayName.contains(query,true) }, key = { it.id.value }) { tip ->
                val index = draft.brushIds.indexOf(tip.id.value)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { TipRow(tip, "−") { draft = draft.copy(brushIds = draft.brushIds - tip.id.value) } }
                    for (delta in listOf(-1,1)) IconButton(enabled = index+delta in draft.brushIds.indices, onClick = {
                        draft = draft.copy(brushIds = draft.brushIds.toMutableList().apply { add(index+delta, removeAt(index)) })
                    }) { Text(if (delta < 0) "↑" else "↓") }
                }
            }
            item { Text("Available tips") }
            items(tips.filter { it.id.value !in draft.brushIds && it.displayName.contains(query,true) }) { tip ->
                TipRow(tip, "+") { if (draft.brushIds.size < 200) draft = draft.copy(brushIds = draft.brushIds + tip.id.value) }
            }
            if (existing) item { TextButton(onClick = { deleting = true }) { Text("Delete group") } }
        }
    }, confirmButton = { TextButton(enabled = draft.name.isNotBlank() && groups.none { it.id != draft.id && it.name.equals(draft.name.trim(),true) }, onClick = {
        val clean = draft.copy(name = draft.name.trim())
        save(if (existing) groups.toMutableList().apply { removeAt(originalIndex); add(groupIndex, clean) } else groups + clean)
    }) { Text("Done") } }, dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } })
    if (discard || deleting) AlertDialog(onDismissRequest = { discard = false; deleting = false },
        title = { Text(if (deleting) "Delete group and keep its tips?" else "Discard group edits?") },
        confirmButton = { TextButton(onClick = { if (deleting) save(groups.filterNot { it.id == original.id }) else close() }) { Text(if (deleting) "Delete" else "Discard") } },
        dismissButton = { TextButton(onClick = { discard = false; deleting = false }) { Text("Keep editing") } })
}
