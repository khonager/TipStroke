package dev.tipstroke.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.tipstroke.core.model.LayerBlendMode
import dev.tipstroke.core.model.LayerId
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
@Config(sdk = [35], qualifiers = "w400dp-h700dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlendModeScrollerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @After fun close() { activity?.close() }

    @Test fun scrollingPreviewsWithoutTappingAndClosingFinishesOnce() {
        var current by mutableStateOf(LayerBlendMode.COLOR_DODGE)
        var open by mutableStateOf(true)
        var starts = 0
        var finishes = 0
        val previews = mutableListOf<LayerBlendMode>()
        val host = Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get()
        host.setContent {
            MaterialTheme {
                if (open) BlendModeScroller(LayerId("paint"), current,
                    onPreview = { current = it; previews += it },
                    onStart = { starts++ }, onFinish = { finishes++ })
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(LayerBlendMode.COLOR_DODGE, current); assertEquals(1, starts) }
        compose.onNodeWithContentDescription("Blend mode previews").performTouchInput {
            swipeUp(durationMillis = 500)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertNotEquals(LayerBlendMode.COLOR_DODGE, current); assertTrue(previews.distinct().size > 1) }
        compose.onNodeWithContentDescription("Blend mode previews").performScrollToIndex(0)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(LayerBlendMode.NORMAL, current) }
        compose.onNodeWithContentDescription("Blend mode previews").performScrollToIndex(LayerBlendMode.entries.lastIndex)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(LayerBlendMode.LUMINOSITY, current); open = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, finishes) }
    }
}
