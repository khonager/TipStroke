package dev.tipstroke.drawing.android

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tipstroke.core.model.BrushPreset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkPreviewInstrumentedTest {
    @Test fun sampleAppearsOnOpenAndRefreshesAfterChangingTip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/${MediaPlaybackTestActivity::class.java.name}"
        ).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        ActivityScenario.launch<MediaPlaybackTestActivity>(Intent(context, MediaPlaybackTestActivity::class.java)).use { scenario ->
            lateinit var pad: InkBrushDrawingPad
            var rendered = CountDownLatch(1)
            scenario.onActivity { activity ->
                pad = InkBrushDrawingPad(activity)
                pad.sampleTimingListener = { assertTrue(it >= 0); rendered.countDown() }
                activity.setContentView(pad)
            }
            assertTrue("Initial sample requires a manual press", rendered.await(20, TimeUnit.SECONDS))
            scenario.onActivity {
                rendered = CountDownLatch(1)
                pad.setBrush(BrushPreset.BlankInk.copy(baseSizePx = 72f))
            }
            assertTrue("Changed tip did not refresh automatically", rendered.await(20, TimeUnit.SECONDS))
        }
    }
}
