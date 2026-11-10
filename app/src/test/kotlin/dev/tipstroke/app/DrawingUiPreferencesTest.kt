package dev.tipstroke.app
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DrawingUiPreferencesTest {
    @Test fun animationDefaultsOpenAndRemembersEachDrawingsManualChoice() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("drawing-ui", 0).edit().clear().commit()
        val preferences = DrawingUiPreferences(context)
        assertTrue(preferences.animationOpen("new-animation"))
        preferences.saveAnimationOpen("first", false)
        preferences.saveAnimationOpen("second", true)
        val reopened = DrawingUiPreferences(context)
        assertFalse(reopened.animationOpen("first"))
        assertTrue(reopened.animationOpen("second"))
        reopened.saveAnimationOpen("first", true)
        assertTrue(DrawingUiPreferences(context).animationOpen("first"))
    }
}
