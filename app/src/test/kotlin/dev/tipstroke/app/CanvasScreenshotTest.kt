package dev.tipstroke.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
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
import dev.tipstroke.core.model.RgbaColor
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasScreenshotTest {
    @Test fun renderLandscapeCanvasForVisualReview() = render(2560, 1600, "tipstroke-landscape.png") { CanvasScreen(initialLayersOpen = true) }

    @Test fun renderPortraitCanvasForVisualReview() = render(1600, 2560, "tipstroke-portrait.png") { CanvasScreen() }

    @Test fun renderCompactLandscapeCanvasForVisualReview() = render(1600, 720, "tipstroke-compact-landscape.png") { CanvasScreen() }

    @Test fun renderColorPickerForVisualReview() = render(2560, 1600, "tipstroke-color-picker.png") {
        Box(Modifier.fillMaxSize().background(Color(0xFF17181B)), contentAlignment = Alignment.Center) {
            ColorPickerPanel(RgbaColor(.2f, .45f, .9f), {}, {})
        }
    }

    @Test fun renderGalleryForVisualReview() = render(2560, 1600, "tipstroke-gallery.png") { activity ->
        GalleryScreen(DrawingLibrary(activity), {}, {}, {})
    }

    @Test fun renderGalleryPortraitForVisualReview() = render(1600, 2560, "tipstroke-gallery-portrait.png") { activity ->
        GalleryScreen(DrawingLibrary(activity), {}, {}, {})
    }

    @Test fun renderGalleryPhoneForVisualReview() = render(800, 1280, "tipstroke-gallery-phone.png") { activity ->
        GalleryScreen(DrawingLibrary(activity), {}, {}, {})
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

    private fun render(width: Int, height: Int, fileName: String, content: @Composable (ComponentActivity) -> Unit) {
        val activity = Robolectric.buildActivity(ScreenshotActivity::class.java).setup().get()
        activity.setContent { TipStrokeTheme { content(activity) } }
        shadowOf(activity.mainLooper).idle()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        shadowOf(activity.mainLooper).idle()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val output = File("build/qa/$fileName")
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(output.length() > 10_000) { "Screenshot render was unexpectedly empty" }
    }
}

class ScreenshotActivity : ComponentActivity()
