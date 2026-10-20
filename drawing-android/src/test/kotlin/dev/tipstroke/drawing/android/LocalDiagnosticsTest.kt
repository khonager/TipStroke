package dev.tipstroke.drawing.android

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalDiagnosticsTest {
    @Test fun crashSurvivesRestartAndDelegatesToAndroidHandler() {
        val directory = Files.createTempDirectory("tipstroke-crash").toFile()
        try {
            val files = DiagnosticFiles(directory)
            files.append("project save queued id=test")
            val failure = IllegalStateException("test crash", IllegalArgumentException("root cause"))
            var delegated: Throwable? = null
            CrashLogHandler(files) { _, error -> delegated = error }
                .uncaughtException(Thread.currentThread(), failure)
            assertSame(failure, delegated)
            val restarted = DiagnosticFiles(directory)
            val report = restarted.readReport()
            assertTrue(report.contains("test crash"))
            assertTrue(report.contains("root cause"))
            assertTrue(report.contains("project save queued"))
            restarted.clear()
            assertFalse(restarted.readReport().contains("test crash"))
            assertFalse(restarted.readReport().contains("project save queued"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun routineLogsRotateAndKeepTheLatestCrashSeparately() {
        val directory = Files.createTempDirectory("tipstroke-log-limit").toFile()
        try {
            val files = DiagnosticFiles(directory)
            files.writeCrash("test", RuntimeException("retained crash"))
            repeat(100) { files.append("event-$it " + "x".repeat(20_000)) }
            val report = files.readReport()
            assertTrue(report.contains("retained crash"))
            assertTrue(report.contains("event-99"))
            assertFalse(report.contains("event-0 "))
            assertTrue(directory.listFiles()!!.sumOf { it.length() } <=
                2L * DiagnosticFiles.MAX_LOG_BYTES + DiagnosticFiles.MAX_CRASH_BYTES)
        } finally { directory.deleteRecursively() }
    }

    @Test fun loggingFailureDoesNotPreventCrashDelegation() {
        val notDirectory = Files.createTempFile("tipstroke-unwritable", ".tmp").toFile()
        try {
            var delegated = false
            CrashLogHandler(DiagnosticFiles(notDirectory)) { _, _ -> delegated = true }
                .uncaughtException(Thread.currentThread(), RuntimeException("crash"))
            assertTrue(delegated)
        } finally { notDirectory.delete() }
    }
}
