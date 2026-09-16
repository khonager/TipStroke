package dev.tipstroke.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import dev.tipstroke.core.model.GestureSettings
import dev.tipstroke.drawing.android.DrawingLibrary
import dev.tipstroke.drawing.android.StylusButton
import dev.tipstroke.drawing.android.StylusButtons

private sealed interface AppScreen {
    data object Gallery : AppScreen
    data object Settings : AppScreen
    data class Editor(val id: String, val name: String, val widthPx: Int, val heightPx: Int, val existing: Boolean) : AppScreen
}

class MainActivity : ComponentActivity() {
    private var saveActiveDrawing: (() -> Unit)? = null
    private var stylusButtonHandler: ((StylusButton) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TipStrokeTheme {
                TipStrokeApp(
                    onSaveActionChanged = { saveActiveDrawing = it },
                    onStylusButtonHandlerChanged = { stylusButtonHandler = it },
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val button = StylusButtons.fromKeyCode(keyCode)
        val handler = stylusButtonHandler
        if (button != null && handler != null) {
            if (event.repeatCount == 0) handler(button)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (StylusButtons.fromKeyCode(keyCode) != null && stylusButtonHandler != null) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        saveActiveDrawing?.invoke()
        super.onStop()
    }
}

@Composable
private fun TipStrokeApp(
    onSaveActionChanged: ((() -> Unit)?) -> Unit,
    onStylusButtonHandlerChanged: (((StylusButton) -> Unit)?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val library = remember { DrawingLibrary(context) }
    val gesturePreferences = remember { GesturePreferences(context) }
    var gestures by remember { mutableStateOf(gesturePreferences.load()) }
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Gallery) }
    BackHandler(enabled = screen == AppScreen.Settings) { screen = AppScreen.Gallery }

    when (val current = screen) {
        AppScreen.Gallery -> GalleryScreen(
            library,
            onOpen = { drawing -> screen = AppScreen.Editor(drawing.id, drawing.name, drawing.widthPx, drawing.heightPx, true) },
            onNew = { request -> screen = AppScreen.Editor(request.id, request.name, request.widthPx, request.heightPx, false) },
            onSettings = { screen = AppScreen.Settings },
        )
        AppScreen.Settings -> SettingsScreen(
            gestures,
            onChange = { updated: GestureSettings -> gestures = updated; gesturePreferences.save(updated) },
            onBack = { screen = AppScreen.Gallery },
        )
        is AppScreen.Editor -> CanvasScreen(
            documentId = current.id, documentName = current.name,
            canvasWidthPx = current.widthPx, canvasHeightPx = current.heightPx,
            loadExisting = current.existing, library = library, gestureSettings = gestures,
            onBackToGallery = { screen = AppScreen.Gallery },
            onSaveActionChanged = onSaveActionChanged,
            onStylusButtonHandlerChanged = onStylusButtonHandlerChanged,
        )
    }
}
