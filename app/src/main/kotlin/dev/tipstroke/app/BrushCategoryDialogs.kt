package dev.tipstroke.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun BrushCategoryManager(categories: List<BrushCategory>, onChange: (List<BrushCategory>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tip categories") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            categories.forEachIndexed { index, category ->
                TextButton(onClick = { renaming = category.id; name = category.name }) { Text(category.name) }
                Row {
                    TextButton(enabled = index > 0, onClick = { onChange(categories.toMutableList().apply { add(index - 1, removeAt(index)) }) }) { Text("Earlier") }
                    TextButton(enabled = index < categories.lastIndex, onClick = { onChange(categories.toMutableList().apply { add(index + 1, removeAt(index)) }) }) { Text("Later") }
                    TextButton(onClick = { onChange(categories - category); if (renaming == category.id) { renaming = null; name = "" } }) { Text("Remove") }
                }
            }
            Text("Removing a category keeps its tips.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(if (renaming == null) "New category" else "Rename category") }, singleLine = true)
            TextButton(enabled = name.isNotBlank() && (renaming != null || categories.size < 64) && categories.none { it.id != renaming && it.name.equals(name.trim(), true) }, onClick = {
                onChange(if (renaming == null) categories + BrushCategory(name = name.trim()) else categories.map { if (it.id == renaming) it.copy(name = name.trim()) else it })
                renaming = null; name = ""
            }) { Text(if (renaming == null) "Create" else "Rename") }
            if (renaming != null) TextButton(onClick = { renaming = null; name = "" }) { Text("New category") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
internal fun BrushCategoryMembership(brush: LibraryBrush, categories: List<BrushCategory>, onChange: (List<BrushCategory>) -> Unit, onDismiss: () -> Unit) {
    val id = brush.preset.id.value
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Categories for ${brush.preset.displayName}") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            if (categories.isEmpty()) Text("Create a category using Categories in the library first.")
            categories.forEach { category ->
                val checked = id in category.brushIds
                Row {
                    Checkbox(checked, enabled = checked || category.brushIds.size < 200, onCheckedChange = { include ->
                        onChange(categories.map { if (it.id == category.id) it.copy(brushIds = if (include) it.brushIds + id else it.brushIds - id) else it })
                    })
                    Text(category.name, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
