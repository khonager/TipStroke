package dev.tipstroke.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*

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
                    ActionIconButton(enabled = index > 0, onClick = { onChange(categories.toMutableList().apply { add(index - 1, removeAt(index)) }) }, label = "Earlier", icon = Icons.Outlined.ArrowBack)
                    ActionIconButton(enabled = index < categories.lastIndex, onClick = { onChange(categories.toMutableList().apply { add(index + 1, removeAt(index)) }) }, label = "Later", icon = Icons.Outlined.ArrowForward)
                    ActionIconButton(onClick = { onChange(categories - category); if (renaming == category.id) { renaming = null; name = "" } }, label = "Remove", icon = Icons.Outlined.Delete)
                }
            }
            Text("Removing a category keeps its tips.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(if (renaming == null) "New category" else "Rename category") }, singleLine = true)
            ActionIconButton(enabled = name.isNotBlank() && (renaming != null || categories.size < 64) && categories.none { it.id != renaming && it.name.equals(name.trim(), true) }, onClick = {
                onChange(if (renaming == null) categories + BrushCategory(name = name.trim()) else categories.map { if (it.id == renaming) it.copy(name = name.trim()) else it })
                renaming = null; name = ""
            }, label = if (renaming == null) "Create category" else "Rename category", icon = if (renaming == null) Icons.Outlined.CreateNewFolder else Icons.Outlined.Edit)
            if (renaming != null) ActionIconButton(onClick = { renaming = null; name = "" }, label = "New category", icon = Icons.Outlined.CreateNewFolder)
        }
    }, confirmButton = { ActionIconButton(onClick = onDismiss, label = "Done", icon = Icons.Outlined.Check) })
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
    }, confirmButton = { ActionIconButton(onClick = onDismiss, label = "Done", icon = Icons.Outlined.Check) })
}
