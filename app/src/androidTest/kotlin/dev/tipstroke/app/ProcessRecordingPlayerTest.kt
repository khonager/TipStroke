package dev.tipstroke.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ProcessRecordingPlayerTest {
    @get:Rule val compose = createComposeRule()
    private val handler = Handler(Looper.getMainLooper())
    private fun frame(index: Int) = Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply {
        eraseColor(if (index == 7) Color.RED else Color.WHITE)
    }
    private fun show(render: (Int, (Result<Bitmap>) -> Unit) -> Unit) {
        compose.setContent {
            MaterialTheme { ProcessRecordingPlayer(8,15258,null,"Recording drawing progress",render,{}, {}) }
        }
    }
    private fun waitForStep(step: Int) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("$step / 8 steps",substring=true).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun assertFinishedImage() {
        val image=compose.onNodeWithContentDescription("Drawing progress replay").captureToImage()
        val pixel=image.toPixelMap()[image.width/2,image.height/2]
        assertEquals(1f,pixel.red,.01f)
        assertEquals(0f,pixel.green,.01f)
        assertEquals(0f,pixel.blue,.01f)
    }
    @Test fun loadingNextFrameDoesNotMovePlaybackControls() {
        var pending: ((Result<Bitmap>) -> Unit)? = null
        show { index, callback ->
            if (index == 1) pending = callback else handler.post { callback(Result.success(frame(index))) }
        }
        waitForStep(1)
        compose.onNodeWithText("Play").performScrollTo().performClick()
        compose.waitUntil(5_000) { pending != null }
        val loading = compose.onNodeWithText("Restart").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { pending!!(Result.success(frame(1))) }
        waitForStep(8)
        assertEquals(loading,compose.onNodeWithText("Restart").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun delayedThirdStepStillPlaysAllEightFramesAndStops() {
        show { index, callback -> handler.postDelayed({ callback(Result.success(frame(index))) }, if(index==2) 300 else 10) }
        waitForStep(1)
        compose.onNodeWithText("Play").performScrollTo().performClick()
        waitForStep(8)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Play").fetchSemanticsNodes().isNotEmpty() }
        assertFinishedImage()
        compose.onNodeWithText("Restart").performScrollTo().performClick()
        waitForStep(1)
    }
    @Test fun seekToEndWinsOverLateThirdStepAndShowsFinishedDrawing() {
        var third: ((Result<Bitmap>) -> Unit)? = null
        show { index, callback ->
            if (index == 2) third=callback else handler.post { callback(Result.success(frame(index))) }
        }
        waitForStep(1)
        compose.onNodeWithText("Play").performScrollTo().performClick()
        compose.waitUntil(5_000) { third != null }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
        waitForStep(8)
        val stale=frame(2)
        compose.runOnIdle { third!!(Result.success(stale)) }
        compose.waitForIdle()
        assertTrue(stale.isRecycled)
        assertFinishedImage()
        compose.onNodeWithText("8 / 8 steps",substring=true).assertExists()
        compose.onNodeWithText("Play").assertExists()
    }
    @Test fun renderFailureIsVisibleAndPausesPlayback() {
        show { index, callback -> handler.post {
            callback(if(index==2) Result.failure(IllegalStateException("Cannot render test step")) else Result.success(frame(index)))
        } }
        waitForStep(1)
        compose.onNodeWithText("Play").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Cannot render test step").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Play").assertExists()
        compose.onNodeWithText("2 / 8 steps",substring=true).assertExists()
    }
}
