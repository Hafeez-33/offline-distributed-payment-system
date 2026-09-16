package com.demo.upimesh.db.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded local telemetry for Android persistence and offline wallet operations.
 *
 * CRITICAL METRIC HYGIENE:
 * High-cardinality sensitive labels (packetHash, walletId, VPA, transactionId, nonce)
 * are STRICTLY PROHIBITED. Only bounded status/operation enum names are allowed.
 */
object LocalDatabaseMetrics {

    private val walletOperations = ConcurrentHashMap<String, AtomicLong>()
    private val localPaymentsCreated = AtomicLong(0)
    private val localPaymentsRecovered = AtomicLong(0)
    private val receivedPackets = AtomicLong(0)
    private val duplicatePackets = AtomicLong(0)
    private val fragmentBuffers = AtomicLong(0)
    private val receiptVerificationFailures = AtomicLong(0)

    fun recordWalletOperation(operation: String, status: String) {
        // Bounded label sanitization: only allow alphanumeric + underscore
        val key = "${operation.lowercase()}_${status.lowercase()}"
        walletOperations.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }

    fun recordLocalPaymentCreated() {
        localPaymentsCreated.incrementAndGet()
    }

    fun recordLocalPaymentRecovered() {
        localPaymentsRecovered.incrementAndGet()
    }

    fun recordReceivedPacket() {
        receivedPackets.incrementAndGet()
    }

    fun recordDuplicatePacket() {
        duplicatePackets.incrementAndGet()
    }

    fun recordFragmentBuffer(total: Long) {
        fragmentBuffers.set(total)
    }

    fun recordReceiptVerificationFailure() {
        receiptVerificationFailures.incrementAndGet()
    }

    fun getWalletOperationCount(operation: String, status: String): Long {
        val key = "${operation.lowercase()}_${status.lowercase()}"
        return walletOperations[key]?.get() ?: 0L
    }

    fun getLocalPaymentsCreated(): Long = localPaymentsCreated.get()
    fun getLocalPaymentsRecovered(): Long = localPaymentsRecovered.get()
    fun getReceivedPackets(): Long = receivedPackets.get()
    fun getDuplicatePackets(): Long = duplicatePackets.get()
    fun getFragmentBuffers(): Long = fragmentBuffers.get()
    fun getReceiptVerificationFailures(): Long = receiptVerificationFailures.get()

    fun reset() {
        walletOperations.clear()
        localPaymentsCreated.set(0)
        localPaymentsRecovered.set(0)
        receivedPackets.set(0)
        duplicatePackets.set(0)
        fragmentBuffers.set(0)
        receiptVerificationFailures.set(0)
    }
}
