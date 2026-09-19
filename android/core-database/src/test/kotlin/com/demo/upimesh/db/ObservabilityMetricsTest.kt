package com.demo.upimesh.db

import com.demo.upimesh.db.metrics.LocalDatabaseMetrics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObservabilityMetricsTest {

    @BeforeEach
    fun setUp() {
        LocalDatabaseMetrics.reset()
    }

    @Test
    fun testMetricsCountersIncrement() {
        assertEquals(0L, LocalDatabaseMetrics.getLocalPaymentsCreated())
        assertEquals(0L, LocalDatabaseMetrics.getReceivedPackets())
        assertEquals(0L, LocalDatabaseMetrics.getDuplicatePackets())

        LocalDatabaseMetrics.recordLocalPaymentCreated()
        LocalDatabaseMetrics.recordLocalPaymentCreated()
        LocalDatabaseMetrics.recordReceivedPacket()
        LocalDatabaseMetrics.recordDuplicatePacket()
        LocalDatabaseMetrics.recordLocalPaymentRecovered()
        LocalDatabaseMetrics.recordReceiptVerificationFailure()
        LocalDatabaseMetrics.recordFragmentBuffer(5L)

        assertEquals(2L, LocalDatabaseMetrics.getLocalPaymentsCreated())
        assertEquals(1L, LocalDatabaseMetrics.getReceivedPackets())
        assertEquals(1L, LocalDatabaseMetrics.getDuplicatePackets())
        assertEquals(1L, LocalDatabaseMetrics.getLocalPaymentsRecovered())
        assertEquals(1L, LocalDatabaseMetrics.getReceiptVerificationFailures())
        assertEquals(5L, LocalDatabaseMetrics.getFragmentBuffers())
    }

    @Test
    fun testWalletOperationsMetricsUseOnlyBoundedLabels() {
        LocalDatabaseMetrics.recordWalletOperation("create", "success")
        LocalDatabaseMetrics.recordWalletOperation("spend", "success")
        LocalDatabaseMetrics.recordWalletOperation("validate", "expired")

        assertEquals(1L, LocalDatabaseMetrics.getWalletOperationCount("create", "success"))
        assertEquals(1L, LocalDatabaseMetrics.getWalletOperationCount("spend", "success"))
        assertEquals(1L, LocalDatabaseMetrics.getWalletOperationCount("validate", "expired"))
        assertEquals(0L, LocalDatabaseMetrics.getWalletOperationCount("spend", "failed"))
    }
}
