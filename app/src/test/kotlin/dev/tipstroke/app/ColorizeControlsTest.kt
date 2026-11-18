package dev.tipstroke.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.tipstroke.drawing.android.DrawingSurface
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w800dp-h400dp-land-mdpi")
class ColorizeControlsTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun createAndEditHintsRemainReachableInShortLandscapeDialog() {
        val surface=DrawingSurface(RuntimeEnvironment.getApplication()).apply { configureBlank(32,32) }
        var editing=false
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { MaterialTheme { ColorizeMaskDialog(surface,{editing=true},{}) } }
            compose.onNodeWithText("Create mask").assertIsDisplayed().performClick()
            compose.runOnIdle { assertTrue(editing);assertNotNull(surface.colorizeMaskSettings()) }
        } finally { activity.close() }
    }
    @Test fun existingMaskHasScrollableHintsAndRequiresPreviewBeforeApply() {
        val surface=DrawingSurface(RuntimeEnvironment.getApplication()).apply { configureBlank(32,32);createColorizeMask() }
        var editing=false
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { MaterialTheme { ColorizeMaskDialog(surface,{editing=true},{}) } }
            compose.onNodeWithText("Apply").assertIsNotEnabled()
            compose.onNodeWithText("Edit hints").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertTrue(editing) }
        } finally { activity.close() }
    }
}
