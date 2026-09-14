package dev.tipstroke.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import dev.tipstroke.core.model.GestureSettings
import dev.tipstroke.drawing.android.DrawingLibrary

private sealed interface AppScreen {
    data object Gallery : AppScreen
    data object Settings : AppScreen
    data class Editor(val id: String, val name: String, val widthPx: Int, val heightPx: Int, val existing: Boolean) : AppScreen
}

class MainActivity : ComponentActivity() {
    private var saveActiveDrawing: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TipStrokeTheme { TipStrokeApp { saveActiveDrawing = it } } }
    }

    override fun onStop() {
        saveActiveDrawing?.invoke()
        super.onStop()
    }
}

@Composable
private fun TipStrokeApp(onSaveActionChanged: ((() -> Unit)?) -> Unit) {
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
        )
    }
}
