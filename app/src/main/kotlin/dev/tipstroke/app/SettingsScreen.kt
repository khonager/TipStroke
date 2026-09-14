package dev.tipstroke.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.tipstroke.core.model.*
import kotlin.math.roundToInt

private enum class GestureSlot(val title: String, val description: String) {
    ONE_DRAG("One-finger drag", "Drag with one finger on the canvas"),
    ONE_HOLD("One-finger hold", "Touch and hold with one finger"),
    TWO_TAP("Two-finger tap", "Tap with two fingers"),
    THREE_TAP("Three-finger tap", "Tap with three fingers"),
}

@Composable
fun SettingsScreen(settings: GestureSettings, onChange: (GestureSettings) -> Unit, onBack: () -> Unit) {
    var selected by remember { mutableStateOf<GestureSlot?>(null) }
    Column(Modifier.fillMaxSize().background(Color(0xFF17181B))) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(62.dp).border(.5.dp, Color(0xFF34363A)).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { SettingsBackIcon() }
            Spacer(Modifier.width(14.dp))
            Text("Settings", color = Color(0xFFF5F5F2), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
            val wide = maxWidth >= 780.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsContent(settings, onChange, { selected = it }, Modifier.weight(if (wide) 1.15f else 1f))
                if (wide) ActionChoicePanel(selected ?: GestureSlot.ONE_DRAG, settings, onChange, Modifier.weight(.85f))
            }
            if (!wide) selected?.let { slot ->
                ActionChoiceDialog(slot, settings, onChange) { selected = null }
            }
        }
    }
}

@Composable
private fun SettingsContent(settings: GestureSettings, onChange: (GestureSettings) -> Unit, onSelect: (GestureSlot) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text("Finger actions", color = Color(0xFFF5F5F2), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Customize what touch gestures do on the canvas.", color = Color(0xFFA9ABB1), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        GestureRow(GestureSlot.ONE_DRAG, settings.oneFingerDrag, { onSelect(GestureSlot.ONE_DRAG) })
        GestureRow(GestureSlot.ONE_HOLD, settings.oneFingerHold, { onSelect(GestureSlot.ONE_HOLD) })
        GestureRow(GestureSlot.TWO_TAP, settings.twoFingerTap, { onSelect(GestureSlot.TWO_TAP) })
        GestureRow(GestureSlot.THREE_TAP, settings.threeFingerTap, { onSelect(GestureSlot.THREE_TAP) })

        if (settings.oneFingerHold == FingerAction.PICK_COLOR) {
            SettingSlider(
                "Hold delay", "${settings.holdDelayMillis} ms", settings.holdDelayMillis.toFloat(), 150f..1000f,
                { onChange(settings.copy(holdDelayMillis = it.roundToInt().toLong())) },
            )
        }
        if (listOf(settings.oneFingerDrag, settings.oneFingerHold).contains(FingerAction.SMUDGE)) {
            SettingSlider(
                "Smudge strength", "${(settings.smudgeStrength * 100).roundToInt()}%", settings.smudgeStrength, .05f..1f,
                { onChange(settings.copy(smudgeStrength = it)) },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).border(1.dp, Color(0xFF3F4146), RoundedCornerShape(15.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StylusSettingsIcon()
            Spacer(Modifier.width(12.dp))
            Column { Text("Stylus always draws", color = Color.White, fontWeight = FontWeight.Medium); Text("Touch settings never change stylus input.", color = Color(0xFFA9ABB1), fontSize = 12.sp) }
        }
        Text("Canvas", color = Color(0xFFF5F5F2), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
        Row(
            Modifier.fillMaxWidth().border(1.dp, Color(0xFF3F4146), RoundedCornerShape(15.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) { Text("Rotation lock", color = Color.White); Text("Keep the canvas in its current orientation.", color = Color(0xFFA9ABB1), fontSize = 12.sp) }
            Switch(settings.rotationLocked, { onChange(settings.copy(rotationLocked = it)) })
        }
    }
}

@Composable
private fun GestureRow(slot: GestureSlot, action: FingerAction, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 9.dp).clickable(onClick = onClick)
            .border(1.dp, Color(0xFF44464B), RoundedCornerShape(15.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(slot.title, color = Color(0xFFF4F4F1), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(slot.description, color = Color(0xFFA5A7AD), fontSize = 12.sp)
        }
        Text(action.displayName, color = Color(0xFFED6A5A), fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        ChevronIcon()
    }
}

@Composable
private fun SettingSlider(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).border(1.dp, Color(0xFF3F4146), RoundedCornerShape(15.dp)).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.White, fontSize = 13.sp)
            Text(valueLabel, color = Color(0xFFED6A5A), fontSize = 13.sp)
        }
        Slider(value, onValue, valueRange = range)
    }
}

@Composable
private fun ActionChoicePanel(slot: GestureSlot, settings: GestureSettings, onChange: (GestureSettings) -> Unit, modifier: Modifier) {
    Surface(modifier.fillMaxHeight(), color = Color(0xFF1D1E21), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF404247))) {
        Column(Modifier.padding(16.dp)) {
            Text(slot.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 14.dp))
            availableActions(slot).forEach { action -> ActionOption(action, action == actionFor(slot, settings)) { onChange(settings.withAction(slot, action)) } }
        }
    }
}

@Composable
private fun ActionChoiceDialog(slot: GestureSlot, settings: GestureSettings, onChange: (GestureSettings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(slot.title) },
        text = { Column { availableActions(slot).forEach { action -> ActionOption(action, action == actionFor(slot, settings)) { onChange(settings.withAction(slot, action)); onDismiss() } } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ActionOption(action: FingerAction, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = onClick)
            .background(if (selected) Color(0xFF3A2B2B) else Color.Transparent, RoundedCornerShape(13.dp))
            .border(1.dp, if (selected) Color(0xFFED6A5A) else Color(0xFF3F4146), RoundedCornerShape(13.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(action.displayName, color = Color.White, fontWeight = FontWeight.Medium)
            Text(actionDescription(action), color = Color(0xFFA5A7AD), fontSize = 11.sp)
        }
        RadioButton(selected, onClick)
    }
}

private fun actionFor(slot: GestureSlot, settings: GestureSettings) = when (slot) {
    GestureSlot.ONE_DRAG -> settings.oneFingerDrag
    GestureSlot.ONE_HOLD -> settings.oneFingerHold
    GestureSlot.TWO_TAP -> settings.twoFingerTap
    GestureSlot.THREE_TAP -> settings.threeFingerTap
}

private fun availableActions(slot: GestureSlot) = when (slot) {
    GestureSlot.ONE_DRAG -> FingerAction.entries
    GestureSlot.ONE_HOLD -> listOf(FingerAction.PICK_COLOR, FingerAction.UNDO, FingerAction.REDO, FingerAction.DISABLED)
    GestureSlot.TWO_TAP, GestureSlot.THREE_TAP -> listOf(FingerAction.UNDO, FingerAction.REDO, FingerAction.DISABLED)
}

private fun GestureSettings.withAction(slot: GestureSlot, action: FingerAction) = when (slot) {
    GestureSlot.ONE_DRAG -> copy(oneFingerDrag = action)
    GestureSlot.ONE_HOLD -> copy(oneFingerHold = action)
    GestureSlot.TWO_TAP -> copy(twoFingerTap = action)
    GestureSlot.THREE_TAP -> copy(threeFingerTap = action)
}

private fun actionDescription(action: FingerAction) = when (action) {
    FingerAction.NAVIGATE -> "Pan around the canvas"
    FingerAction.SMUDGE -> "Smudge and blend paint"
    FingerAction.PICK_COLOR -> "Pick a color from the canvas"
    FingerAction.UNDO -> "Undo the last step"
    FingerAction.REDO -> "Redo the last undone step"
    FingerAction.DISABLED -> "No action"
}

@Composable private fun SettingsBackIcon() { Canvas(Modifier.size(22.dp)) { val width = 2.dp.toPx(); drawLine(Color.White, Offset(size.width * .8f, size.height * .5f), Offset(size.width * .2f, size.height * .5f), width, StrokeCap.Round); drawLine(Color.White, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .46f, size.height * .24f), width, StrokeCap.Round); drawLine(Color.White, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .46f, size.height * .76f), width, StrokeCap.Round) } }
@Composable private fun ChevronIcon() { Canvas(Modifier.size(18.dp)) { val width = 1.8.dp.toPx(); drawLine(Color.White, Offset(size.width * .36f, size.height * .22f), Offset(size.width * .65f, size.height * .5f), width, StrokeCap.Round); drawLine(Color.White, Offset(size.width * .65f, size.height * .5f), Offset(size.width * .36f, size.height * .78f), width, StrokeCap.Round) } }
@Composable private fun StylusSettingsIcon() { Canvas(Modifier.size(26.dp)) { val width = 4.dp.toPx(); drawLine(Color(0xFFED6A5A), Offset(size.width * .25f, size.height * .78f), Offset(size.width * .72f, size.height * .22f), width, StrokeCap.Round); drawCircle(Color(0xFFED6A5A), 2.dp.toPx(), Offset(size.width * .2f, size.height * .83f)) } }
