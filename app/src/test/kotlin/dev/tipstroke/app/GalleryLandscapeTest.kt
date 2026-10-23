package dev.tipstroke.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.junit.After
import androidx.test.core.app.ApplicationProvider
import dev.tipstroke.drawing.android.DrawingLibrary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryLandscapeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @After fun closeActivity() { activity?.close() }

    @Test fun tallDrawingPreviewsAreBoundedAndGalleryScrollsToLastCard() {
        val library = DrawingLibrary(ApplicationProvider.getApplicationContext())
        repeat(12) { index ->
            val id = "landscape-$index"
            val directory = library.projectDirectory(id).apply { mkdirs() }
            File(directory, "manifest.json").writeText(JSONObject()
                .put("schemaVersion", 2).put("id", id).put("name", "Portrait $index")
                .put("widthPx", 128).put("heightPx", 4096)
                .put("modifiedAtMillis", 1800000000000L - index)
                .put("selectedLayerId", "paint").put("layers", JSONArray()).toString())
            val bitmap = Bitmap.createBitmap(8, 256, Bitmap.Config.ARGB_8888)
            File(directory, "thumbnail.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent { TipStrokeTheme { GalleryScreen(library, {}, {}, {}) } }
        val grid = compose.onNodeWithContentDescription("Gallery drawings")
        assertTrue(grid.fetchSemanticsNode().boundsInRoot.height > 240f)
        val thumbnail = compose.onNodeWithContentDescription("Portrait 0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Tall document should fit a bounded thumbnail", thumbnail.height <= 120f)
        grid.performScrollToNode(hasText("Portrait 11"))
        compose.onNodeWithText("Portrait 11").assertIsDisplayed()
        compose.onNodeWithText("Your drawings").assertDoesNotExist()
    }
}
