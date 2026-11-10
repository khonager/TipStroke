package dev.tipstroke.drawing.android

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExportServiceInstrumentedTest {
    @Test fun foregroundExportFinishesAfterActivityIsBackgrounded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var failure: Throwable? = null
        val scenario = ActivityScenario.launch<MediaPlaybackTestActivity>(Intent(context, MediaPlaybackTestActivity::class.java))
        try {
            scenario.onActivity {
                assertTrue(ExportWork.start(it, work = { progress -> runCatching {
                    progress(.25f); started.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    progress(1f)
                } }, onComplete = { result -> failure = result.exceptionOrNull(); completed.countDown() }))
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertTrue(ExportWork.state.running)
            release.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertNull(failure)
            assertFalse(ExportWork.state.running)
            assertEquals("Export saved.", ExportWork.state.message)
        } finally { release.countDown(); scenario.close() }
    }
}
