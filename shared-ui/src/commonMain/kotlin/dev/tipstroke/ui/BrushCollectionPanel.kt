package dev.tipstroke.ui

import androidx.compose.foundation.layout.*
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
    onSave: (BrushPreset, String) -> Unit, onDelete: (BrushPreset) -> Unit, onImport: () -> Unit, onExport: (BrushPreset) -> Unit) {
    var name by remember(current.id) { mutableStateOf(current.displayName) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tip library", style = MaterialTheme.typography.headlineSmall)
        Row { TextButton(onClick = onImport) { Text("Import") }; TextButton(onClick = { onExport(current) }) { Text("Share tip") } }
        Row { TextButton(onClick = { onSelect(BrushPreset.Blank) }) { Text("New stamp tip") }; TextButton(onClick = { onSelect(BrushPreset.BlankInk) }) { Text("New Ink tip") } }
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Tip name") }, singleLine = true)
        TextButton(onClick = { onSave(current, name) }, enabled = name.isNotBlank() && brushes.size < 200) { Text("Save a copy of current tip") }
        LazyColumn(Modifier.weight(1f)) {
            items(BrushPreset.libraryBuiltIns + brushes.map { it.preset }, key = { it.id.value }) { preset ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onSelect(preset) }, modifier = Modifier.weight(1f)) { Text(preset.displayName + if (preset.id == current.id) " ✓" else "") }
                    if (brushes.any { it.preset.id == preset.id }) TextButton(onClick = { onDelete(preset) }) { Text("Delete") }
                }
            }
        }
        Text("Select a tip, then use Brush settings to tune its shape, grain and dynamics.")
    }
}
