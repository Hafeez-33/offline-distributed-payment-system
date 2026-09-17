package com.demo.upimesh.bridge.metrics

import java.util.concurrent.atomic.AtomicLong

/**
 * Observability metrics for the Android WAN Bridge Subsystem.
 * All metrics use strictly bounded cardinality (no transaction IDs, packet hashes, or user VPAs).
 */
object WanBridgeMetrics {

    private val uploadAttemptsTotal = AtomicLong(0)
    private val uploadSuccessTotal = AtomicLong(0)
    private val uploadRetryTotal = AtomicLong(0)
    private val uploadPermanentFailureTotal = AtomicLong(0)
    private val uploadBytesTotal = AtomicLong(0)
    private val receiptsVerifiedTotal = AtomicLong(0)
    private val receiptsRejectedTotal = AtomicLong(0)
    private val queueDepth = AtomicLong(0)
    private val workerRunsTotal = AtomicLong(0)

    fun recordUploadAttempt() {
        uploadAttemptsTotal.incrementAndGet()
    }

    fun recordUploadSuccess() {
        uploadSuccessTotal.incrementAndGet()
    }

    fun recordUploadRetry() {
        uploadRetryTotal.incrementAndGet()
    }

    fun recordUploadPermanentFailure() {
        uploadPermanentFailureTotal.incrementAndGet()
    }

    fun recordBytesUploaded(bytes: Long) {
        if (bytes > 0) {
            uploadBytesTotal.addAndGet(bytes)
        }
    }

    fun recordReceiptVerified() {
        receiptsVerifiedTotal.incrementAndGet()
    }

    fun recordReceiptRejected() {
        receiptsRejectedTotal.incrementAndGet()
    }

    fun setQueueDepth(depth: Long) {
        queueDepth.set(maxOf(0L, depth))
    }

    fun recordWorkerRun() {
        workerRunsTotal.incrementAndGet()
    }

    // Getters for testing and diagnostic reporting
    fun getUploadAttemptsTotal(): Long = uploadAttemptsTotal.get()
    fun getUploadSuccessTotal(): Long = uploadSuccessTotal.get()
    fun getUploadRetryTotal(): Long = uploadRetryTotal.get()
    fun getUploadPermanentFailureTotal(): Long = uploadPermanentFailureTotal.get()
    fun getUploadBytesTotal(): Long = uploadBytesTotal.get()
    fun getReceiptsVerifiedTotal(): Long = receiptsVerifiedTotal.get()
    fun getReceiptsRejectedTotal(): Long = receiptsRejectedTotal.get()
    fun getQueueDepth(): Long = queueDepth.get()
    fun getWorkerRunsTotal(): Long = workerRunsTotal.get()

    fun reset() {
        uploadAttemptsTotal.set(0)
        uploadSuccessTotal.set(0)
        uploadRetryTotal.set(0)
        uploadPermanentFailureTotal.set(0)
        uploadBytesTotal.set(0)
        receiptsVerifiedTotal.set(0)
        receiptsRejectedTotal.set(0)
        queueDepth.set(0)
        workerRunsTotal.set(0)
    }
}
