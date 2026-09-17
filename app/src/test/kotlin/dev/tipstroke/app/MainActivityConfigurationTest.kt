package dev.tipstroke.app

import android.content.pm.ActivityInfo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityConfigurationTest {
    @Test fun orientationAndScreenLayoutChangesKeepTheActiveEditorActivity() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val activityInfo = activity.packageManager.getActivityInfo(activity.componentName, 0)

        assertTrue(activityInfo.configChanges and ActivityInfo.CONFIG_ORIENTATION != 0)
        assertTrue(activityInfo.configChanges and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0)
        assertTrue(activityInfo.configChanges and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
        assertTrue(activityInfo.configChanges and ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE != 0)
    }
}
