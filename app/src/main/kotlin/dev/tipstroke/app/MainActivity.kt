package dev.tipstroke.app

import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import dev.tipstroke.core.model.GestureSettings
import dev.tipstroke.drawing.android.DrawingLibrary
import dev.tipstroke.drawing.android.StylusButton
import dev.tipstroke.drawing.android.StylusButtons

internal sealed interface AppScreen {
    data object Gallery : AppScreen
    data object Settings : AppScreen
    data class Editor(val id: String, val name: String, val widthPx: Int, val heightPx: Int, val existing: Boolean) : AppScreen
}

internal class TipStrokeAppState : ViewModel() {
    var screen by mutableStateOf<AppScreen>(AppScreen.Gallery)
}

class MainActivity : ComponentActivity() {
    private val appState by viewModels<TipStrokeAppState>()
    private var saveActiveDrawing: (() -> Unit)? = null
    private var stylusButtonHandler: ((StylusButton) -> Unit)? = null
    private var navigationBarHiddenForDrawing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TipStrokeTheme {
                TipStrokeApp(
                    appState = appState,
                    onSaveActionChanged = { saveActiveDrawing = it },
                    onStylusButtonHandlerChanged = { stylusButtonHandler = it },
                    onImmersiveModeChanged = ::setNavigationBarHiddenForDrawing,
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyNavigationBarVisibility()
    }

    private fun setNavigationBarHiddenForDrawing(hidden: Boolean) {
        navigationBarHiddenForDrawing = hidden
        applyNavigationBarVisibility()
    }

    @Suppress("DEPRECATION")
    private fun applyNavigationBarVisibility() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (navigationBarHiddenForDrawing) controller.hide(WindowInsets.Type.navigationBars())
                else controller.show(WindowInsets.Type.navigationBars())
            }
        } else {
            val hideFlags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            window.decorView.systemUiVisibility = if (navigationBarHiddenForDrawing) {
                window.decorView.systemUiVisibility or hideFlags
            } else {
                window.decorView.systemUiVisibility and hideFlags.inv()
            }
        }
    }
}

@Composable
private fun TipStrokeApp(
    appState: TipStrokeAppState,
    onSaveActionChanged: ((() -> Unit)?) -> Unit,
    onStylusButtonHandlerChanged: (((StylusButton) -> Unit)?) -> Unit,
    onImmersiveModeChanged: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val library = remember { DrawingLibrary(context) }
    val gesturePreferences = remember { GesturePreferences(context) }
    var gestures by remember { mutableStateOf(gesturePreferences.load()) }
    val screen = appState.screen
    DisposableEffect(screen, gestures.hideNavigationBarWhileDrawing) {
        onImmersiveModeChanged(screen is AppScreen.Editor && gestures.hideNavigationBarWhileDrawing)
        onDispose { onImmersiveModeChanged(false) }
    }
    BackHandler(enabled = screen == AppScreen.Settings) { appState.screen = AppScreen.Gallery }

    when (val current = screen) {
        AppScreen.Gallery -> GalleryScreen(
            library,
            onOpen = { drawing -> appState.screen = AppScreen.Editor(drawing.id, drawing.name, drawing.widthPx, drawing.heightPx, true) },
            onNew = { request -> appState.screen = AppScreen.Editor(request.id, request.name, request.widthPx, request.heightPx, false) },
            onSettings = { appState.screen = AppScreen.Settings },
        )
        AppScreen.Settings -> SettingsScreen(
            gestures,
            onChange = { updated: GestureSettings -> gestures = updated; gesturePreferences.save(updated) },
            onBack = { appState.screen = AppScreen.Gallery },
        )
        is AppScreen.Editor -> CanvasScreen(
            documentId = current.id, documentName = current.name,
            canvasWidthPx = current.widthPx, canvasHeightPx = current.heightPx,
            loadExisting = current.existing, library = library, gestureSettings = gestures,
            onBackToGallery = { appState.screen = AppScreen.Gallery },
            onSaveActionChanged = onSaveActionChanged,
            onStylusButtonHandlerChanged = onStylusButtonHandlerChanged,
        )
    }
}
