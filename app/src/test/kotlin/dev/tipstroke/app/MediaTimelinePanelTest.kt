package dev.tipstroke.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
            TipStrokeTheme { content() }
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
    @Test fun splitAndKeyframeControlsUpdateOneTrack() {
        var state by mutableStateOf(AnimationUiState(listOf(AnimationFrame(FrameId("drawing"), sourceTimeUs = 0)),
            0, 12, PlaybackMode.ONCE, false, 0, 0, 0f, emptySet(), hasMedia = true,
            mediaPositionUs = 1_000_000, mediaDurationUs = 4_000_000,
            videoEdit = VideoEdit(listOf(MediaClip("clip", "photo", "Photo", 0, 4_000_000))), photoAssetIds = setOf("photo")))
        content {
            MediaTimelinePanel(state, { state = state.copy(mediaPositionUs = it) }, {}, {}, {}, {}, {}, {}, {},
                onVideoEdit = { edit, time -> state = state.copy(videoEdit = edit, mediaDurationUs = edit.durationUs,
                    mediaPositionUs = time ?: state.mediaPositionUs) })
        }
        compose.onNodeWithText("Split here").performClick()
        assertEquals(2, state.videoEdit!!.clips.size)
        assertEquals(1_000_000L, state.videoEdit!!.clips[1].sourceInUs)
        compose.onNodeWithText("Keyframe here").performScrollTo().performClick()
        compose.onNodeWithText("Scale (%)").performScrollTo().performTextReplacement("150")
        compose.onNodeWithText("Save keyframe").performClick()
        assertEquals(1.5f, state.videoEdit!!.clips[1].keyframes.single().pose.scale)
        compose.onNodeWithText("Trim / duration").performScrollTo().performClick()
        compose.onNodeWithText("Photo duration (seconds)").performTextReplacement("8")
        compose.onNodeWithText("Apply trim").performClick()
        assertEquals(8_000_000L, state.videoEdit!!.clips[1].durationUs)
        compose.runOnIdle {
            val root = activity!!.get().window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            val file = java.io.File("build/qa/video-timeline.png").also { it.parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

}
