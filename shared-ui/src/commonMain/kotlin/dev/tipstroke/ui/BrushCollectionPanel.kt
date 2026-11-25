package dev.tipstroke.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.brush.LibraryBrush
import dev.tipstroke.core.model.*

@Composable
fun BrushCollectionPanel(brushes: List<LibraryBrush>, current: BrushPreset, onSelect: (BrushPreset) -> Unit,
    onSave: (BrushPreset, String) -> Unit, onDelete: (BrushPreset) -> Unit, onImport: () -> Unit, onExport: (BrushPreset) -> Unit,
    pins: List<String> = emptyList(), onPins: ((List<String>) -> Unit)? = null,
    groups: Map<String, List<String>> = emptyMap(), onGroups: ((Map<String, List<String>>) -> Unit)? = null,
    onManageGroups: (() -> Unit)? = null) {
    var name by remember(current.id) { mutableStateOf(current.displayName) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf<String?>(null) }
    var organize by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf("") }
    val presets = BrushPreset.libraryBuiltIns + brushes.map { it.preset }
    val visible = when {
        favoritesOnly -> pins.mapNotNull { id -> presets.find { it.id.value == id } }
        group != null -> presets.filter { it.id.value in groups[group].orEmpty() }
        else -> presets
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tip library", style = MaterialTheme.typography.headlineSmall)
        Row { TextButton(onClick = onImport) { Text("Import") }; TextButton(onClick = { onExport(current) }) { Text("Share tip") } }
        Row { TextButton(onClick = { onSelect(BrushPreset.Blank) }) { Text("New stamp tip") }; TextButton(onClick = { onSelect(BrushPreset.BlankInk) }) { Text("New Ink tip") } }
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Tip name") }, singleLine = true)
        TextButton(onClick = { onSave(current, name) }, enabled = name.isNotBlank() && brushes.size < 200) { Text("Save a copy of current tip") }
        if (onPins != null && onGroups != null) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(!favoritesOnly && group == null, { favoritesOnly = false; group = null }, { Text("All") })
                FilterChip(favoritesOnly, { favoritesOnly = true; group = null }, { Text("Favorites") })
                groups.keys.sorted().forEach { title ->
                    FilterChip(group == title, { group = title; favoritesOnly = false }, { Text(title) })
                }
                if (onManageGroups != null) TextButton(onClick = onManageGroups) { Text("Manage tip groups") }
                TextButton(onClick = { organize = true }) { Text("Organize tip") }
            }
            if (organize) AlertDialog(onDismissRequest = { organize = false }, title = { Text(current.displayName) },
                text = {
                    LazyColumn {
                        item {
                            val pinned = current.id.value in pins
                            TextButton(onClick = { onPins(if (pinned) pins - current.id.value else pins + current.id.value) }) {
                                Text(if (pinned) "Remove from favorites" else "Add to favorites")
                            }
                        }
                        items(groups.keys.sorted()) { title ->
                            val ids = groups.getValue(title)
                            val included = current.id.value in ids
                            TextButton(enabled = included || ids.size < 200, onClick = { onGroups(groups + (title to if (included) ids - current.id.value else ids + current.id.value)) }) {
                                Text((if (included) "✓ " else "") + title)
                            }
                        }
                        item {
                            OutlinedTextField(newGroup, { newGroup = it.take(80) }, label = { Text("New category") }, singleLine = true)
                            TextButton(enabled = newGroup.isNotBlank() && groups.keys.none { it.equals(newGroup.trim(), true) } && groups.size < 64, onClick = {
                                onGroups(groups + (newGroup.trim() to listOf(current.id.value))); newGroup = ""
                            }) { Text("Create with current tip") }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { organize = false }) { Text("Done") } })
        }
        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.id.value }) { preset ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onSelect(preset) }, modifier = Modifier.weight(1f)) {
                        TipSwatch(preset, Modifier.size(36.dp))
                        TipName(preset, preset.id == current.id, preset.id.value in pins, Modifier.padding(start = 6.dp))
                    }
                    if (brushes.any { it.preset.id == preset.id }) TextButton(onClick = { onDelete(preset) }) { Text("Delete") }
                }
            }
        }
        Text("Select a tip, then use Brush settings to tune its shape, grain and dynamics.")
    }
}
