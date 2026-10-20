package dev.tipstroke.drawing.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** App-private, bounded diagnostics. Routine events never perform disk I/O on the input thread. */
object LocalDiagnostics {
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(128),
        { task -> Thread(task, "TipStroke-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy())
    @Volatile private var store: DiagnosticFiles? = null

    @Synchronized
    fun initialize(context: Context, version: String) {
        if (store != null) return
        val app = context.applicationContext
        val files = DiagnosticFiles(File(app.filesDir, "diagnostics"))
        store = files
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashLogHandler(files, previous))
        record("process start version=$version android=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}")
        executor.execute {
            if (Build.VERSION.SDK_INT >= 30) {
                runCatching {
                    val manager = app.getSystemService(ActivityManager::class.java)
                    manager.getHistoricalProcessExitReasons(null, 0, 5).forEach { exit ->
                        files.append("Android process exit at ${Instant.ofEpochMilli(exit.timestamp)}: " +
                            "${exitReason(exit.reason)} status=${exit.status} importance=${exit.importance} " +
                            "pssKiB=${exit.pss} rssKiB=${exit.rss} description=${exit.description.orEmpty()}")
                    }
                }.onFailure { files.append("Could not read Android process exits", it) }
            }
        }
    }

    fun record(event: String, failure: Throwable? = null) {
        val files = store ?: return
        executor.execute { files.append(event, failure) }
    }

    fun readReport(onComplete: (String) -> Unit) {
        executor.execute {
            val report = store?.readReport() ?: "Diagnostics have not been initialized."
            Handler(Looper.getMainLooper()).post { onComplete(report) }
        }
    }

    fun clear(onComplete: () -> Unit) {
        executor.execute {
            store?.clear()
            Handler(Looper.getMainLooper()).post(onComplete)
        }
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "App crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_ANR -> "App not responding (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Android reclaimed memory"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "Stopped by user/system request"
        ApplicationExitInfo.REASON_SIGNALED -> "Process killed by signal"
        ApplicationExitInfo.REASON_EXIT_SELF -> "App exited"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource use"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission changed"
        ApplicationExitInfo.REASON_OTHER -> "Other Android reason"
        else -> "Android reason $reason"
    }
}

internal class CrashLogHandler(
    private val files: DiagnosticFiles,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, failure: Throwable) {
        try {
            // This is deliberately synchronous: an async write may never run before termination.
            files.writeCrash(thread.name, failure)
        } catch (_: Throwable) {
            // Diagnostics must never interfere with Android's crash handling.
        } finally {
            if (previous != null) previous.uncaughtException(thread, failure)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}

internal class DiagnosticFiles(private val directory: File) {
    private val events = File(directory, "events.log")
    private val olderEvents = File(directory, "events.previous.log")
    private val crash = AtomicFile(File(directory, "last-crash.txt"))

    @Synchronized
    fun append(event: String, failure: Throwable? = null) {
        runCatching {
            directory.mkdirs()
            val text = "${Instant.now()} $event${failure?.let { "\n${it.stackTraceToString()}" }.orEmpty()}\n"
                .toByteArray().take(MAX_ENTRY_BYTES).toByteArray()
            if (events.length() + text.size > MAX_LOG_BYTES) {
                olderEvents.delete()
                check(!events.exists() || events.renameTo(olderEvents))
            }
            events.appendBytes(text)
        }
    }

    @Synchronized
    fun writeCrash(thread: String, failure: Throwable) {
        directory.mkdirs()
        val runtime = Runtime.getRuntime()
        val text = "${Instant.now()} Uncaught ${failure.javaClass.name} on thread $thread\n" +
            "Heap used=${runtime.totalMemory() - runtime.freeMemory()} max=${runtime.maxMemory()} bytes\n" +
            failure.stackTraceToString()
        val output = crash.startWrite()
        try {
            output.write(text.toByteArray().take(MAX_CRASH_BYTES).toByteArray())
            crash.finishWrite(output)
        } catch (error: Throwable) {
            crash.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun readReport(): String = runCatching {
        buildString {
            appendLine("TIPSTROKE LOCAL DIAGNOSTICS")
            appendLine("Android may stop the app without a Java crash. On Android 11+, recent system exit reasons are included when available.")
            appendLine("\nLAST UNCAUGHT CRASH")
            appendLine(if (crash.baseFile.exists()) crash.openRead().bufferedReader().use { it.readText() }
                else "No uncaught crash recorded.")
            appendLine("\nRECENT EVENTS (oldest first)")
            if (olderEvents.exists()) appendLine(olderEvents.readText())
            if (events.exists()) appendLine(events.readText())
        }
    }.getOrElse { "Could not read diagnostics: ${it.message}" }

    @Synchronized
    fun clear() {
        events.delete()
        olderEvents.delete()
        crash.delete()
    }

    companion object {
        const val MAX_LOG_BYTES = 128 * 1024
        const val MAX_ENTRY_BYTES = 16 * 1024
        const val MAX_CRASH_BYTES = 64 * 1024
    }
}
