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
import dev.tipstroke.drawing.android.LocalDiagnostics
import dev.tipstroke.drawing.android.StylusButton
import dev.tipstroke.drawing.android.StylusButtons

internal sealed interface AppScreen {
    data object Gallery : AppScreen
    data object Settings : AppScreen
    data class Editor(val id: String, val name: String, val widthPx: Int, val heightPx: Int, val existing: Boolean) : AppScreen
}

internal class TipStrokeAppState : ViewModel() {
    var screen by mutableStateOf<AppScreen>(AppScreen.Gallery)

    fun saveState(out: Bundle) {
        val current = screen
        out.putString("screen", when (current) {
            AppScreen.Gallery -> "gallery"
            AppScreen.Settings -> "settings"
            is AppScreen.Editor -> "editor"
        })
        if (current is AppScreen.Editor) {
            out.putString("documentId", current.id)
            out.putString("documentName", current.name)
            out.putInt("widthPx", current.widthPx)
            out.putInt("heightPx", current.heightPx)
        }
    }

    fun restoreState(saved: Bundle) {
        screen = when (saved.getString("screen")) {
            "settings" -> AppScreen.Settings
            // Restoration must never create a blank replacement for a missing/failed project.
            "editor" -> saved.getString("documentId")?.let {
                AppScreen.Editor(it, saved.getString("documentName") ?: "Untitled drawing",
                    saved.getInt("widthPx", 2048), saved.getInt("heightPx", 2048), true)
            } ?: AppScreen.Gallery
            else -> AppScreen.Gallery
        }
    }
}

class MainActivity : ComponentActivity() {
    private val appState by viewModels<TipStrokeAppState>()
    private var saveActiveDrawing: (() -> Unit)? = null
    private var stylusButtonHandler: ((StylusButton) -> Unit)? = null
    private var navigationBarHiddenForDrawing = false
    private var drawingScreenActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let(appState::restoreState)
        LocalDiagnostics.record("activity create restored=${savedInstanceState != null}")
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
        LocalDiagnostics.record("activity stop changingConfigurations=$isChangingConfigurations saveAvailable=${saveActiveDrawing != null}")
        saveActiveDrawing?.invoke()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        appState.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        LocalDiagnostics.record("activity start")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyNavigationBarVisibility()
    }

    private fun setNavigationBarHiddenForDrawing(hidden: Boolean, drawing: Boolean) {
        navigationBarHiddenForDrawing = hidden
        drawingScreenActive = drawing
        applyNavigationBarVisibility()
    }

    @Suppress("DEPRECATION")
    private fun applyNavigationBarVisibility() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (drawingScreenActive) controller.hide(WindowInsets.Type.statusBars())
                else controller.show(WindowInsets.Type.statusBars())
                if (navigationBarHiddenForDrawing) controller.hide(WindowInsets.Type.navigationBars())
                else controller.show(WindowInsets.Type.navigationBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (drawingScreenActive) {
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_FULLSCREEN
            } else window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
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
    onImmersiveModeChanged: (Boolean, Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val library = remember { DrawingLibrary(context) }
    val gesturePreferences = remember { GesturePreferences(context) }
    var gestures by remember { mutableStateOf(gesturePreferences.load()) }
    val screen = appState.screen
    DisposableEffect(screen, gestures.hideNavigationBarWhileDrawing) {
        onImmersiveModeChanged(screen is AppScreen.Editor && gestures.hideNavigationBarWhileDrawing, screen is AppScreen.Editor)
        onDispose { onImmersiveModeChanged(false, false) }
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
            onGestureSettingsChange = { updated -> gestures = updated; gesturePreferences.save(updated) },
            onBackToGallery = { appState.screen = AppScreen.Gallery },
            onSaveActionChanged = onSaveActionChanged,
            onStylusButtonHandlerChanged = onStylusButtonHandlerChanged,
            onDocumentOpened = {
                if (appState.screen == current) appState.screen = current.copy(existing = true)
            },
        )
    }
}
