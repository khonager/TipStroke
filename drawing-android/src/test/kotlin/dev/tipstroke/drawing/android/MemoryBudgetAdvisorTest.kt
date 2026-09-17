package dev.tipstroke.drawing.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryBudgetAdvisorTest {
    @Test fun fullLayerEstimateRoundsUpToSparseTileBoundaries() {
        assertEquals(16L * MIB, MemoryBudgetAdvisor.fullRasterLayerBytes(2048, 2048))
        assertEquals(1L * MIB, MemoryBudgetAdvisor.fullRasterLayerBytes(257, 257))
    }

    @Test fun remainingLayersUseTheTighterProcessOrSystemHeadroom() {
        val result = calculateMemoryBudget(
            processBytes = 200L * MIB,
            processBudgetBytes = 400L * MIB,
            deviceAvailableBytes = 180L * MIB,
            deviceLowMemoryThresholdBytes = 100L * MIB,
            systemLowMemory = false,
            documentBytes = 40L * MIB,
            fullLayerBytes = 16L * MIB,
        )

        assertEquals(5, result.fullLayersRemaining)
        assertEquals(MemoryPressure.NORMAL, result.pressure)
    }

    @Test fun processBaselineExcludesTrackedDocumentMemory() {
        assertEquals(300L * MIB, nonDocumentBaseline(340L * MIB, 40L * MIB))
        assertEquals(0L, nonDocumentBaseline(20L * MIB, 40L * MIB))
    }

    @Test fun lowMemoryNeverBlocksLayersButReportsCriticalPressure() {
        val result = calculateMemoryBudget(
            processBytes = 100L * MIB,
            processBudgetBytes = 400L * MIB,
            deviceAvailableBytes = 120L * MIB,
            deviceLowMemoryThresholdBytes = 100L * MIB,
            systemLowMemory = true,
            documentBytes = 20L * MIB,
            fullLayerBytes = 32L * MIB,
        )

        assertEquals(0, result.fullLayersRemaining)
        assertEquals(MemoryPressure.CRITICAL, result.pressure)
    }

    private companion object { const val MIB = 1024L * 1024L }
}
