package dev.tipstroke.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.tipstroke.core.model.FingerAction
import dev.tipstroke.core.model.GestureSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var settings by mutableStateOf(GestureSettings())
    @After fun close() { activity?.close() }

    private fun showSettings() {
        Robolectric.buildActivity(ComponentActivity::class.java).also { activity = it }.setup().get().setContent {
            MaterialTheme { SettingsScreen(settings, { settings = it }, {}) }
        }
    }

    private val radioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    @Config(qualifiers = "w900dp-h360dp-land-mdpi")
    fun shortWidePanelScrollsToDisabledAndUpdatesSelection() {
        showSettings()
        compose.onAllNodes(radioButton).assertCountEquals(FingerAction.entries.size)
        compose.onNode(radioButton and hasText("Disabled")).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(FingerAction.DISABLED, settings.oneFingerDrag) }
    }

    @Test
    @Config(qualifiers = "w390dp-h400dp-port-mdpi")
    fun shortDialogScrollsToDisabledAndClosesAfterSelection() {
        showSettings()
        compose.onNodeWithText("One-finger drag").performClick()
        compose.onNode(radioButton and hasText("Disabled")).performScrollTo().assertIsDisplayed().performClick()
        compose.onAllNodes(radioButton).assertCountEquals(0)
        compose.runOnIdle { assertEquals(FingerAction.DISABLED, settings.oneFingerDrag) }
    }
}
