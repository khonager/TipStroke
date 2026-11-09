package dev.tipstroke.drawing.android

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import kotlin.math.ceil

enum class MemoryPressure { NORMAL, ELEVATED, CRITICAL }

internal data class MemoryBudgetSnapshot(
    val processBytes: Long,
    val processBudgetBytes: Long,
    val deviceAvailableBytes: Long,
    val documentBytes: Long,
    val fullLayerBytes: Long,
    val fullLayersRemaining: Int,
    val pressure: MemoryPressure,
)

/**
 * Produces an advisory memory budget, never a layer limit. Process PSS includes bitmap
 * pixel storage on current Android releases, while the document estimate explains how
 * much of that memory is attributable to permanent tiles, undo snapshots, and images.
 */
internal class MemoryBudgetAdvisor(context: Context) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private var lastSampleAtMs = Long.MIN_VALUE
    private var cached: MemoryBudgetSnapshot? = null
    private var nonDocumentBaselineBytes: Long? = null

    fun snapshot(
        canvasWidth: Int,
        canvasHeight: Int,
        documentBytes: Long,
        force: Boolean = false,
    ): MemoryBudgetSnapshot {
        val now = SystemClock.elapsedRealtime()
        cached?.takeIf { !force && now - lastSampleAtMs < SAMPLE_INTERVAL_MS }?.let { previous ->
            return previous.copy(documentBytes = documentBytes)
        }

        val device = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val process = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong() * 1024L
        val appHeapBytes = activityManager.memoryClass.toLong() * MIB
        val documentAllowance = (appHeapBytes * SAFE_PROCESS_FRACTION_NUMERATOR) / SAFE_PROCESS_FRACTION_DENOMINATOR
        val baseline = nonDocumentBaselineBytes ?: nonDocumentBaseline(process, documentBytes).also {
            nonDocumentBaselineBytes = it
        }
        // PSS contains code, shared pages, EGL buffers, and other graphics memory that is not
        // constrained by ActivityManager.memoryClass. Keep that observed baseline, then use a
        // conservative fraction of the heap class as the growing-document allowance.
        val processBudget = saturatingAdd(baseline, documentAllowance)
        val fullLayerBytes = fullRasterLayerBytes(canvasWidth, canvasHeight)
        val result = calculateMemoryBudget(
            processBytes = process,
            processBudgetBytes = processBudget,
            deviceAvailableBytes = device.availMem,
            deviceLowMemoryThresholdBytes = device.threshold,
            systemLowMemory = device.lowMemory,
            documentBytes = documentBytes,
            fullLayerBytes = fullLayerBytes,
            documentBudgetBytes = documentAllowance,
        )
        cached = result
        lastSampleAtMs = now
        return result
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val SAFE_PROCESS_FRACTION_NUMERATOR = 3L
        private const val SAFE_PROCESS_FRACTION_DENOMINATOR = 4L
        private const val TILE_SIZE = 256L
        private const val BYTES_PER_PIXEL = 4L
        private const val MIB = 1024L * 1024L

        internal fun fullRasterLayerBytes(width: Int, height: Int): Long {
            val columns = ceil(width.coerceAtLeast(1) / TILE_SIZE.toDouble()).toLong()
            val rows = ceil(height.coerceAtLeast(1) / TILE_SIZE.toDouble()).toLong()
            return columns * rows * TILE_SIZE * TILE_SIZE * BYTES_PER_PIXEL
        }
    }
}

internal fun nonDocumentBaseline(processBytes: Long, documentBytes: Long): Long =
    (processBytes - documentBytes).coerceAtLeast(0L)

private fun saturatingAdd(first: Long, second: Long): Long =
    if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

internal fun calculateMemoryBudget(
    processBytes: Long,
    processBudgetBytes: Long,
    deviceAvailableBytes: Long,
    deviceLowMemoryThresholdBytes: Long,
    systemLowMemory: Boolean,
    documentBytes: Long,
    fullLayerBytes: Long,
    documentBudgetBytes: Long = processBudgetBytes,
): MemoryBudgetSnapshot {
    val processHeadroom = (documentBudgetBytes - documentBytes).coerceAtLeast(0L)
    val systemHeadroom = (deviceAvailableBytes - deviceLowMemoryThresholdBytes).coerceAtLeast(0L)
    val usableHeadroom = minOf(processHeadroom, systemHeadroom)
    val remaining = if (fullLayerBytes > 0L) {
        (usableHeadroom / fullLayerBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else 0
    // Layer capacity is not painting headroom. On the one-layer maximum, reserving
    // another whole layer reported critical pressure after even a small stroke.
    // PSS also includes code and driver caches outside the Java heap allowance.
    val usage = if (documentBudgetBytes > 0L) documentBytes.toDouble() / documentBudgetBytes else 1.0
    val pressure = when {
        systemLowMemory || usage >= .95 || usableHeadroom < 8L * 1024 * 1024 -> MemoryPressure.CRITICAL
        usage >= .85 || usableHeadroom < 24L * 1024 * 1024 -> MemoryPressure.ELEVATED
        else -> MemoryPressure.NORMAL
    }
    return MemoryBudgetSnapshot(
        processBytes = processBytes,
        processBudgetBytes = processBudgetBytes,
        deviceAvailableBytes = deviceAvailableBytes,
        documentBytes = documentBytes,
        fullLayerBytes = fullLayerBytes,
        fullLayersRemaining = remaining,
        pressure = pressure,
    )
}
