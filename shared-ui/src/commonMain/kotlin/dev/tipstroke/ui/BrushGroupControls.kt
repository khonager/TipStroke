package dev.tipstroke.ui

import dev.tipstroke.core.brush.BrushCategory

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

enum class GroupAction { MEMBERS, RENAME, EARLIER, LATER, REMOVE }

@Composable
fun BrushCategoryManager(
    categories: List<BrushCategory>, onChange: (List<BrushCategory>) -> Unit,
    onEditMembers: (String) -> Unit, onDismiss: () -> Unit,
    action: @Composable (GroupAction, String, Boolean, () -> Unit) -> Unit,
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
                FlowRow {
                    action(GroupAction.MEMBERS, "Edit tips in ${category.name}", true) { onEditMembers(category.id) }
                    action(GroupAction.RENAME, "Rename ${category.name}", true) {
                        renaming = category.id; name = category.name
                        scope.launch { scroll.animateScrollTo(0) }
                    }
                    action(GroupAction.EARLIER, "Move ${category.name} earlier", index > 0) { onChange(categories.toMutableList().apply { add(index - 1, removeAt(index)) }) }
                    action(GroupAction.LATER, "Move ${category.name} later", index < categories.lastIndex) { onChange(categories.toMutableList().apply { add(index + 1, removeAt(index)) }) }
                    action(GroupAction.REMOVE, "Remove ${category.name}", true) { onChange(categories - category); if (renaming == category.id) { renaming = null; name = "" } }
                }
                HorizontalDivider()
            }
            if (categories.isNotEmpty()) Text("Removing a group keeps its tips.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
fun BrushGroupMembers(
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
                    GroupToggle(tip.displayName, included, enabled = included || group.brushIds.size < 200, tip = tip) { include ->
                        onChange(group.copy(brushIds = if (include) group.brushIds + id else group.brushIds - id))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
fun GroupToggle(label: String, checked: Boolean, enabled: Boolean = true, tip: BrushPreset? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        if (tip != null) TipSwatch(tip, Modifier.size(36.dp))
        Text(label)
    }
}
