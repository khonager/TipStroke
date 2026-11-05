package dev.tipstroke.drawing.android

import android.app.ActivityManager
import android.content.Context

/** Conservative admission limit; sparse storage still allocates only painted tiles. */
object LayerCapacity {
    fun maximum(context: Context, width: Int, height: Int): Int = maximum(
        context.getSystemService(ActivityManager::class.java).memoryClass, width, height)

    internal fun maximum(heapMiB: Int, width: Int, height: Int): Int {
        val allowance = heapMiB.toLong() * 1024 * 1024 * 3 / 4
        val layerBytes = MemoryBudgetAdvisor.fullRasterLayerBytes(width, height)
        // Reserve two full layers for an in-flight edit and undo; cap metadata too.
        return (allowance / layerBytes - 2).coerceIn(1, 64).toInt()
    }
}
