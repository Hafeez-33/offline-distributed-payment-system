package com.demo.upimesh.bridge.metrics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WanBridgeMetricsTest {

    @BeforeEach
    fun setUp() {
        WanBridgeMetrics.reset()
    }

    @Test
    fun testMetricsCountersAndGauges() {
        assertEquals(0L, WanBridgeMetrics.getUploadAttemptsTotal())
        assertEquals(0L, WanBridgeMetrics.getUploadSuccessTotal())
        assertEquals(0L, WanBridgeMetrics.getUploadRetryTotal())
        assertEquals(0L, WanBridgeMetrics.getUploadPermanentFailureTotal())
        assertEquals(0L, WanBridgeMetrics.getUploadBytesTotal())
        assertEquals(0L, WanBridgeMetrics.getReceiptsVerifiedTotal())
        assertEquals(0L, WanBridgeMetrics.getReceiptsRejectedTotal())
        assertEquals(0L, WanBridgeMetrics.getQueueDepth())
        assertEquals(0L, WanBridgeMetrics.getWorkerRunsTotal())

        WanBridgeMetrics.recordUploadAttempt()
        WanBridgeMetrics.recordUploadAttempt()
        WanBridgeMetrics.recordUploadSuccess()
        WanBridgeMetrics.recordUploadRetry()
        WanBridgeMetrics.recordUploadPermanentFailure()
        WanBridgeMetrics.recordBytesUploaded(1024L)
        WanBridgeMetrics.recordReceiptVerified()
        WanBridgeMetrics.recordReceiptRejected()
        WanBridgeMetrics.setQueueDepth(15L)
        WanBridgeMetrics.recordWorkerRun()

        assertEquals(2L, WanBridgeMetrics.getUploadAttemptsTotal())
        assertEquals(1L, WanBridgeMetrics.getUploadSuccessTotal())
        assertEquals(1L, WanBridgeMetrics.getUploadRetryTotal())
        assertEquals(1L, WanBridgeMetrics.getUploadPermanentFailureTotal())
        assertEquals(1024L, WanBridgeMetrics.getUploadBytesTotal())
        assertEquals(1L, WanBridgeMetrics.getReceiptsVerifiedTotal())
        assertEquals(1L, WanBridgeMetrics.getReceiptsRejectedTotal())
        assertEquals(15L, WanBridgeMetrics.getQueueDepth())
        assertEquals(1L, WanBridgeMetrics.getWorkerRunsTotal())

        WanBridgeMetrics.reset()
        assertEquals(0L, WanBridgeMetrics.getUploadAttemptsTotal())
        assertEquals(0L, WanBridgeMetrics.getQueueDepth())
    }
}
