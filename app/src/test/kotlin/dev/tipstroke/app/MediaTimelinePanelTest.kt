package dev.tipstroke.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.tipstroke.core.model.*
import dev.tipstroke.drawing.android.AnimationUiState
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaTimelinePanelTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @After fun close() { activity?.close() }
    private fun content(content: @Composable () -> Unit) {
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme { content() }
        }
    }
    @Test fun changingOnlyDrawingFpsDoesNotRewriteTheHold() {
        var fps = 12
        var holdChanges = 0
        val state = AnimationUiState(listOf(AnimationFrame(FrameId("a"), sourceTimeUs = 0, durationUs = 500_000)),
            0, 12, PlaybackMode.ONCE, false, 0, 0, 0f, emptySet(), hasMedia = true, mediaDurationUs = 2_000_000)
        content {
            MediaTimelinePanel(state, {}, {}, {}, {}, { holdChanges++ }, { fps = it }, {}, {})
        }
        compose.onNodeWithText("12 FPS").performClick()
        compose.onNodeWithText("Drawing FPS (1–60)").performTextReplacement("24")
        compose.onNodeWithText("Apply").performClick()
        assertEquals(24, fps)
        assertEquals(0, holdChanges)
    }
    @Test fun mediaExportOffersBothCadencesAndPassesTheChosenOne() {
        var request: AnimationExportRequest? = null
        content { AnimationExportDialog("Video", 640, 480, {}, {}, hasMedia = true) { request = it } }
        compose.onNodeWithText("Smooth video + held drawings").assertIsDisplayed()
        compose.onNodeWithText("Video at drawing FPS").performClick()
        compose.onNodeWithText("Export", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(MediaExportTiming.DRAWING_FPS, request?.timing)
    }
}
