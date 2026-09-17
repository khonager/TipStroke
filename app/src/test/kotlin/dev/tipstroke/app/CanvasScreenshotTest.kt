package dev.tipstroke.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.MotionEvent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.tipstroke.core.model.GestureSettings
import dev.tipstroke.core.model.LayerId
import dev.tipstroke.core.model.LayerKind
import dev.tipstroke.core.model.LayerSummary
import dev.tipstroke.drawing.android.DrawingLibrary
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasScreenshotTest {
    @Test fun renderLandscapeCanvasForVisualReview() = render(2560, 1600, "tipstroke-landscape.png") { CanvasScreen(initialLayersOpen = true) }

    @Test fun renderPortraitCanvasForVisualReview() = render(1600, 2560, "tipstroke-portrait.png") { CanvasScreen() }

    @Test fun renderCompactLandscapeCanvasForVisualReview() = render(1600, 720, "tipstroke-compact-landscape.png") { CanvasScreen() }

    @Test fun renderColorPickerForVisualReview() = render(2560, 1600, "tipstroke-color-picker.png") {
        CanvasScreen(initialColorPickerOpen = true)
    }

    @Test fun renderPortraitColorPickerForVisualReview() = render(1600, 2560, "tipstroke-color-picker-portrait.png") {
        CanvasScreen(initialColorPickerOpen = true)
    }

    @Test fun renderLiveColorPickerSelectionForVisualReview() = render(
        2560, 1600, "tipstroke-color-picker-live-selection.png",
        afterLayout = { root -> tap(root, 1780f, 580f) },
    ) {
        CanvasScreen(initialColorPickerOpen = true)
    }

    @Test fun renderDismissedColorPickerForVisualReview() = render(
        2560, 1600, "tipstroke-color-picker-dismissed.png",
        afterLayout = { root -> tap(root, 800f, 800f) },
    ) {
        CanvasScreen(initialColorPickerOpen = true)
    }

    @Test fun renderClassicColorPickerForVisualReview() = render(
        2560, 1600, "tipstroke-color-picker-classic.png",
        afterLayout = { root -> tap(root, 2000f, 356f) },
    ) {
        CanvasScreen(initialColorPickerOpen = true)
    }

    @Test fun renderPopulatedColorPickerForVisualReview() = render(2560, 1600, "tipstroke-color-picker-populated.png") {
        Box(Modifier.fillMaxSize().background(Color(0xFF17181B)), contentAlignment = Alignment.Center) {
            ColorPickerPanel(
                initialColor = dev.tipstroke.core.model.RgbaColor(.72f, .18f, .5f),
                drawingPalette = listOf(
                    dev.tipstroke.core.model.RgbaColor(.12f, .55f, .82f),
                    dev.tipstroke.core.model.RgbaColor(.88f, .64f, .16f),
                    dev.tipstroke.core.model.RgbaColor(.28f, .72f, .42f),
                ),
                paletteColorCount = 3,
                colorHistory = List(10) { index ->
                    dev.tipstroke.core.model.RgbaColor(index / 12f, .25f + index / 30f, .72f - index / 24f)
                },
                onColorSelected = {}, onPaletteColorCountChanged = {}, onClearHistory = {},
            )
        }
    }

    @Test fun renderSelectionControlsForVisualReview() = render(1600, 2560, "tipstroke-selection.png") {
        CanvasScreen(initialSelectionOpen = true)
    }

    @Test fun renderGalleryForVisualReview() = render(2560, 1600, "tipstroke-gallery.png") { activity ->
        GalleryScreen(galleryWithExamples(activity), {}, {}, {})
    }

    @Test fun renderGalleryPortraitForVisualReview() = render(1600, 2560, "tipstroke-gallery-portrait.png") { activity ->
        GalleryScreen(galleryWithExamples(activity), {}, {}, {})
    }

    @Test fun renderGalleryPhoneForVisualReview() = render(800, 1280, "tipstroke-gallery-phone.png") { activity ->
        GalleryScreen(galleryWithExamples(activity), {}, {}, {})
    }

    @Test fun renderSettingsForVisualReview() = render(2560, 1600, "tipstroke-settings.png") {
        SettingsScreen(GestureSettings(), {}, {})
    }

    @Test fun renderSettingsPortraitForVisualReview() = render(1600, 2560, "tipstroke-settings-portrait.png") {
        SettingsScreen(GestureSettings(), {}, {})
    }

    @Test fun renderSettingsPhoneForVisualReview() = render(800, 1280, "tipstroke-settings-phone.png") {
        SettingsScreen(GestureSettings(), {}, {})
    }

    @Test fun renderExportForVisualReview() = render(2560, 1600, "tipstroke-export.png") {
        Box(Modifier.fillMaxSize().background(Color(0xFF17181B)), contentAlignment = Alignment.Center) {
            CanvasScreen()
            ExportDrawingSheet("Ink details", 2048, 2048, onDismiss = {}, onExport = {})
        }
    }

    @Test fun renderImageTransformControlsForVisualReview() = render(2560, 1600, "tipstroke-image-transform.png") {
        Box(Modifier.fillMaxSize().background(Color(0xFF17181B)), contentAlignment = Alignment.Center) {
            val imageId = LayerId("reference")
            LayersPanel(
                layers = listOf(LayerSummary(imageId, "Reference photo.png", LayerKind.IMAGE, true, .82f, 4032, 3024, .46f)),
                selectedId = imageId,
                imageTransforming = true,
                onSelect = {}, onToggleVisibility = {}, onOpacity = {}, onImageScale = {},
                onFitImage = {}, onOriginalImageSize = {}, onImageTransforming = {},
                onAddPaint = {}, onImportImage = {}, onMoveForward = {}, onMoveBackward = {}, onDelete = {},
            )
        }
    }

    private fun render(
        width: Int,
        height: Int,
        fileName: String,
        afterLayout: (View) -> Unit = {},
        content: @Composable (ComponentActivity) -> Unit,
    ) {
        val activity = Robolectric.buildActivity(ScreenshotActivity::class.java).setup().get()
        activity.setContent { TipStrokeTheme { content(activity) } }
        shadowOf(activity.mainLooper).idle()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        shadowOf(activity.mainLooper).idle()
        afterLayout(root)
        shadowOf(activity.mainLooper).idle()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val output = File("build/qa/$fileName")
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(output.length() > 10_000) { "Screenshot render was unexpectedly empty" }
    }

    private fun tap(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).also {
            it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        })
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 16L, MotionEvent.ACTION_UP, x, y, 0).also {
            it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        })
    }

    private fun galleryWithExamples(activity: ComponentActivity): DrawingLibrary {
        val library = DrawingLibrary(activity)
        val colors = listOf(0xFFE9B8A4.toInt(), 0xFF8DB9C8.toInt(), 0xFFDECB7B.toInt(), 0xFF9D8BC4.toInt(), 0xFF86A77A.toInt())
        colors.forEachIndexed { index, color ->
            val id = "qa-gallery-$index"
            val directory = library.projectDirectory(id).apply { mkdirs() }
            val manifest = java.io.File(directory, "manifest.json")
            if (!manifest.isFile) {
                manifest.writeText(JSONObject()
                    .put("schemaVersion", 2).put("id", id).put("name", "Sketch ${index + 1}")
                    .put("widthPx", 1200 + index * 100).put("heightPx", 900 + index * 80)
                    .put("modifiedAtMillis", 1_800_000_000_000L - index * 1_000L)
                    .put("selectedLayerId", "paint").put("layers", org.json.JSONArray()).toString())
                Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(color)
                    Canvas(bitmap).drawCircle(200f, 150f, 82f + index * 8f, android.graphics.Paint().apply {
                        this.color = AndroidColor.argb(180, 35, 38, 43)
                    })
                    java.io.File(directory, "thumbnail.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
        if (library.galleryItems().none { it is dev.tipstroke.drawing.android.GalleryStack }) {
            library.stackDrawing("qa-gallery-1", "drawing:qa-gallery-0")?.let { library.renameStack(it, "Character studies") }
        }
        return library
    }
}

class ScreenshotActivity : ComponentActivity()
