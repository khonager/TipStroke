package dev.tipstroke.drawing.android

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportWorkTest {
    @Test fun secondExportCannotReplacePendingSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        ExportWork.publish(ExportWork.State())
        val first: ((Float) -> Unit) -> Result<Unit> = { Result.success(Unit) }
        try {
            assertTrue(ExportWork.start(context, first))
            var failure: Throwable? = null
            assertFalse(ExportWork.start(context, { error("Must not execute") }) { failure = it.exceptionOrNull() })
            assertSame(first, ExportWork.pending)
            assertNotNull(failure)
        } finally {
            ExportWork.pending = null; ExportWork.complete = null
            ExportWork.publish(ExportWork.State())
        }
    }
}
