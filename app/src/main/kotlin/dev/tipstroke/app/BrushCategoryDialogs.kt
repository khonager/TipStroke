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
import dev.tipstroke.ui.GroupToggle

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
