package dev.tipstroke.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.tipstroke.core.model.BrushPreset
import kotlinx.coroutines.launch

@Composable
internal fun BrushCategoryManager(
    categories: List<BrushCategory>, onChange: (List<BrushCategory>) -> Unit,
    onEditMembers: (String) -> Unit, onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tip groups") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(if (renaming == null) "New group" else "Rename group") }, singleLine = true)
            Row {
                TextButton(
                    enabled = name.isNotBlank() && (renaming != null || categories.size < 64) && categories.none { it.id != renaming && it.name.equals(name.trim(), true) },
                    onClick = {
                        onChange(if (renaming == null) categories + BrushCategory(name = name.trim()) else categories.map { if (it.id == renaming) it.copy(name = name.trim()) else it })
                        renaming = null; name = ""
                    },
                ) { Text(if (renaming == null) "Create group" else "Save name") }
                if (renaming != null) TextButton(onClick = { renaming = null; name = "" }) { Text("Cancel") }
            }
            categories.forEachIndexed { index, category ->
                Text(category.name, style = MaterialTheme.typography.titleMedium)
                GroupToggle("Show ${category.name} in tip bar", category.showInDock) { show ->
                    onChange(categories.map { if (it.id == category.id) it.copy(showInDock = show) else it })
                }
                FlowRow {
                    ActionIconButton(onClick = { onEditMembers(category.id) }, label = "Edit tips in ${category.name}", icon = Icons.Outlined.Checklist)
                    ActionIconButton(onClick = {
                        renaming = category.id; name = category.name
                        scope.launch { scroll.animateScrollTo(0) }
                    }, label = "Rename ${category.name}", icon = Icons.Outlined.Edit)
                    ActionIconButton(enabled = index > 0, onClick = { onChange(categories.toMutableList().apply { add(index - 1, removeAt(index)) }) }, label = "Move ${category.name} earlier", icon = Icons.Outlined.KeyboardArrowUp)
                    ActionIconButton(enabled = index < categories.lastIndex, onClick = { onChange(categories.toMutableList().apply { add(index + 1, removeAt(index)) }) }, label = "Move ${category.name} later", icon = Icons.Outlined.KeyboardArrowDown)
                    ActionIconButton(onClick = { onChange(categories - category); if (renaming == category.id) { renaming = null; name = "" } }, label = "Remove ${category.name}", icon = Icons.Outlined.Delete)
                }
                HorizontalDivider()
            }
            if (categories.isNotEmpty()) Text("Removing a group keeps its tips.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
internal fun BrushGroupMembers(
    group: BrushCategory, tips: List<BrushPreset>, onChange: (BrushCategory) -> Unit, onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tips in ${group.name}") }, text = {
        Column(Modifier.heightIn(max = 480.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Search tips") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn {
                items(tips.filter { it.displayName.contains(query, ignoreCase = true) }, key = { it.id.value }) { tip ->
                    val id = tip.id.value
                    val included = id in group.brushIds
                    GroupToggle(tip.displayName, included, enabled = included || group.brushIds.size < 200) { include ->
                        onChange(group.copy(brushIds = if (include) group.brushIds + id else group.brushIds - id))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun GroupToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label)
    }
}

@Composable
internal fun BrushCategoryMembership(brush: LibraryBrush, categories: List<BrushCategory>, onChange: (List<BrushCategory>) -> Unit, onDismiss: () -> Unit) {
    val id = brush.preset.id.value
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Categories for ${brush.preset.displayName}") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            if (categories.isEmpty()) Text("Create a group using Manage tip groups in the library.")
            categories.forEach { category ->
                val checked = id in category.brushIds
                GroupToggle(category.name, checked, enabled = checked || category.brushIds.size < 200) { include ->
                    onChange(categories.map { if (it.id == category.id) it.copy(brushIds = if (include) it.brushIds + id else it.brushIds - id) else it })
                }
            }
        }
    }, confirmButton = { ActionIconButton(onClick = onDismiss, label = "Done", icon = Icons.Outlined.Check) })
}
